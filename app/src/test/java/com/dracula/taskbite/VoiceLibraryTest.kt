package com.dracula.taskbite

import android.app.Application
import android.media.MediaMetadataRetriever
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VoiceLibraryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun recording(duration: String = "2300", hasAudio: String = "yes"): File {
        val file = File.createTempFile("test-voice", ".m4a", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_DURATION, duration)
        ShadowMediaMetadataRetriever.addMetadata(file.path, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, hasAudio)
        return file
    }
    @Test fun actorsOnlyOfferRealAssetsForTheirCoveredCategories() {
        val library = VoiceLibrary(context)
        assertTrue(library.actors().map { it.id }.containsAll(listOf("alex", "sean", "meghan")))
        for (actor in library.actors()) {
            Preferences(context).voiceActor = actor.id
            for (kind in RoastKind.entries) {
                val clips = library.bundled(kind)
                if (actor.id in VoiceLibrary.SHORT_REACTION_ACTORS) {
                    assertEquals(kind in VoiceLibrary.SHORT_REACTION_KINDS, clips.isNotEmpty())
                } else assertTrue(clips.isNotEmpty())
                clips.forEach { clip -> context.assets.open(requireNotNull(clip.asset)).use { input ->
                    val pcm = PcmAudio.read(input)
                    assertEquals(44100, pcm.sampleRate)
                    assertEquals(1, pcm.channels)
                    assertTrue(pcm.frameCount > 0)
                } }
                val first = library.choose(kind)
                if (clips.size > 1) assertNotEquals(first, library.choose(kind))
            }
        }
    }
    @Test fun customImportPersistsAndOnlyOverridesItsCategoryWhenEnabled() {
        val source = recording()
        val library = VoiceLibrary(context)
        val clip = library.importFile(RoastKind.SNOOZE, source, "Tomorrow again?")
        source.delete() // Import owns a copy, independent of the picker or original file.
        assertTrue(clip.file!!.isFile)
        assertNull(library.choose(RoastKind.SNOOZE))
        Preferences(context).customVoice = true
        val reopened = VoiceLibrary(context)
        assertEquals(clip, reopened.choose(RoastKind.SNOOZE))
        assertNotNull(reopened.choose(RoastKind.COMPLETED)!!.asset)
        assertEquals("Tomorrow again?", reopened.custom(RoastKind.SNOOZE).single().label)
        reopened.remove(clip)
        assertFalse(clip.file.exists())
        assertNull(reopened.choose(RoastKind.SNOOZE))
    }
    @Test fun clearingNeverBorrowsLeavingAudioButPreservesTheOriginalRecording() {
        val library = VoiceLibrary(context)
        val prefs = Preferences(context)
        for (style in TeasingStyle.entries) {
            prefs.teasingStyle = style
            prefs.customVoice = true
            val legacy = library.importFile(RoastKind.ABANDON, recording(), "My leaving recording", style, "Come back soon.")
            assertNull(library.choose(RoastKind.NOTIFICATION_CLEARED))
            val own = library.importFile(RoastKind.NOTIFICATION_CLEARED, recording(), "My clearing recording", style, "Nice swipe.")
            assertEquals(own, library.choose(RoastKind.NOTIFICATION_CLEARED))
            assertEquals(legacy, library.choose(RoastKind.ABANDON))
            assertTrue(legacy.file!!.isFile)
            library.remove(own)
            assertNull(VoiceLibrary(context).choose(RoastKind.NOTIFICATION_CLEARED))
            prefs.customVoice = false
            assertNull(library.choose(RoastKind.NOTIFICATION_CLEARED))
        }
    }
    @Test fun invalidAndOversizedAudioDoNotReplaceSavedRecordings() {
        val library = VoiceLibrary(context)
        val clip = library.importFile(RoastKind.OVERDUE, recording(), "Saved roast")
        listOf(recording("30001"), recording("0"), recording(hasAudio = "no")).forEach {
            assertThrows(IllegalArgumentException::class.java) { library.importFile(RoastKind.OVERDUE, it, "Bad") }
        }
        val oversized = ByteArray(VoiceLibrary.MAX_CLIP_BYTES + 1).inputStream()
        assertThrows(IllegalArgumentException::class.java) { library.importStream(RoastKind.OVERDUE, oversized, "Too large") }
        assertEquals(listOf(clip), library.custom(RoastKind.OVERDUE))
        assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("voice-import-") })
    }
    @Test fun sweetNeverFallsBackToUnrelatedClassicRecordings() {
        val library = VoiceLibrary(context)
        Preferences(context).apply { voiceActor = "sean"; teasingStyle = TeasingStyle.SWEET }
        RoastKind.entries.forEach { assertNull(library.choose(it)); assertTrue(library.bundled(it).isEmpty()) }
        val words = "One little task, sweetheart."
        val clip = library.importFile(RoastKind.PLAN_DAY, recording(), words, caption = words)
        Preferences(context).customVoice = true
        assertEquals(clip, VoiceLibrary(context).choose(RoastKind.PLAN_DAY))
        assertNull(library.choose(RoastKind.CREATED))
        assertEquals(words, library.reaction(RoastKind.PLAN_DAY).caption)
        Preferences(context).teasingStyle = TeasingStyle.CLASSIC
        assertNull(library.choose(RoastKind.PLAN_DAY))
        assertTrue(library.custom(RoastKind.PLAN_DAY).isEmpty())
    }
    @Test fun selectionKeepsCaptionAndClipTogetherAcrossReplayRecreationAndActorChanges() {
        val library = VoiceLibrary(context)
        for (kind in RoastKind.entries) {
            val reaction = library.reaction(kind)
            if (reaction.clip != null) assertEquals(reaction.clip.caption, reaction.caption)
            else assertTrue(reaction.caption in Roasts.options(kind))
            val saved = reaction.saved()
            library.choose(kind) // A different reaction must not mutate the saved selection.
            Preferences(context).voiceActor = "meghan"
            assertEquals(reaction, VoiceReaction.restore(saved))
        }
    }
    @Test fun legacyRecordingsSurviveAndCanBeAssignedCaptionAndSweetStyleWithoutCopyingAudio() {
        val library = VoiceLibrary(context)
        val imported = library.importFile(RoastKind.OVERDUE, recording(), "old-file.m4a")
        File(imported.file!!.parentFile, "${imported.file.nameWithoutExtension}.json").delete()
        Preferences(context).customVoice = true
        assertEquals(imported, library.choose(RoastKind.OVERDUE))
        assertEquals("Recording: old-file.m4a", library.reaction(RoastKind.OVERDUE).caption)
        library.updateCaption(imported, RoastKind.OVERDUE, TeasingStyle.SWEET, "Babe, the deadline noticed.")
        Preferences(context).teasingStyle = TeasingStyle.SWEET
        val reaction = VoiceLibrary(context).reaction(RoastKind.OVERDUE)
        assertEquals("Babe, the deadline noticed.", reaction.caption)
        assertEquals(imported.file, reaction.clip!!.file)
        assertEquals(listOf<Byte>(1, 2, 3, 4), imported.file.readBytes().toList())
        assertTrue(library.custom(RoastKind.OVERDUE, TeasingStyle.CLASSIC).isEmpty())
        library.remove(reaction.clip)
        assertFalse(imported.file.exists())
        assertNull(library.choose(RoastKind.OVERDUE))
    }
    @Test fun voiceSelectionsAreIndependentAndDisabledCustomClipsExplainTheSwitch() {
        val prefs = Preferences(context)
        prefs.selectActor(TeasingStyle.CLASSIC, "sean")
        prefs.selectActor(TeasingStyle.SWEET, "bite_sweet")
        val reopened = Preferences(context)
        assertEquals("sean", reopened.actorFor(TeasingStyle.CLASSIC))
        assertEquals("bite_sweet", reopened.actorFor(TeasingStyle.SWEET))
        prefs.teasingStyle = TeasingStyle.SWEET
        val library = VoiceLibrary(context)
        library.importFile(RoastKind.CREATED, recording(), "My Sweet take")
        val reaction = library.reaction(RoastKind.CREATED)
        assertNull(reaction.clip)
        assertTrue(reaction.explanation!!.contains("turn on Use my recordings"))
    }
    @Test fun categoryLimitRejectsSixthClipWithoutLosingTheFirstFive() {
        val library = VoiceLibrary(context)
        val source = recording()
        repeat(5) { library.importFile(RoastKind.TINY, source, "Take $it") }
        assertThrows(IllegalArgumentException::class.java) { library.importFile(RoastKind.TINY, source, "Sixth") }
        assertEquals(5, VoiceLibrary(context).custom(RoastKind.TINY).size)
    }
}

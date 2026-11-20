package com.dracula.taskbite

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VoiceRotationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun clips(count: Int) = (1..count).map { VoiceClip("Line $it", asset = "voice-$it.wav", caption = "Line $it") }

    @Test fun freshSelectorsContinueTheCycleAndDoNotRepeatAtItsBoundary() {
        val pool = clips(5)
        val chosen = (1..20).map { VoiceRotation(context).choose("CLASSIC.sean.PLAN_DAY", TeasingStyle.CLASSIC, pool)!! }
        chosen.chunked(5).forEach { assertEquals(pool.toSet(), it.toSet()) }
        chosen.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertFalse(context.getSharedPreferences("voice_rotation", 0).all.isEmpty())
    }
    @Test fun clearingBetweenNudgesDoesNotResetOrConsumeTheNudgeCycle() {
        val pool = clips(5)
        val plans = mutableListOf<VoiceClip>()
        val dismissals = mutableListOf<VoiceClip>()
        repeat(15) {
            plans += VoiceRotation(context).choose("CLASSIC.sean.PLAN_DAY", TeasingStyle.CLASSIC, pool)!!
            dismissals += VoiceRotation(context).choose("CLASSIC.sean.ABANDON", TeasingStyle.CLASSIC, pool)!!
        }
        for (selected in listOf(plans, dismissals)) {
            selected.chunked(5).forEach { assertEquals(5, it.toSet().size) }
            selected.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }
    @Test fun overlappingSituationsAvoidTheJustUsedClipWhenTheyHaveChoices() {
        val pool = clips(5)
        val first = VoiceRotation(context).choose("plan", TeasingStyle.CLASSIC, pool)
        val second = VoiceRotation(context).choose("dismiss", TeasingStyle.CLASSIC, pool)
        val third = VoiceRotation(context).choose("idle", TeasingStyle.CLASSIC, pool)
        assertEquals(3, setOf(first, second, third).size)
    }
    @Test fun changedPoolsDiscardRemovedClipsAndCountDuplicateAssetsOnce() {
        val pool = clips(3)
        val before = VoiceRotation(context).choose("plan", TeasingStyle.CLASSIC, pool)!!
        val newClip = VoiceClip("New recording", asset = "new.wav")
        val changed = pool.filter { it != before } + newClip
        val picked = (1..3).map { VoiceRotation(context).choose("plan", TeasingStyle.CLASSIC, changed + changed)!! }
        assertEquals(changed.toSet(), picked.toSet())
        assertFalse(before in picked)
    }
    @Test fun emptyAndSingleRecordingPoolsDoNotInventDifferentVoices() {
        val rotation = VoiceRotation(context)
        assertNull(rotation.choose("empty", TeasingStyle.SWEET, emptyList()))
        val own = VoiceClip("Your recording", asset = "only.wav")
        repeat(5) { assertEquals(own, VoiceRotation(context).choose("own", TeasingStyle.SWEET, listOf(own))) }
    }
    @Test fun catalogOffersMultipleRealClipsWithoutChangingCaptionOrSelectedActor() {
        val p = Preferences(context)
        for (actor in listOf("sean", "alex", "meghan")) {
            p.voiceActor = actor
            for (kind in listOf(RoastKind.PLAN_DAY, RoastKind.IDLE, RoastKind.ABANDON)) {
                assertEquals(5, VoiceLibrary(context).available(kind).size)
                val reactions = (1..5).map { VoiceLibrary(context).reaction(kind) }
                assertEquals(5, reactions.map { it.clip!!.asset }.toSet().size)
                reactions.forEach {
                    assertEquals(it.caption, it.clip!!.caption)
                    assertFalse(it.caption.contains("welcome", ignoreCase = true))
                    assertFalse(it.caption.contains("hello", ignoreCase = true))
                    assertFalse(it.caption.contains("good luck", ignoreCase = true))
                }
                assertEquals(actor, p.voiceActor)
            }
            val clears = (1..6).map { VoiceLibrary(context).reaction(RoastKind.NOTIFICATION_CLEARED) }
            assertEquals(2, clears.map { it.clip!!.asset }.toSet().size)
            clears.zipWithNext().forEach { (a, b) -> assertNotEquals(a.clip, b.clip) }
            assertTrue(clears.all { it.caption in listOf("[Sigh] No way.", "Not happening. Let’s go!") })
        }
        if (VoiceLibrary(context).actors().any { it.id == "high_on_life" }) {
            p.voiceActor = "high_on_life"
            RoastKind.entries.forEach { kind ->
                val first = VoiceLibrary(context).reaction(kind)
                val second = VoiceLibrary(context).reaction(kind)
                assertNotEquals(first.clip!!.asset, second.clip!!.asset)
                assertEquals(first.caption, first.clip.caption)
                assertTrue(first.clip.asset!!.contains("high_on_life"))
            }
            assertEquals("high_on_life", p.voiceActor)
        }
    }
}

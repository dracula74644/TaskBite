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
    @Test fun shortPacksRotateOnlyAppropriateActionsWithoutChangingActor() {
        val p = Preferences(context)
        for (actor in VoiceLibrary(context).actors().filter { it.id in VoiceLibrary.SHORT_REACTION_ACTORS }) {
            p.voiceActor = actor.id
            for (kind in VoiceLibrary.SHORT_REACTION_KINDS) {
                val pool = VoiceLibrary(context).available(kind)
                val reactions = (1..pool.size).map { VoiceLibrary(context).reaction(kind) }
                assertEquals(pool.size, reactions.map { it.clip!!.asset }.toSet().size)
                reactions.forEach { assertEquals(it.caption, it.clip!!.caption) }
            }
            for (kind in RoastKind.entries - VoiceLibrary.SHORT_REACTION_KINDS) {
                assertTrue(VoiceLibrary(context).available(kind).isEmpty())
                assertNull(VoiceLibrary(context).reaction(kind).clip)
            }
            assertEquals(actor.id, p.voiceActor)
        }
    }
}

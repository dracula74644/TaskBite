package com.dracula.taskbite

import android.Manifest
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class ScenarioVoiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    @Before fun setup() {
        app.preferences.apply { onboarded = true; quietEnabled = false; notificationReactions = true; lastTaskActivity = 0 }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun bothPreloadedPacksCoverEverySituationWithAudibleDistinctChoicesAndExactCaptions() {
        val library = VoiceLibrary(app)
        for ((actor, count) in listOf("meme_sidekick" to 21, "meme_unfiltered" to 26)) {
            app.preferences.voiceActor = actor
            val all = mutableSetOf<String>()
            for (kind in RoastKind.entries) {
                val clips = library.bundled(kind)
                assertTrue("$actor $kind", clips.size in 2..5)
                assertEquals(clips.size, clips.map { it.asset }.toSet().size)
                for (clip in clips) {
                    all.add(requireNotNull(clip.asset))
                    val pcm = clip.pcm(app)
                    assertEquals(44100, pcm.sampleRate)
                    assertEquals(1, pcm.channels)
                    assertTrue(pcm.durationMillis in 500..5000)
                    assertTrue(pcm.samples.any { it != 0.toByte() })
                    assertTrue(clip.caption!!.split(Regex("\\s+")).size < 12)
                    if (actor == "meme_sidekick") assertFalse(Regex("(?i)\\b(shit|fuck|fucking|damn)\\b").containsMatchIn(clip.caption))
                }
                val chosen = (1..clips.size).map { VoiceLibrary(app).reaction(kind) }
                assertEquals(clips.toSet(), chosen.map { it.clip }.toSet())
                chosen.forEach { assertEquals(it.caption, it.clip!!.caption); assertEquals(it, VoiceReaction.restore(it.saved())) }
            }
            assertEquals(count, all.size)
        }
    }
    @Test fun noTasksStayAnEmptyDayAfterAnIgnoredOrOpenedReminder() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        app.preferences.awayGapMinutes = 15
        val n = requireNotNull(Reminders.notifyPlanning(app, "Empty list", now = now))
        val nudge = AwayNudge(now, AwayScenario.EMPTY_DAY)
        assertEquals(RoastKind.PLAN_DAY, NotificationReactions.followUp(app, nudge, now + 15 * 60_000)!!.second)
        val opened = requireNotNull(NotificationReactions.opened(app, n.token, now + 16 * 60_000))
        assertEquals(RoastKind.NOTIFICATION_OPENED, opened.kind)
        assertEquals(RoastKind.PLAN_DAY, NotificationReactions.followUp(app, nudge, now + 31 * 60_000)!!.second)
        assertTrue(app.repository.dao.allTasks().isEmpty())
        assertTrue(app.repository.dao.allEvents().isEmpty())
    }
    @Test fun followUpsKeepSmallListLowPriorityStartedAndOverdueSituations() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val task = Task(title = "Unfinished", dueAt = now + 3_600_000, updatedAt = now - 1000)
        app.repository.dao.put(task)
        val notice = requireNotNull(Reminders.notify(app, task, "Waiting", now = now))
        NotificationReactions.opened(app, notice.token, now + 1)
        for (scenario in listOf(AwayScenario.SMALL_LIST, AwayScenario.LOW_PRIORITY, AwayScenario.STILL_WORKING, AwayScenario.OVERDUE_TASK)) {
            val result = NotificationReactions.followUp(app, AwayNudge(now, scenario, task), now + 31 * 60_000)
            assertEquals(scenario.kind, result!!.second)
        }
    }
    @Test fun simulatorPresetsUseTheirDistinctRecordedSituations() {
        val date = LocalDate.of(2026, 11, 20)
        for ((preset, kind) in listOf("empty" to RoastKind.PLAN_DAY, "small" to RoastKind.SMALL_LIST,
            "low" to RoastKind.LOW_PRIORITY, "started" to RoastKind.STILL_WORKING)) {
            val simulation = DaySimulation.replay(date, ZoneId.of("UTC"), listOf("preset:$preset"))
            simulation.advance(if (preset == "started") 45 * 60_000 else 30 * 60_000)
            assertEquals(preset, kind, simulation.event.kind)
        }
    }
    @Test fun savedPackSurvivesReloadAndExistingLibrarySeesNewPacksWithoutRestart() {
        val library = VoiceLibrary(app)
        library.actors() // Initialize the cache before installing another pack.
        installTestVoicePack(app)
        assertTrue(library.actors().any { it.id == "bite_original" })
        assertNotNull(library.reaction(RoastKind.PLAN_DAY).clip!!.file)
        PersonalVoicePacks.keepBundled(app)
        val saved = PersonalVoicePacks.records(app).single { it.getString("id") == "meme_sidekick" }
        val asset = saved.getJSONObject("categories").getJSONArray("PLAN_DAY").getJSONObject(0).getString("asset")
        assertNotNull(PersonalVoicePacks.file(app, asset))
    }
    @Test fun cleanPackIsSelectedOnlyOnceAndSweetAndCustomPreferencesStayIntact() {
        val prefs = app.getSharedPreferences("taskbite", 0)
        val p = app.preferences
        prefs.edit().remove("memePackV1").apply()
        p.voiceActor = "sean"; p.teasingStyle = TeasingStyle.CLASSIC; p.customVoice = true
        PersonalVoicePacks.activateMemesOnce(app)
        assertEquals("meme_sidekick", p.voiceActor); assertTrue(p.customVoice)
        p.voiceActor = "meme_unfiltered"
        PersonalVoicePacks.activateMemesOnce(app)
        assertEquals("meme_unfiltered", p.voiceActor)
        prefs.edit().remove("memePackV1").apply()
        p.teasingStyle = TeasingStyle.SWEET; p.voiceActor = "sean"
        PersonalVoicePacks.activateMemesOnce(app)
        assertEquals(TeasingStyle.SWEET, p.teasingStyle); assertEquals("sean", p.voiceActor)
    }
}

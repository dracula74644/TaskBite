package com.dracula.taskbite

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class, shadows = [InstantRecordedPlayer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoicePreviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualPreviewButtonsPlayMediaInVibrateAndExplainMediaMutePrecisely() {
        val activity = compose.activity
        val audio = activity.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val notifications = activity.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        var plays = 0
        ShadowAudioTrack.addAudioDataListener { _, _, _ -> plays++ }
        assertEquals(AudioManager.STREAM_MUSIC, activity.volumeControlStream)
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(" Test voice"))
        compose.onNodeWithText(" Test voice").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { plays == 1 }
        compose.onNodeWithText("Voice settings").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText("Alex Brodie"))
        compose.onAllNodesWithText("Hear voice").onFirst().performScrollTo().performClick()
        compose.waitUntil(5000) { plays == 2 }
        if (VoiceLibrary(activity).actors().any { it.id == "high_on_life" }) {
            compose.onNodeWithTag("voice_library").performScrollToNode(hasContentDescription("High on Life voice"))
            compose.onNodeWithContentDescription("High on Life voice").performClick()
            assertEquals("high_on_life", Preferences(activity).voiceActor)
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText("Alex Brodie"))
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText("Alex Brodie"))
        compose.onAllNodesWithText("Hear voice").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText(VoiceResult.MEDIA_MUTED.message!!))
        compose.onNodeWithText(VoiceResult.MEDIA_MUTED.message!!).assertExists()
        assertEquals(2, plays) // The mute reason is shown without pretending this is a decoding failure.
    }
}

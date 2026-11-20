package com.dracula.taskbite

import android.app.Application
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [InstantRecordedPlayer::class, RecordingPcmTrack::class])
class VoiceVolumeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private var lastPlayer: ShadowMediaPlayer? = null
    private var lastPcm: AudioTrack? = null
    @Before fun setup() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ShadowAudioTrack.addAudioDataListener { shadow, data, format ->
            assertTrue(data.isNotEmpty())
            assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, format.encoding)
            lastPcm = ReflectionHelpers.getField(shadow, "audioTrack")
            assertEquals(AudioAttributes.USAGE_MEDIA, lastPcm!!.audioAttributes.usage)
        }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, 0) }
        ShadowMediaPlayer.setCreateListener { _, shadow -> lastPlayer = shadow }
    }
    @Test fun liveAndBackgroundPlayersUseSavedVolumeAndReleaseAfterPlayback() = runBlocking {
        val prefs = Preferences(context)
        val systemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        suspend fun check(voice: Voice, expected: Float) {
            assertEquals(VoiceResult.PLAYED, voice.play(RoastKind.COMPLETED))
            assertNull("Bundled clips must bypass MediaPlayer decoding", lastPlayer)
            val engine = requireNotNull(lastPcm)
            val shadow = Shadow.extract<RecordingPcmTrack>(engine)
            assertEquals(expected, shadow.gain, 0.001f)
            assertEquals(AudioTrack.STATE_UNINITIALIZED, engine.state)
            assertEquals(systemVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_SYSTEM))
        }
        val inApp = Voice(context)
        check(inApp, 1f)
        prefs.alertVolume = 35
        check(inApp, .35f)
        check(Voice(context), .35f)
    }
    @Test fun muteSilentModeAndDndDoNotCreateAPlayer() = runBlocking {
        val voice = Voice(context)
        Preferences(context).alertVolume = 0
        assertEquals(VoiceResult.APP_MUTED, voice.play(RoastKind.OVERDUE))
        Preferences(context).alertVolume = 100
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(VoiceResult.SILENT_MODE, voice.play(RoastKind.OVERDUE))
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        context.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertEquals(VoiceResult.DND_BLOCKED, voice.play(RoastKind.OVERDUE))
        assertNull(lastPlayer)
        assertNull(lastPcm)
    }
    @Test fun previewWorksInVibrateWithNotificationAndSystemVolumesMuted() = runBlocking {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val voice = Voice(context)
        assertEquals(VoiceResult.PLAYED, voice.play(RoastKind.COMPLETED, preview = true))
        assertEquals(VoiceResult.SILENT_MODE, voice.play(RoastKind.COMPLETED))
    }
    @Test fun priorityDndDoesNotPreemptivelyRejectExplicitMediaPreview() = runBlocking {
        context.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val voice = Voice(context)
        assertEquals(VoiceResult.PLAYED, voice.play(RoastKind.COMPLETED, preview = true))
        assertEquals(VoiceResult.DND_BLOCKED, voice.play(RoastKind.COMPLETED))
    }
    @Test fun zeroMediaVolumeAndDeniedFocusHaveSpecificErrors() = runBlocking {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val voice = Voice(context)
        assertEquals(VoiceResult.MEDIA_MUTED, voice.play(RoastKind.COMPLETED, preview = true))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals(VoiceResult.FOCUS_DENIED, voice.play(RoastKind.COMPLETED, preview = true))
        assertNull(lastPlayer)
        assertNull(lastPcm)
    }
    @Test fun pcmOutputFailureIsNotMisreportedAsADecoderError() = runBlocking {
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        assertEquals(VoiceResult.ENGINE_ERROR, Voice(context).play(RoastKind.STARTED))
        assertNull(lastPlayer)
        assertEquals(AudioTrack.STATE_UNINITIALIZED, requireNotNull(lastPcm).state)
    }
    @Test fun playbackFailureReleasesResources() = runBlocking {
        ShadowMediaPlayer.setMediaInfoProvider { throw IllegalArgumentException("Bad audio") }
        val file = java.io.File(context.cacheDir, "test.mp3").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(VoiceResult.ENGINE_ERROR, Voice(context).play(RoastKind.OVERDUE, VoiceClip("Imported", file = file)))
        assertEquals(ShadowMediaPlayer.State.END, requireNotNull(lastPlayer).state)
    }
    @Test fun inAppOptOutAndQuietHoursExplainSuppressionWithoutPlaying() = runBlocking {
        val prefs = Preferences(context)
        val voice = Voice(context)
        prefs.actionVoice = false
        assertEquals(VoiceResult.VOICE_DISABLED, voice.react(RoastKind.SNOOZE))
        prefs.actionVoice = true
        prefs.quietEnabled = true
        prefs.quietStart = 8
        prefs.quietEnd = 8
        assertEquals(VoiceResult.QUIET_HOURS, voice.react(RoastKind.SNOOZE))
        assertNull(lastPcm)
        assertEquals(VoiceResult.PLAYED, voice.play(RoastKind.STARTED, preview = true))
    }
    @Test fun everySweetSituationPlaysItsOwnFileAndCaptionAndMissingClipsStaySilent() = runBlocking {
        val prefs = Preferences(context).apply { teasingStyle = TeasingStyle.SWEET; customVoice = true }
        val library = VoiceLibrary(context)
        val voice = Voice(context)
        for (kind in RoastKind.entries) {
            val missing = library.reaction(kind)
            assertNull(missing.clip)
            assertEquals(VoiceResult.NO_RECORDING, voice.play(missing, preview = true))
            val source = java.io.File.createTempFile("sweet-test", ".m4a", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(source.path, android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")
            org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(source.path, android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
            val words = Roasts.options(kind, TeasingStyle.SWEET).first()
            val clip = library.importFile(kind, source, "Take for $kind", caption = words)
            val selected = library.reaction(kind)
            assertEquals(words, selected.caption)
            assertEquals(clip, selected.clip)
            assertEquals(VoiceResult.PLAYED, voice.play(VoiceReaction.restore(selected.saved())!!, preview = true))
            assertEquals(org.robolectric.shadows.util.DataSource.toDataSource(clip.file!!.path), lastPlayer!!.dataSource)
            library.remove(clip)
            assertEquals(VoiceResult.FILE_UNAVAILABLE, voice.play(selected, preview = true))
            assertNull(library.reaction(kind).clip)
        }
        assertNull(lastPcm) // No fallback to the unrelated bundled Classic actor.
    }
}

/** Simulate finished playback while retaining the platform calls, data source, and gain. */
@Implements(MediaPlayer::class)
class InstantRecordedPlayer : ShadowMediaPlayer() {
    @Implementation override fun prepareAsync() {
        invokePreparedListener()
        invokeCompletionListener()
    }
}

@Implements(AudioTrack::class)
class RecordingPcmTrack : ShadowAudioTrack() {
    var gain: Float = -1f
    @Implementation fun setVolume(value: Float): Int { gain = value; return AudioTrack.SUCCESS }
}

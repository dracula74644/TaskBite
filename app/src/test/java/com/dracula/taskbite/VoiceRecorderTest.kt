package com.dracula.taskbite

import android.app.Application
import android.media.MediaRecorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaRecorder
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VoiceRecorderTest {
    @Test fun completedTakeCanBeReviewedButCloseRemovesUnsavedAudio() {
        val recorder = VoiceRecorder(RuntimeEnvironment.getApplication())
        recorder.start()
        assertTrue(recorder.error, recorder.recording)
        val engine = ReflectionHelpers.getField<MediaRecorder>(recorder, "recorder")
        val shadow = shadowOf(engine)
        assertEquals(MediaRecorder.AudioSource.MIC, shadow.audioSource)
        assertEquals(30_000, shadow.maxDuration)
        File(shadow.outputPath).writeBytes(byteArrayOf(1, 2, 3))
        recorder.finish()
        assertFalse(recorder.recording)
        val draft = requireNotNull(recorder.draft)
        assertTrue(draft.exists())
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, shadow.state)
        recorder.close()
        assertFalse(draft.exists())
        assertNull(recorder.draft)
    }
    @Test fun backgroundCancellationReleasesMicrophoneAndDiscardsUnfinishedTake() {
        val recorder = VoiceRecorder(RuntimeEnvironment.getApplication())
        recorder.start()
        assertTrue(recorder.error, recorder.recording)
        val shadow = shadowOf(ReflectionHelpers.getField<MediaRecorder>(recorder, "recorder"))
        val output = File(shadow.outputPath)
        recorder.cancelRecording()
        assertFalse(recorder.recording)
        assertNull(recorder.draft)
        assertFalse(output.exists())
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, shadow.state)
    }
}

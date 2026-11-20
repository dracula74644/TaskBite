package com.dracula.taskbite

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.*
import java.io.File

/** Microphone capture exists only while the voice editor is visible. */
class VoiceRecorder(private val context: Context) {
    var recording by mutableStateOf(false)
        private set
    var draft by mutableStateOf<File?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    @Suppress("DEPRECATION")
    fun start() {
        close()
        error = null
        try {
            output = File.createTempFile("bite-recording-", ".m4a", context.cacheDir)
            val engine = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = engine
            engine.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setMaxDuration(30_000)
                setOutputFile(requireNotNull(output).absolutePath)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish() }
                setOnErrorListener { _, _, _ -> cancelRecording(); error = "Recording stopped. Please try again." }
                prepare()
                start()
            }
            recording = true
        } catch (_: Exception) {
            cancelRecording()
            error = "Couldn't use the microphone. Check permission and try again."
        }
    }
    fun finish() {
        if (!recording) return
        recording = false
        try {
            recorder?.stop()
            draft = output
            output = null
        } catch (_: RuntimeException) {
            output?.delete()
            output = null
            error = "That recording was too short. Try a longer take."
        } finally { recorder?.release(); recorder = null }
    }
    fun cancelRecording() {
        recording = false
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        output?.delete()
        output = null
    }
    fun discardDraft() { draft?.delete(); draft = null }
    fun close() { cancelRecording(); discardDraft() }
}

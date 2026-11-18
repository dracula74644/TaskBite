package com.dracula.taskbite

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/** Each speaker owns and releases its TTS engine and audio focus. Never overrides DND/volume. */
class Voice(private val context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var current: CompletableDeferred<Boolean>? = null
    private var generation = 0
    private var utterance: String? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }.build()
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    private fun audible(): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0
    }
    suspend fun speak(text: String): Boolean {
        val requested = generation
        if (withTimeoutOrNull(5000) { ready.await() } != true || requested != generation || !audible()) return false
        // Releasing the previous request is important when two actions occur quickly.
        stop()
        if (tts.setLanguage(Locale.ENGLISH) < TextToSpeech.LANG_AVAILABLE) return false
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(1.03f)
        tts.setPitch(0.92f)
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        current = done
        utterance = id
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("TTS callback")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id) done.complete(false) }
        })
        return try {
            if (tts.speak(text.take(500), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) false
            else withTimeoutOrNull(20_000) { done.await() } ?: false
        } finally {
            if (utterance == id) stop()
        }
    }
    fun stop() {
        generation++
        tts.stop()
        current?.complete(false)
        current = null
        utterance = null
        audio.abandonAudioFocusRequest(focus)
    }
    fun close() { stop(); tts.shutdown() }
}

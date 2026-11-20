package com.dracula.taskbite

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.IOException

/** A stopped preview is not an error; other failures have a specific, actionable explanation. */
enum class VoiceResult(val message: String? = null) {
    PLAYED, STOPPED,
    PAUSED("Teasing is paused. Explicit voice previews still work."),
    NO_RECORDING("No recording for this style and situation. Add one in Voice settings."),
    VOICE_DISABLED("Voice reactions are off. Enable In-app voice in Sidekick."),
    QUIET_HOURS("Quiet hours are on. You can still preview this reaction."),
    APP_MUTED("Spoken alerts are muted. Raise the volume slider to test the voice."),
    MEDIA_MUTED("Your phone's media volume is zero. Press Volume Up, then try again."),
    SILENT_MODE("Automatic voice is paused in silent or vibrate mode. Test voice still works with media volume."),
    DND_BLOCKED("Do Not Disturb is blocking this audio. Allow media in DND settings or turn DND off."),
    FOCUS_DENIED("Android couldn't give Bite audio focus. Finish any call, then check background access in Sidekick's delivery details."),
    FILE_UNAVAILABLE("Couldn't open this audio clip. Try another voice or import the recording again."),
    DECODER_ERROR("Android couldn't decode this recording. Try another clip or import an MP3 or M4A file."),
    ENGINE_ERROR("Android couldn't start audio playback. Close and reopen TaskBite, then try again."),
    TIMED_OUT("Audio playback timed out. Try the clip again."),
}

/** All clips use media volume; automatic reactions additionally respect ringer/DND settings. */
class Voice(private val context: Context) {
    private val preferences = Preferences(context.applicationContext)
    private val library = VoiceLibrary(context.applicationContext)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var player: MediaPlayer? = null
    private var pcmTrack: AudioTrack? = null
    private var generation = 0
    private var current: CompletableDeferred<VoiceResult>? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }.build()

    private fun blocked(preview: Boolean): VoiceResult? {
        if (!preview && preferences.teasingPaused()) return VoiceResult.PAUSED
        if (preferences.alertVolume == 0) return VoiceResult.APP_MUTED
        val filter = notifications.currentInterruptionFilter
        // Explicit previews are media playback. Do not reject them just because the ringer is muted
        // or priority DND is active: Android applies the user's media policy to USAGE_MEDIA.
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) return VoiceResult.DND_BLOCKED
        if (!preview) {
            if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS)
                return VoiceResult.DND_BLOCKED
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return VoiceResult.SILENT_MODE
        }
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return VoiceResult.MEDIA_MUTED
        return null
    }

    fun reactionBlockReason(): VoiceResult? {
        if (preferences.teasingPaused()) return VoiceResult.PAUSED
        if (!preferences.actionVoice) return VoiceResult.VOICE_DISABLED
        if (preferences.isQuiet()) return VoiceResult.QUIET_HOURS
        return blocked(preview = false)
    }

    suspend fun react(kind: RoastKind): VoiceResult {
        return react(library.reaction(kind))
    }

    suspend fun react(reaction: VoiceReaction): VoiceResult {
        reactionBlockReason()?.let { return it }
        if (reaction.clip == null) { stop(); return VoiceResult.NO_RECORDING }
        return play(reaction)
    }

    suspend fun play(reaction: VoiceReaction, preview: Boolean = false): VoiceResult {
        val clip = reaction.clip ?: run { stop(); return VoiceResult.NO_RECORDING }
        return play(reaction.kind, clip, preview)
    }

    suspend fun play(kind: RoastKind, clip: VoiceClip? = null, preview: Boolean = false): VoiceResult {
        stop()
        val requested = generation
        blocked(preview)?.let { return it }
        val recording: VoiceClip
        val pcm: PcmAudio?
        try {
            recording = clip ?: library.choose(kind) ?: return VoiceResult.NO_RECORDING
            if (recording.file != null && !recording.file.isFile) return VoiceResult.FILE_UNAVAILABLE
            pcm = if (recording.asset?.endsWith(".wav") == true || recording.file?.extension == "wav") withContext(Dispatchers.IO) {
                (recording.file?.inputStream() ?: context.assets.open(requireNotNull(recording.asset))).use(PcmAudio::read)
            } else null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.w("BiteVoice", "Could not load recording", e); return VoiceResult.FILE_UNAVAILABLE }
        if (generation != requested) return VoiceResult.STOPPED
        val granted = try { audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
            catch (_: RuntimeException) { false }
        if (!granted) return VoiceResult.FOCUS_DENIED
        return if (pcm != null) playPcm(pcm) else playEncoded(recording)
    }

    private suspend fun playPcm(pcm: PcmAudio): VoiceResult {
        val track = try {
            AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.samples.size).build()
        } catch (e: RuntimeException) {
            audio.abandonAudioFocusRequest(focus)
            Log.w("BiteVoice", "PCM output unavailable", e)
            return VoiceResult.ENGINE_ERROR
        }
        pcmTrack = track
        val done = CompletableDeferred<VoiceResult>()
        current = done
        return try {
            if (track.state == AudioTrack.STATE_UNINITIALIZED ||
                track.write(pcm.samples, 0, pcm.samples.size) != pcm.samples.size) VoiceResult.ENGINE_ERROR
            else {
                track.setVolume(preferences.alertVolume / 100f)
                track.play()
                withTimeoutOrNull(pcm.durationMillis + 5_000) {
                    while (!done.isCompleted) {
                        if (track.playbackHeadPosition >= pcm.frameCount) done.complete(VoiceResult.PLAYED)
                        else delay(20)
                    }
                    done.await()
                } ?: VoiceResult.TIMED_OUT
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: RuntimeException) { Log.w("BiteVoice", "PCM output failed", e); VoiceResult.ENGINE_ERROR }
        finally { if (pcmTrack === track) stop() }
    }

    private suspend fun playEncoded(recording: VoiceClip): VoiceResult {
        val engine = try { MediaPlayer() } catch (e: RuntimeException) {
            audio.abandonAudioFocusRequest(focus)
            Log.w("BiteVoice", "MediaPlayer could not start", e)
            return VoiceResult.ENGINE_ERROR
        }
        player = engine
        val done = CompletableDeferred<VoiceResult>()
        current = done
        return try {
            engine.setAudioAttributes(attributes)
            val volume = preferences.alertVolume / 100f
            engine.setVolume(volume, volume)
            engine.setOnPreparedListener {
                if (player === it) {
                    try { it.start() } catch (e: IllegalStateException) {
                        Log.w("BiteVoice", "Could not start prepared audio", e)
                        done.complete(VoiceResult.ENGINE_ERROR)
                    }
                }
            }
            engine.setOnCompletionListener { done.complete(VoiceResult.PLAYED) }
            engine.setOnErrorListener { _, what, extra ->
                Log.w("BiteVoice", "MediaPlayer error $what/$extra")
                done.complete(when {
                    what == MediaPlayer.MEDIA_ERROR_SERVER_DIED -> VoiceResult.ENGINE_ERROR
                    extra == MediaPlayer.MEDIA_ERROR_IO -> VoiceResult.FILE_UNAVAILABLE
                    extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT -> VoiceResult.TIMED_OUT
                    extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED -> VoiceResult.DECODER_ERROR
                    else -> VoiceResult.ENGINE_ERROR
                }); true
            }
            if (recording.asset != null) context.assets.openFd(recording.asset).use {
                engine.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            } else engine.setDataSource(requireNotNull(recording.file).absolutePath)
            engine.prepareAsync()
            withTimeoutOrNull(35_000) { done.await() } ?: VoiceResult.TIMED_OUT
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: IOException) { Log.w("BiteVoice", "Could not open audio", e); VoiceResult.FILE_UNAVAILABLE }
        catch (e: Exception) { Log.w("BiteVoice", "Could not prepare audio", e); VoiceResult.ENGINE_ERROR }
        finally { if (player === engine) stop() }
    }
    fun stop() {
        generation++
        current?.complete(VoiceResult.STOPPED)
        current = null
        pcmTrack?.let { track -> runCatching { track.stop() }; track.release() }
        pcmTrack = null
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focus)
    }
    fun close() = stop()
}

package com.dracula.taskbite

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmAudioTest {
    private fun wave(): ByteArray = ByteBuffer.allocate(44 + 882).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + 882); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(44100); putInt(88200); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(882)
        repeat(441) { putShort(it.toShort()) }
    }.array()
    @Test fun extractsExactPcmFramesAndDuration() {
        val parsed = PcmAudio.read(wave().inputStream())
        assertEquals(441, parsed.frameCount)
        assertEquals(10L, parsed.durationMillis)
        assertEquals(882, parsed.samples.size)
        assertEquals(440, ByteBuffer.wrap(parsed.samples).order(ByteOrder.LITTLE_ENDIAN).getShort(880).toInt())
    }
    @Test fun truncatedAndCompressedWavHeadersAreRejectedBeforePlayback() {
        val valid = wave()
        val compressed = valid.copyOf().apply { this[20] = 3 }
        val oddFrames = valid.copyOf().apply { this[40] = 113 }
        for (bad in listOf(valid.copyOf(50), compressed, oddFrames, ByteArray(50))) {
            assertThrows(IOException::class.java) { PcmAudio.read(bad.inputStream()) }
        }
    }
}

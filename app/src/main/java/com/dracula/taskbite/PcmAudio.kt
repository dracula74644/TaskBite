package com.dracula.taskbite

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bundled performances are PCM WAV so playback needs no device-specific compressed decoder. */
data class PcmAudio(val samples: ByteArray, val sampleRate: Int, val channels: Int) {
    val frameCount: Int get() = samples.size / (channels * 2)
    val durationMillis: Long get() = frameCount * 1000L / sampleRate

    companion object {
        const val MAX_FILE_BYTES = 6 * 1024 * 1024
        fun read(input: java.io.InputStream): PcmAudio {
            val bytes = input.readBytesLimited()
            fun invalid(): Nothing = throw IOException("Invalid PCM WAV recording")
            if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") invalid()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val declared = (buffer.getInt(4).toLong() and 0xffffffffL) + 8
            if (declared != bytes.size.toLong()) invalid()
            var offset = 12
            var channels = 0
            var rate = 0
            var data: ByteArray? = null
            while (offset + 8 <= bytes.size) {
                val type = String(bytes, offset, 4, Charsets.US_ASCII)
                val length = buffer.getInt(offset + 4).toLong() and 0xffffffffL
                val start = offset + 8
                if (length > bytes.size - start) invalid()
                if (type == "fmt ") {
                    if (length < 16 || buffer.getShort(start).toInt() != 1) invalid()
                    channels = buffer.getShort(start + 2).toInt()
                    rate = buffer.getInt(start + 4)
                    val blockSize = buffer.getShort(start + 12).toInt()
                    if (channels !in 1..2 || rate !in listOf(22050, 24000, 44100, 48000) ||
                        buffer.getShort(start + 14).toInt() != 16 || blockSize != channels * 2 ||
                        buffer.getInt(start + 8) != rate * blockSize) invalid()
                } else if (type == "data") data = bytes.copyOfRange(start, start + length.toInt())
                offset = start + length.toInt() + (length.toInt() and 1)
            }
            val samples = data ?: invalid()
            if (channels == 0 || rate == 0 || samples.isEmpty() || samples.size % (channels * 2) != 0) invalid()
            return PcmAudio(samples, rate, channels).also { if (it.durationMillis !in 1..30_000) invalid() }
        }
        private fun java.io.InputStream.readBytesLimited(): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_FILE_BYTES) throw IOException("PCM recording is too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

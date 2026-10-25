package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoftwareOpusDecoderTest {
    @Test
    fun decodesCarPlayOpusPacketToPcm16le() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        val decoder = SoftwareOpusDecoder(sampleRate = 48_000, channels = 1)
        assertTrue(decoder.available)

        val packet = encoder.encode(toneFrame()).single()
        val pcm = ByteArray(SoftwareOpusEncoder.FRAME_BYTES)
        val bytes = decoder.decode(packet, pcm)

        assertEquals(SoftwareOpusEncoder.FRAME_BYTES, bytes)
        assertTrue("decoded PCM must not be silent", pcm.any { it.toInt() != 0 })
        encoder.close()
        decoder.close()
        assertFalse(decoder.available)
    }

    @Test
    fun softwareDecodeStaysWellInsideTheTwentyMillisecondFrameBudget() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        val decoder = SoftwareOpusDecoder(sampleRate = 48_000, channels = 1)
        val packet = encoder.encode(toneFrame()).single()
        val pcm = ByteArray(SoftwareOpusEncoder.FRAME_BYTES)

        repeat(20) { decoder.decode(packet, pcm) } // JIT + decoder state warm-up
        val iterations = 200
        val started = System.nanoTime()
        repeat(iterations) { decoder.decode(packet, pcm) }
        val averageMs = (System.nanoTime() - started) / 1_000_000.0 / iterations
        println("software Opus decode average=${"%.3f".format(averageMs)}ms per 20ms frame")

        assertTrue("average decode ${averageMs}ms exceeds half the 20ms frame budget",
            averageMs < 10.0)
        encoder.close()
        decoder.close()
    }

    @Test
    fun decoderCreationFailureIsReportedWithoutThrowing() {
        val decoder = SoftwareOpusDecoder(48_000, 1, create = { _, _ -> error("no codec") })
        assertFalse(decoder.available)
        assertEquals(-1, decoder.decode(ByteArray(8), ByteArray(64)))
        assertTrue(decoder.failureDescription.orEmpty().contains("no codec"))
    }

    private fun toneFrame(): ByteArray {
        val bytes = ByteArray(SoftwareOpusEncoder.FRAME_BYTES)
        for (index in 0 until SoftwareOpusEncoder.FRAME_SAMPLES) {
            val sample = (8_000 * sin(2 * PI * 440 * index / 48_000.0)).toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        return bytes
    }
}

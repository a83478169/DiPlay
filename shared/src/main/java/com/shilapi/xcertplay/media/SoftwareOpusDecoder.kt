package com.shilapi.xcertplay.media

import org.concentus.OpusDecoder
import org.concentus.OpusException

/**
 * Opus 下行软解兜底。
 *
 * 部分车机的 Android 没有 `audio/opus` MediaCodec（KX11_A2 实测 createDecoderByType 返回
 * error=-2），CarPlay 通话下行会因此整段丢弃。Concentus 已是本模块现成依赖，这里只负责把
 * 单个 RTP Opus payload 解成 PCM16LE，交给现有通话 AudioTrack。
 *
 * 只由 AudioRenderer 的音频线程调用，无共享状态。
 */
internal class SoftwareOpusDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    create: (Int, Int) -> OpusDecoder = { rate, count -> OpusDecoder(rate, count) },
) {
    val available: Boolean
        get() = decoder != null && failure == null

    val failureDescription: String?
        get() = failure?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }

    private var failure: Throwable? = null
    private var decoder: OpusDecoder? = try {
        create(sampleRate.coerceOpusRate(), if (channels >= 2) 2 else 1)
    } catch (error: Throwable) {
        failure = error
        null
    }
    private val decodedChannels = if (channels >= 2) 2 else 1
    private val maxFrameSamples = MAX_FRAME_MILLIS * sampleRate.coerceOpusRate() / 1000
    private val shortPcm = ShortArray(maxFrameSamples * decodedChannels)
    private var packetFailures = 0

    /**
     * 解一个 Opus 包，成功时返回 PCM16LE 的有效字节数，失败返回 -1。
     * 单个损坏包复位解码器后继续（只丢这一包）；解码器自身不可恢复的错误才停掉软解。
     */
    fun decode(packet: ByteArray, target: ByteArray): Int {
        val decoder = decoder ?: return -1
        if (failure != null || packet.size < MIN_PACKET_BYTES) return -1
        return try {
            val samples = decoder.decode(
                packet, 0, packet.size, shortPcm, 0, maxFrameSamples, false,
            )
            if (samples <= 0) return -1
            val values = samples * decodedChannels
            val bytes = values * 2
            if (target.size < bytes) return -1
            for (index in 0 until values) {
                val sample = shortPcm[index].toInt()
                target[index * 2] = (sample and 0xff).toByte()
                target[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
            }
            bytes
        } catch (error: OpusException) {
            packetFailures++
            if (packetFailures <= MAX_REPORTED_PACKET_FAILURES) {
                onError("software Opus decode failed packet=$packetFailures", error)
            }
            runCatching { decoder.resetState() }
            -1
        } catch (error: Throwable) {
            // AssertionError 等表示解码器内部状态不可信；继续每包重试只会刷日志，本次通话停用软解。
            failure = error
            -1
        }
    }

    fun close() {
        decoder = null
    }

    /**
     * Opus 解码器只接受 8/12/16/24/48 kHz。CarPlay 电话流正常是 48k，异常协商值按 48k 解，
     * 宁可音调异常也不让整通电话静音。
     */
    private fun Int.coerceOpusRate(): Int = when (this) {
        8_000, 12_000, 16_000, 24_000, 48_000 -> this
        else -> 48_000
    }

    internal companion object {
        private const val MIN_PACKET_BYTES = 4
        private const val MAX_FRAME_MILLIS = 120
        private const val MAX_REPORTED_PACKET_FAILURES = 3
        private const val REPORT_INTERVAL_PACKETS = 500L

        /** 500 包约 10 秒；只做一次低噪声心跳，证明软解确实在持续出 PCM。 */
        fun shouldReport(packets: Long): Boolean = packets == 1L || packets % REPORT_INTERVAL_PACKETS == 0L
    }
}

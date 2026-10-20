package dev.oaktech.bv.util

import android.media.MediaCodecList

/**
 * AV1 解码能力探测。
 *
 * Android 标准的 AV1 mime 是 `video/av01`，但部分厂商平台（MStar / Realtek 电视）
 * 会把 AV1 解码器注册成私有 mime（`video/av1`、`video/av1-8k`）。
 * ExoPlayer 只按标准 mime 查找解码器，这类设备会出现「硬件信息里明明有 AV1 硬解，
 * 播放却黑屏只有声音」的问题——视频轨道匹配不到解码器，直接被判为不支持，且不报错。
 */
object Av1Support {

    /** Android 标准 AV1 mime */
    const val MIME_AV1 = "video/av01"

    /** 部分厂商平台注册 AV1 解码器用的私有 mime */
    const val MIME_AV1_VENDOR = "video/av1"

    private val hasStandardAv1: Boolean by lazy { hasDecoder(MIME_AV1) }
    private val hasVendorAv1: Boolean by lazy { hasDecoder(MIME_AV1_VENDOR) }

    /** 设备是否有标准 mime 的 AV1 解码器（ExoPlayer 可直接使用） */
    fun hasStandardAv1Decoder(): Boolean = hasStandardAv1

    /** 设备是否有私有 mime（video/av1）的 AV1 解码器 */
    fun hasVendorAv1Decoder(): Boolean = hasVendorAv1

    /**
     * 是否需要对 AV1 视频轨做 mime 重写：
     * 没有标准 `video/av01` 解码器、但有私有 `video/av1` 解码器时成立。
     */
    fun needsAv1MimeRewrite(): Boolean = !hasStandardAv1 && hasVendorAv1

    /** AV1 是否可解码（标准解码器，或私有解码器 + mime 重写） */
    fun isAv1Decodable(): Boolean = hasStandardAv1 || hasVendorAv1

    private fun hasDecoder(mimeType: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codecInfo ->
            !codecInfo.isEncoder && codecInfo.supportedTypes.any {
                it.equals(mimeType, ignoreCase = true)
            }
        }
    }.getOrDefault(false)
}

package dev.oaktech.bv.player

data class VideoPlayerOptions(
    val userAgent: String? = null,
    val referer: String? = null,
    val enableFfmpegAudioRenderer: Boolean = false,
    /**
     * 是否启用 AV1 mime 重写（video/av01 → video/av1）。
     * 用于 MStar / Realtek 等把 AV1 解码器注册成私有 mime 的平台，
     * 见 `dev.oaktech.bv.util.Av1Support.needsAv1MimeRewrite()`。
     */
    val enableAv1MimeRewrite: Boolean = false,
    /**
     * AV1 解码失败（mime 重写后仍渲染不出画面）时的回调，
     * 由调用方负责降级到其他编码。
     */
    val onAv1DecodeFailed: (() -> Unit)? = null
)

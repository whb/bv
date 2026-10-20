package dev.oaktech.bv.player.impl.exo

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput

/**
 * 部分厂商平台（MStar / Realtek 电视）把 AV1 解码器注册成私有 mime `video/av1`
 * （Android 标准为 `video/av01`），ExoPlayer 只认标准 mime，导致设备明明有 AV1
 * 硬解却匹配不上：视频轨道被判为不支持，播放只有声音、黑屏。
 *
 * 该工厂包装默认的 [DefaultExtractorsFactory]，在轨道输出时把视频轨 mime 从
 * `video/av01` 改写为 `video/av1`，让 ExoPlayer 按私有 mime 找到设备解码器。
 *
 * 仅在设备没有标准 `video/av01` 解码器、但有 `video/av1` 解码器时启用，
 * 见 `dev.oaktech.bv.util.Av1Support.needsAv1MimeRewrite()`。
 */
@OptIn(UnstableApi::class)
class Av1MimeRewriteExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory()
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { Av1MimeRewriteExtractor(it) }.toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders)
            .map { Av1MimeRewriteExtractor(it) }.toTypedArray()
}

@OptIn(UnstableApi::class)
private class Av1MimeRewriteExtractor(
    extractor: Extractor
) : ForwardingExtractor(extractor) {
    override fun init(output: ExtractorOutput) {
        super.init(Av1MimeRewriteExtractorOutput(output))
    }
}

@OptIn(UnstableApi::class)
private class Av1MimeRewriteExtractorOutput(
    output: ExtractorOutput
) : ForwardingExtractorOutput(output) {
    override fun track(id: Int, type: Int): TrackOutput =
        Av1MimeRewriteTrackOutput(super.track(id, type))
}

@OptIn(UnstableApi::class)
private class Av1MimeRewriteTrackOutput(
    trackOutput: TrackOutput
) : ForwardingTrackOutput(trackOutput) {
    override fun format(format: Format) {
        super.format(rewriteAv1Mime(format))
    }

    companion object {
        private const val MIME_AV1_STANDARD = "video/av01"
        private const val MIME_AV1_VENDOR = "video/av1"

        /**
         * 把标准 AV1 mime 改写为厂商私有 mime，同时清掉 codec string，
         * 避免厂商解码器上报的 profile/level 不完整导致能力校验失败。
         */
        fun rewriteAv1Mime(format: Format): Format {
            if (!format.sampleMimeType.equals(MIME_AV1_STANDARD, ignoreCase = true)) {
                return format
            }
            return format.buildUpon()
                .setSampleMimeType(MIME_AV1_VENDOR)
                .setCodecs(null)
                .build()
        }
    }
}

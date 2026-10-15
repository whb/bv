package dev.oaktech.biliapi.entity

import dev.oaktech.biliapi.entity.danmaku.DanmakuMask
import dev.oaktech.biliapi.entity.danmaku.DanmakuMaskType
import dev.oaktech.biliapi.entity.danmaku.DanmakuMobMaskFrame
import dev.oaktech.biliapi.entity.danmaku.DanmakuWebMaskFrame
import java.io.File
import kotlin.test.Test

class DanmakuMaskTest {
    @Test
    fun `parse web mask file`() {
        val maskFile = Any::class::class.java.getResource("/3540266_25_2.exp.webmask")!!
        val binary = File(maskFile.toURI()).readBytes()
        val mask = DanmakuMask.fromBinary(binary, DanmakuMaskType.WebMask)
        println(mask)
    }

    @Test
    fun `parse web mask file and output`() {
        val maskFile = Any::class::class.java.getResource("/3540266_25_2.exp.webmask")!!
        val binary = File(maskFile.toURI()).readBytes()
        val mask = DanmakuMask.fromBinary(binary, DanmakuMaskType.WebMask)
        val outputDir = File("/home/seele/Documents/output/webmask")
        outputDir.mkdirs()
        mask.segments.forEachIndexed { index, danmakuMaskSegment ->
            val dir = File(outputDir, "$index")
            dir.mkdir()
            danmakuMaskSegment.frames.forEach { danmakuMaskFrame ->
                File(dir, "${danmakuMaskFrame.range}.svg")
                    .writeText((danmakuMaskFrame as DanmakuWebMaskFrame).svg)
            }
        }
    }

    @Test
    fun `parse mob mask file`() {
        val maskFile = Any::class::class.java.getResource("/3540266_25_2.exp.mobmask")!!
        val binary = File(maskFile.toURI()).readBytes()
        val mask = DanmakuMask.fromBinary(binary, DanmakuMaskType.MobMask)
        println()
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `parse mob mask file and output`() {
        val maskFile = Any::class::class.java.getResource("/3540266_25_2.exp.mobmask")!!
        val binary = File(maskFile.toURI()).readBytes()
        val mask = DanmakuMask.fromBinary(binary, DanmakuMaskType.MobMask)
        val outputDir = File("/home/seele/Documents/output/mobmask")
        outputDir.mkdirs()
        mask.segments.forEachIndexed { index, danmakuMaskSegment ->
            val dir = File(outputDir, "$index")
            dir.mkdir()
            danmakuMaskSegment.frames.forEach { danmakuMaskFrame ->
                val content = (danmakuMaskFrame as DanmakuMobMaskFrame)
                    .image
                    .toHexString(HexFormat.Default)
                    .chunked(80)
                    .joinToString("\n")
                File(dir, "${danmakuMaskFrame.range}.txt").writeText(content)
            }
        }
    }
}
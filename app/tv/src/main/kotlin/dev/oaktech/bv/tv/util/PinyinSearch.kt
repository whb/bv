package dev.oaktech.bv.tv.util

import net.sourceforge.pinyin4j.PinyinHelper

/**
 * 中文拼音检索工具。
 *
 * 为每条数据预先构建一个「小写检索文本块」，其中包含：
 * - 原文（小写）
 * - 拼音全拼（去声调）
 * - 拼音首字母
 *
 * 这样「张三」可以用 `zhang`、`zhangsan`、`zs` 任一种方式命中，
 * 输入法仍可照常输入中文（原文匹配）。
 */
object PinyinSearch {

    /**
     * 把一段文本转成「首字母」与「全拼」两个串（均小写、去声调、无空格）。
     * 非中文字符原样保留，空白忽略。
     */
    private fun toPinyin(text: String): Pair<String, String> {
        val initials = StringBuilder(text.length)
        val full = StringBuilder(text.length * 2)
        for (ch in text) {
            if (ch.isWhitespace()) continue
            val pinyinArray = runCatching { PinyinHelper.toHanyuPinyinStringArray(ch) }
                .getOrNull()
            if (pinyinArray.isNullOrEmpty()) {
                // 英文、数字、符号等：直接参与匹配
                val lower = ch.lowercaseChar()
                initials.append(lower)
                full.append(lower)
            } else {
                // 多音字取第一个读音，去掉声调数字
                val pinyin = pinyinArray[0].trimEnd { it.isDigit() }.lowercase()
                if (pinyin.isNotEmpty()) {
                    initials.append(pinyin.first())
                    full.append(pinyin)
                }
            }
        }
        return initials.toString() to full.toString()
    }

    /**
     * 构建检索文本块，结果形如 `张三 zhangsan zs `（原文 + 全拼 + 首字母）。
     * 多个文本会依次拼接，仍可用一次 contains 完成匹配。
     */
    fun buildSearchBlob(vararg texts: String): String {
        val sb = StringBuilder()
        texts.forEach { text ->
            if (text.isBlank()) return@forEach
            val (initials, full) = toPinyin(text)
            sb.append(text.lowercase()).append(' ')
                .append(full).append(' ')
                .append(initials).append(' ')
        }
        return sb.toString()
    }

    /** 归一化用户输入：去掉空白并转小写，便于与检索块直接比较 */
    fun normalize(keyword: String): String =
        keyword.filterNot { it.isWhitespace() }.lowercase()
}

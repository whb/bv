package dev.oaktech.bv.tv.screens.main

import androidx.compose.ui.unit.dp

/**
 * 正文内容列（首页 / UGC / PGC）的固定宽度。
 *
 * 各正文页将内容列水平居中，左右留白 = (内容区宽度 - ContentColumnWidth) / 2。
 * 调整此值即可统一改变所有正文页的左右留白：值越大留白越小，反之越大。
 */
val ContentColumnWidth = 1080.dp

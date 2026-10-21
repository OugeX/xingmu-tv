package com.xingmu.tv.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * t28：列表是否正在滚动（由 LazyVerticalGrid/LazyRow 的 state.isScrollInProgress 提供）。
 *
 * 用 CompositionLocal 而不是逐层传参：PosterCard / LiteListRow / 各种卡片都能读到，
 * 只有真正读取它的 PosterImage 会因滚动状态变化而重组，列表项本身不会被整表刷新。
 */
val LocalListScrolling = compositionLocalOf { false }

/** 列表停稳多久后把封面从低清升级到原尺寸（防抖，避免一边滑一边换图）。 */
const val HI_RES_DELAY_MS = 250L

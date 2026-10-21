package com.tvbox.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.desktop.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .background(
                if (enabled) AppColors.accent else AppColors.surfaceHigh,
                RoundedCornerShape(7.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = AppText.bodyStrong.copy(
                color = if (enabled) AppColors.accentOn else AppColors.textMuted,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val color = if (danger) AppColors.danger else AppColors.textSecondary
    Box(
        modifier = modifier
            .border(1.dp, if (enabled) AppColors.divider else AppColors.divider.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = AppText.body.copy(color = if (enabled) color else AppColors.textMuted, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                if (selected) AppColors.accent.copy(alpha = 0.16f) else AppColors.surfaceHigh,
                RoundedCornerShape(20.dp),
            )
            .border(
                1.dp,
                if (selected) AppColors.accent.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = AppText.body.copy(
                color = if (selected) AppColors.accent else AppColors.textSecondary,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
fun Tag(text: String, color: Color = AppColors.textMuted, background: Color = color.copy(alpha = 0.12f)) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = AppText.caption.copy(color = color, fontSize = 10.sp), maxLines = 1)
    }
}

@Composable
fun SectionHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kicker, style = AppText.section)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = AppText.hero)
                if (subtitle != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(subtitle, style = AppText.caption, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(placeholder, style = AppText.caption) },
        textStyle = AppText.bodyStrong.copy(fontSize = 12.sp),
        colors = darkFieldColors(),
    )
}

@Composable
fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        placeholder = { Text(placeholder, style = AppText.caption) },
        textStyle = AppText.body.copy(fontSize = 12.sp),
        colors = darkFieldColors(),
    )
}

@Composable
private fun darkFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
    textColor = AppColors.text,
    backgroundColor = AppColors.surface,
    cursorColor = AppColors.accent,
    focusedBorderColor = AppColors.accent.copy(alpha = 0.8f),
    unfocusedBorderColor = AppColors.divider,
    placeholderColor = AppColors.textMuted,
)

@Composable
fun LoadingRow(text: String = "加载中…") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = AppColors.accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = AppText.caption)
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("◇", style = AppText.hero.copy(color = AppColors.textMuted, fontSize = 34.sp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = AppText.bodyStrong, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = AppText.caption, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Banner(text: String, color: Color = AppColors.danger, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = AppText.caption.copy(color = color))
    }
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        bitmap = null
        failed = false
        if (url.isBlank()) {
            failed = true
            return@LaunchedEffect
        }
        val loaded = RemoteImageCache.load(url)
        bitmap = loaded
        failed = loaded == null
    }
    Box(modifier.background(AppColors.surfaceHigh), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(if (failed) "无图" else "…", style = AppText.caption.copy(fontSize = 10.sp))
        }
    }
}

/** 海报内存缓存（LRU，160 张），只在可见卡片上触发加载。 */
object RemoteImageCache {
    private const val MAX_ENTRIES = 160
    private val http = Http()
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    suspend fun load(url: String): ImageBitmap? {
        mutex.withLock { cache[url] }?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            runCatching { http.getBytes(url, emptyMap(), 8_000, 6 * 1024 * 1024) }.getOrNull()
        } ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        } ?: return null
        mutex.withLock { cache[url] = bitmap }
        return bitmap
    }
}

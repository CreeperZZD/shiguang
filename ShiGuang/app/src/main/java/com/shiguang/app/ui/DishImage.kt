package com.shiguang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.shiguang.app.data.Api
import com.shiguang.app.data.AppSettings

private val PlaceholderColor = Color(0xFFF3E7DE)

/**
 * 菜品配图：只使用服务器真实照片（Wikimedia 等真实来源），无图时显示干净的占位底色 + 菜名首字。
 * 不使用 AI 生成图。
 * 省流模式：不加载图片，直接显示占位。
 */
@Composable
fun DishImage(name: String, coverUrl: String? = null, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (AppSettings.dataSaver || coverUrl.isNullOrBlank()) {
        Box(
            modifier = modifier.background(PlaceholderColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFB08D7A),
            )
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(Api.HOST + coverUrl)
            .crossfade(220)
            .build(),
        contentDescription = name,
        modifier = modifier.background(PlaceholderColor),
        placeholder = ColorPainter(PlaceholderColor),
        error = ColorPainter(PlaceholderColor),
        contentScale = contentScale,
    )
}

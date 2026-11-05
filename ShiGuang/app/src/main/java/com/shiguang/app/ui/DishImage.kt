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
import java.net.URLEncoder
import kotlin.math.abs

private val PlaceholderColor = Color(0xFFF3E7DE)

/** pollinations AI 生成图兜底（免费可商用，菜名做种子稳定出图） */
fun dishImageUrl(name: String, width: Int = 512, height: Int = 384): String {
    val prompt = URLEncoder.encode("$name 中国家常菜成品 美食摄影 暖色调 俯拍", "UTF-8")
    return "https://image.pollinations.ai/prompt/$prompt?width=$width&height=$height&nologo=true&seed=${abs(name.hashCode()) % 100000}"
}

/**
 * 菜品配图：优先服务器真实照片（Wikimedia 免费可商用），没有则 AI 生成图兜底。
 * 省流模式：不加载图片，显示占位底色 + 菜名首字。
 */
@Composable
fun DishImage(name: String, coverUrl: String? = null, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (AppSettings.dataSaver) {
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
    val model = if (!coverUrl.isNullOrBlank()) Api.HOST + coverUrl else dishImageUrl(name)
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(220)
            .build(),
        contentDescription = name,
        modifier = modifier.background(PlaceholderColor),
        placeholder = ColorPainter(PlaceholderColor),
        error = ColorPainter(PlaceholderColor),
        contentScale = contentScale,
    )
}

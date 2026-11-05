package com.shiguang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.VideoResult
import com.shiguang.app.data.toVideoResult

/** 菜谱视频教程列表：B站站内结果 + 一键跳抖音/B站搜索兜底 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(recipeId: Long, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<VideoResult?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(recipeId) {
        try {
            name = Api.get("/recipes/$recipeId").getJSONObject("item").optString("name")
        } catch (e: Exception) {
            err = e.message
        }
        try {
            result = Api.get("/recipes/$recipeId/videos").toVideoResult()
        } catch (e: Exception) {
            result = VideoResult(emptyList(), "none", false, "https://search.bilibili.com/all?keyword=${java.net.URLEncoder.encode(name.ifBlank { "家常菜" }, "UTF-8")}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (name.isBlank()) "视频教程" else "「${name}」视频教程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("在短视频平台搜「${name}」", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.FilledTonalButton(onClick = { LinkUtils.openDouyinSearch(ctx, name) }) {
                                Text("🎵 抖音搜教程")
                            }
                            androidx.compose.material3.FilledTonalButton(onClick = { LinkUtils.openBilibiliSearch(ctx, name) }) {
                                Text("📺 B站搜教程")
                            }
                        }
                    }
                }
            }
            val res = result
            if (res != null && res.items.isNotEmpty()) {
                item {
                    Text(
                        "B站精选（${res.items.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(res.items.size) { i ->
                    val v = res.items[i]
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { LinkUtils.openBilibiliVideo(ctx, v.bvid) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            if (v.cover.isNotBlank()) {
                                coil.compose.AsyncImage(
                                    model = v.cover,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(v.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${v.author} · ${v.duration} · ${formatViews(v.viewCount)}次播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (res != null) {
                item {
                    Text(
                        "暂无站内视频结果，点上方按钮去抖音 / B站搜索看教程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            err?.let {
                item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun formatViews(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
    else -> n.toString()
}

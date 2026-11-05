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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.Recipe
import com.shiguang.app.data.toRecipe

private fun medal(rank: Int): String = when (rank) {
    1 -> "🥇"
    2 -> "🥈"
    3 -> "🥉"
    else -> "$rank"
}

/** 菜谱热度排行榜：浏览+点赞+收藏加权（服务端排序） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            items = Api.get("/leaderboard").getJSONArray("items").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).toRecipe() }
            }
        } catch (e: Exception) {
            err = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔥 菜谱热度榜") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        when {
            err != null -> Column(Modifier.padding(pad)) { ErrorBox(err!!) }
            items.isEmpty() -> Column(Modifier.padding(pad)) { LoadingBox() }
            else -> LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "按 浏览 + 点赞 +收藏 综合排序 · 每日更新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(items.size, key = { items[it].id }) { i ->
                    val r = items[i]
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecipe(r.id) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                medal(i + 1),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    buildString {
                                        append(r.category)
                                        if (r.kcal > 0) append(" · ").append(r.kcal).append(" kcal")
                                        append(" · 👍").append(r.likeCount).append(" ⭐").append(r.favoriteCount).append(" 👀").append(r.views)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

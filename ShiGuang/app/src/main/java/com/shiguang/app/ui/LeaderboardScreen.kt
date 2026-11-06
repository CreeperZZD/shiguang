package com.shiguang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.Api
import com.shiguang.app.data.Recipe
import com.shiguang.app.data.toRecipe

private val SORT_OPTIONS = listOf(
    "hot" to "综合热度",
    "new" to "最新收录",
    "views" to "浏览最多",
    "likes" to "点赞最多",
    "favs" to "收藏最多",
    "ratio" to "好评率",
)

private val MEDAL_COLORS = listOf(
    Color(0xFFD4A017), // 金
    Color(0xFF9EA7B3), // 银
    Color(0xFFB07950), // 铜
)

/** 菜谱排行榜：多维度排序（综合/最新/浏览/点赞/收藏/好评率），前三名奖牌图标 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf("hot") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sort) {
        loading = true
        try {
            items = Api.get("/leaderboard?sort=$sort").getJSONArray("items").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).toRecipe() }
            }
            err = null
        } catch (e: Exception) {
            err = e.message
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("菜谱排行榜") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SORT_OPTIONS.forEach { (key, label) ->
                    FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(label) })
                }
            }
            when {
                err != null -> ErrorBox(err!!)
                loading || items.isEmpty() -> LoadingBox()
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            when (sort) {
                                "hot" -> "按 浏览 + 点赞 + 收藏 综合排序 · 每日更新"
                                "new" -> "按收录时间从新到旧"
                                "views" -> "按累计浏览量排序"
                                "likes" -> "按累计点赞数排序"
                                "favs" -> "按累计收藏数排序"
                                else -> "按 点赞 ÷ 浏览 的好评率排序（浏览 ≥ 5 计入）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(items.size, key = { items[it].id }) { i ->
                        val r = items[i]
                        val rank = i + 1
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRecipe(r.id) },
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (rank <= 3) {
                                    Icon(
                                        painterResource(R.drawable.ic_medal),
                                        contentDescription = "第$rank 名",
                                        tint = MEDAL_COLORS[rank - 1],
                                        modifier = Modifier.size(34.dp).padding(end = 4.dp),
                                    )
                                } else {
                                    Text(
                                        "$rank",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(38.dp),
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatIcon(R.drawable.ic_like, r.likeCount)
                                        Spacer(Modifier.width(10.dp))
                                        StatIcon(R.drawable.ic_heart, r.favoriteCount)
                                        Spacer(Modifier.width(10.dp))
                                        StatIcon(R.drawable.ic_eye, r.views)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatIcon(res: Int, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "$value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

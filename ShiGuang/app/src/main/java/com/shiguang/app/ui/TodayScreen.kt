package com.shiguang.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.DailyPlan
import com.shiguang.app.data.toDailyPlan
import com.shiguang.app.data.toRecipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TodayScreen(target: Int?, onOpenRecipe: (Long) -> Unit, onOpenWeekly: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<DailyPlan?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var familyMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showRoll by remember { mutableStateOf(false) }
    var rollDish by remember { mutableStateOf<com.shiguang.app.data.Recipe?>(null) }
    var rolling by remember { mutableStateOf(false) }

    fun load(rerollSeed: String?) {
        scope.launch {
            refreshing = true
            try {
                val q = buildString {
                    append("/recipes/daily?target=")
                    append(target ?: 2000)
                    if (familyMode) append("&family=1")
                    if (rerollSeed != null) append("&seed=").append(rerollSeed)
                }
                plan = Api.get(q).toDailyPlan()
                err = null
            } catch (e: Exception) {
                err = e.message
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(target, familyMode) { load(null) }

    fun reroll() {
        val current = plan ?: return
        scope.launch {
            refreshing = true
            try {
                var next: DailyPlan? = null
                for (i in 1..5) {
                    val seed = System.currentTimeMillis().toString() + "-" + i
                    val q = buildString {
                        append("/recipes/daily?target=")
                        append(target ?: 2000)
                        if (familyMode) append("&family=1")
                        append("&seed=").append(seed)
                    }
                    val candidate = Api.get(q).toDailyPlan()
                    val changed = candidate.meals.map { it.recipe.id } != current.meals.map { it.recipe.id }
                    if (changed || i == 5) { next = candidate; break }
                }
                plan = next ?: current
                err = null
            } catch (e: Exception) {
                err = e.message
            } finally {
                refreshing = false
            }
        }
    }

    // 摇一摇滚动动画：骰子图标旋转
    val rollSpin by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (rolling) 1080f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 900),
        label = "rollSpin",
    )

    fun roll() {
        if (rolling) return
        rolling = true
        showRoll = true
        rollDish = null
        scope.launch {
            try {
                val r = Api.get("/recipes/random").getJSONObject("item").toRecipe()
                delay(700) // 让摇一摇动画有仪式感
                rollDish = r
            } catch (e: Exception) {
                err = e.message
                showRoll = false
            } finally {
                rolling = false
            }
        }
    }

    when {
        err != null && plan == null -> ErrorBox(err!!)
        plan == null -> LoadingBox()
        else -> {
            val p = plan!!
            if (showRoll) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { if (!rolling) showRoll = false },
                    title = { Text("🎲 今天吃啥") },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            if (rolling || rollDish == null) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(com.shiguang.app.R.drawable.ic_dice),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .graphicsLayer { rotationZ = rollSpin },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text("摇一摇中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                val d = rollDish!!
                                Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${d.kcal} kcal · ${d.minutes} 分钟 · ${d.category}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    androidx.compose.material3.FilledTonalButton(onClick = { showRoll = false; onOpenRecipe(d.id) }) { Text("就吃它 →") }
                                    androidx.compose.material3.FilledTonalButton(onClick = { roll() }) { Text("再摇") }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { if (!rolling) showRoll = false }) { Text("关闭") }
                    },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("今日三餐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenWeekly, enabled = !refreshing) { Text("一周计划") }
                        TextButton(onClick = { reroll() }, enabled = !refreshing) { Text(if (refreshing) "换菜中…" else "换一换") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (p.breakdown != null) "${p.date} · 全家餐桌 · 目标 ${p.target} kcal"
                            else "${p.date} · 按你 ${p.target} kcal 的目标",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.FilterChip(
                            selected = familyMode,
                            onClick = { familyMode = !familyMode },
                            label = { Text("全家餐桌") },
                        )
                    }
                    p.breakdown?.let { parts ->
                        Text(
                            "全家目标：${parts.joinToString(" + ") { "${it.name} ${it.target}" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { showRoll = true },
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(
                                androidx.compose.ui.res.painterResource(com.shiguang.app.R.drawable.ic_dice),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("今天吃啥？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("摇一道菜，决定就是它", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("摇一摇 →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "共 ${p.totalKcal} kcal",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                                AssistChip(
                                    onClick = {},
                                    label = { Text(if (p.withinRange) "✓ 贴合目标" else "偏离目标") },
                                )
                            }
                            Text(
                                "目标区间 ${p.target * 85 / 100} ~ ${p.target * 115 / 100} kcal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(p.meals.size) { i ->
                    val m = p.meals[i]
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecipe(m.recipe.id) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text(m.label) })
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    m.recipe.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${m.kcal} kcal · ${m.recipe.minutes} 分钟 · ${"★".repeat(m.recipe.difficulty)}${"☆".repeat((3 - m.recipe.difficulty).coerceAtLeast(0))} · ${if (m.servings != 1.0) "${m.servings} 份" else "1 份"}",
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

@Composable
internal fun LoadingBox() {
    // 骨架屏：闪烁的占位卡片，比"加载中…"文字体验好
    val alpha = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(700, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.45f)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha.value), MaterialTheme.shapes.medium),
        )
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha.value), MaterialTheme.shapes.medium),
            )
        }
    }
}

@Composable
internal fun ErrorBox(message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("出错了", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "请检查网络连接",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package com.shiguang.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
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
fun TodayScreen(target: Int?, onOpenRecipe: (Long) -> Unit, onOpenWeekly: () -> Unit, onOpenDiary: () -> Unit = {}, onOpenStreet: () -> Unit = {}, onOpenShake: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<DailyPlan?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var logTip by remember { mutableStateOf<String?>(null) }
    var familyMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // 几菜一汤结构：午/晚 1~4 道菜 + 汤开关
    var lunchN by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(1) }
    var dinnerN by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(1) }
    var withSoup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    var showStructure by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    fun load(rerollSeed: String?) {
        scope.launch {
            refreshing = true
            try {
                val q = buildString {
                    append("/recipes/daily?target=")
                    append(target ?: 2000)
                    if (familyMode) append("&family=1")
                    append("&lunch_n=").append(lunchN)
                    append("&dinner_n=").append(dinnerN)
                    if (!withSoup) append("&soup=0")
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

    LaunchedEffect(target, familyMode, lunchN, dinnerN, withSoup) { load(null) }

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
                        append("&lunch_n=").append(lunchN)
                        append("&dinner_n=").append(dinnerN)
                        if (!withSoup) append("&soup=0")
                        append("&seed=").append(seed)
                    }
                    val candidate = Api.get(q).toDailyPlan()
                    val ids = { plan2: com.shiguang.app.data.DailyPlan ->
                        plan2.meals.flatMap { it.dishes?.map { d -> d.recipe.id } ?: listOf(it.recipe.id) }
                    }
                    val changed = ids(candidate) != ids(current)
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

    // 把某餐的每道菜记入三餐日记
    fun logMealToDiary(m: com.shiguang.app.data.DailyMeal) {
        scope.launch {
            try {
                val date = plan?.date
                val dishes = m.dishes ?: listOf(com.shiguang.app.data.DishItem(m.recipe, m.servings, m.kcal, ""))
                for (d in dishes) {
                    val body = org.json.JSONObject()
                    if (date != null && date.isNotBlank()) body.put("date", date)
                    body.put("meal_key", m.key)
                    body.put("recipe_id", d.recipe.id)
                    if (d.servings != 1.0) body.put("servings", d.servings)
                    Api.post("/diary", body)
                }
                logTip = "已记入${m.label}日记"
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    // 摇一摇：仅今日页内响应摇动手机（离开本页自动注销传感器，其他页面不误触）
    // 阈值 3.2g + 防抖 2s，避免日常拿手机就触发
    val shakeCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(shakeCtx) {
        val sm = shakeCtx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var lastShake = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val gx = e.values[0] / android.hardware.SensorManager.GRAVITY_EARTH
                val gy = e.values[1] / android.hardware.SensorManager.GRAVITY_EARTH
                val gz = e.values[2] / android.hardware.SensorManager.GRAVITY_EARTH
                val g = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz.toDouble())
                if (g > 3.2 && com.shiguang.app.data.AppSettings.shakeEnabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastShake > 2000) {
                        lastShake = now
                        onOpenShake()
                    }
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        sm?.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm?.unregisterListener(listener) }
    }

    when {
        err != null && plan == null -> ErrorBox(err!!)
        plan == null -> LoadingBox()
        else -> {
            val p = plan!!
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
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.FilterChip(
                            selected = showStructure,
                            onClick = { showStructure = !showStructure },
                            label = { Text("几菜一汤") },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "午 ${lunchN} 菜${if (withSoup) " 1 汤" else ""} · 晚 ${dinnerN} 菜${if (withSoup) " 1 汤" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenDiary) { Text("三餐日记 ›") }
                    }
                    logTip?.let {
                        Text(
                            "✓ $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (com.shiguang.app.data.AppSettings.streetMode) {
                        Spacer(Modifier.height(6.dp))
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpenStreet() },
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(com.shiguang.app.R.drawable.ic_tab_ingredients),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("干饭街 · 学生/上班族", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("单人套餐推荐 · 转盘决定 · 外食记账", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("进入 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (showStructure) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                listOf("午餐" to lunchN, "晚餐" to dinnerN).forEach { (label, cur) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                                        listOf(1, 2, 3, 4).forEach { n ->
                                            androidx.compose.material3.FilterChip(
                                                selected = cur == n,
                                                onClick = { if (label == "午餐") lunchN = n else dinnerN = n },
                                                label = { Text(if (n == 1) "1 道" else "$n 道") },
                                                modifier = Modifier.padding(end = 6.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("汤", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                                    androidx.compose.material3.FilterChip(
                                        selected = withSoup,
                                        onClick = { withSoup = !withSoup },
                                        label = { Text("每餐配 1 汤") },
                                    )
                                }
                            }
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text(m.label) })
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    "${m.kcal} kcal",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { logMealToDiary(m) },
                                    enabled = !refreshing,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                                ) { Text("+日记") }
                            }
                            Spacer(Modifier.height(4.dp))
                            val dishList = m.dishes
                                ?: listOf(com.shiguang.app.data.DishItem(m.recipe, m.servings, m.kcal, if (m.servings <= 1.75) "中份" else "大份"))
                            dishList.forEach { d ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenRecipe(d.recipe.id) }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.recipe.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${d.recipe.minutes} 分钟 · ${"★".repeat(d.recipe.difficulty)}${"☆".repeat((3 - d.recipe.difficulty).coerceAtLeast(0))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${d.servingLabel} ${d.kcal} kcal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                if (p.drinks.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text("饮品") })
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    p.drinks.forEach { dr ->
                                        Text("${dr.name} · ${dr.kcal} kcal", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(
                                        "按富余热量配的一杯，饮品不在菜谱库中",
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

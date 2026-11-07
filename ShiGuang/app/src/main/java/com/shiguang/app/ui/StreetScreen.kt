package com.shiguang.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiguang.app.data.Api
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class StreetFood(
    val id: Long,
    val name: String,
    val category: String,
    val price: Double,
    val kcal: Int,
    val spicy: Int,
    val intro: String,
)

private val SPICY_LABEL = listOf("不辣", "微辣", "中辣", "重辣")

private fun slotLabel(s: String): String = when (s) {
    "breakfast" -> "早餐"
    "lunch" -> "午餐"
    "dinner" -> "晚餐"
    "night" -> "夜宵"
    else -> "全天"
}

/**
 * 干饭街：大学生/上班族模式——美食街单人餐推荐、转盘决定吃什么、外食打卡记账。
 * 开关在「我的」页；主页有入口卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val now = remember { Calendar.getInstance() }
    val hour = now.get(Calendar.HOUR_OF_DAY)
    var slot by rememberSaveable { mutableStateOf(if (hour < 10) "breakfast" else if (hour < 14) "lunch" else if (hour < 21) "dinner" else "night") }
    var budget by rememberSaveable { mutableStateOf(0) }   // 0=不限
    var spicyMax by rememberSaveable { mutableStateOf(4) } // 4=都能吃
    var recs by remember { mutableStateOf<List<StreetFood>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 转盘
    var wheelOpen by remember { mutableStateOf(false) }
    var wheelItems by remember { mutableStateOf<List<StreetFood>>(emptyList()) }
    var wheelErr by remember { mutableStateOf<String?>(null) }

    // 手动打卡
    var checkinOpen by remember { mutableStateOf<StreetFood?>(null) }
    var manualName by remember { mutableStateOf("") }
    var manualPrice by remember { mutableStateOf("") }

    // 小结
    var monthCount by remember { mutableStateOf(0) }
    var monthPrice by remember { mutableStateOf(0.0) }
    var favs by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Triple<String, Double?, String>>>(emptyList()) }

    fun load() {
        scope.launch {
            loading = true
            try {
                val q = StringBuilder("/street/menu?slot=").append(slot)
                    .append("&budget=").append(budget)
                    .append("&spicy_max=").append(spicyMax)
                val d = Api.get(q.toString(), auth = false)
                val arr = d.optJSONArray("items") ?: JSONArray()
                recs = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    StreetFood(o.getLong("id"), o.getString("name"), o.optString("category"), o.optDouble("price"), o.optInt("kcal"), o.optInt("spicy"), o.optString("intro"))
                }
                err = null
            } catch (e: Exception) {
                err = e.message
                recs = emptyList()
            }
            loading = false
            if (Api.token != null) {
                runCatching {
                    val s = Api.get("/street/summary")
                    monthCount = s.optInt("count")
                    monthPrice = s.optDouble("total_price", 0.0)
                    val fa = s.optJSONArray("favs") ?: JSONArray()
                    favs = (0 until fa.length()).map { i -> val o = fa.getJSONObject(i); o.getString("name") to o.getInt("times") }
                    val ra = s.optJSONArray("recent") ?: JSONArray()
                    recent = (0 until ra.length()).map { i ->
                        val o = ra.getJSONObject(i)
                        Triple(o.getString("name"), if (o.isNull("price")) null else o.optDouble("price"), o.optString("date"))
                    }
                }
            }
        }
    }

    fun openWheel() {
        scope.launch {
            wheelErr = null
            try {
                val d = Api.get("/street/wheel?slot=$slot&budget=$budget&spicy_max=$spicyMax&n=6", auth = false)
                val arr = d.optJSONArray("items") ?: JSONArray()
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    StreetFood(o.getLong("id"), o.getString("name"), o.optString("category"), o.optDouble("price"), o.optInt("kcal"), o.optInt("spicy"), o.optString("intro"))
                }
                // 随机定赢家并排到指针正下方（items[0]），转盘动画只转整数圈，结果与画面必然一致
                wheelItems = if (list.isEmpty()) list else {
                    val winner = list.random()
                    listOf(winner) + list.filter { it != winner }.shuffled()
                }
                wheelOpen = true
            } catch (e: Exception) {
                wheelErr = e.message
            }
        }
    }

    fun checkin(food: StreetFood?) {
        val name = food?.name ?: manualName.trim()
        if (name.isEmpty()) return
        scope.launch {
            try {
                val body = JSONObject().put("food_name", name).put("slot", slot)
                val price = food?.price ?: manualPrice.toDoubleOrNull()
                if (price != null) body.put("price", price)
                food?.kcal?.let { body.put("kcal", it) }
                Api.post("/street/checkin", body)
                tip = "已记录「$name」"
                manualName = ""
                manualPrice = ""
                checkinOpen = null
                load()
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    LaunchedEffect(slot, budget, spicyMax) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("干饭街") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "学校/公司周边怎么吃：单人套餐、粉面小吃、轻食、干锅烤鱼都在这",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ── 过滤器 ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐", "night" to "夜宵").forEach { (k, label) ->
                    FilterChip(selected = slot == k, onClick = { slot = k }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "不限预算", 10 to "≤10元", 15 to "≤15元", 20 to "≤20元").forEach { (v, label) ->
                    FilterChip(selected = budget == v, onClick = { budget = v }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4 to "都能吃辣", 1 to "微辣", 0 to "不辣").forEach { (v, label) ->
                    FilterChip(selected = spicyMax == v, onClick = { spicyMax = v }, label = { Text(label) })
                }
            }

            tip?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            // ── 转盘 ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("拿不准吃什么？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (wheelErr != null) wheelErr!! else "转盘上放 6 道过滤后的美食，转到哪个吃哪个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { openWheel() }, enabled = wheelItems.isEmpty() || wheelErr == null || true) {
                        Text("开转盘决定")
                    }
                }
            }

            // ── 今日推荐 ──
            Text("今日推荐 · ${slotLabel(slot)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (loading && recs.isEmpty()) {
                LoadingBox()
            } else if (recs.isEmpty() && err == null) {
                Text("这个时段暂时没有符合条件的选择", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                recs.forEach { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOf(f.category, SPICY_LABEL.getOrElse(f.spicy) { "" }.takeIf { f.spicy > 0 }, "${f.kcal} kcal")
                                            .filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "¥${if (f.price % 1.0 == 0.0) f.price.toInt() else f.price}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (f.intro.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(f.intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { checkin(f) }, enabled = Api.token != null) { Text("吃了，记一笔") }
                            }
                            if (Api.token == null) {
                                Text("游客模式不能记账，登录后可统计本月外食", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }

            // ── 手动打卡 ──
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("自己吃了别的？手动记一笔", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualName,
                        onValueChange = { manualName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("吃了什么，如：食堂二楼麻辣香锅") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualPrice,
                            onValueChange = { manualPrice = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.width(140.dp),
                            placeholder = { Text("花了多少元") },
                            singleLine = true,
                        )
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { checkin(null) }, enabled = manualName.isNotBlank() && Api.token != null) { Text("记一笔") }
                    }
                }
            }

            // ── 本月外食小结 ──
            if (Api.token != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("本月外食小结", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text("$monthCount", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("次打卡", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("¥${monthPrice.toInt()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("总花费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1.4f)) {
                                Text(
                                    if (favs.isEmpty()) "—" else favs.joinToString("、") { "${it.first}×${it.second}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text("最常吃", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (recent.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            recent.take(4).forEach { (name, price, date) ->
                                Text(
                                    "$date  $name" + (price?.let { "  ¥${if (it % 1.0 == 0.0) it.toInt() else it}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (wheelOpen && wheelItems.isNotEmpty()) {
        WheelDialog(
            items = wheelItems,
            onDismiss = { wheelOpen = false },
            onPick = { f ->
                wheelOpen = false
                checkin(f)
            },
            onRefresh = { openWheel() },
        )
    }

    if (checkinOpen != null) {
        AlertDialog(
            onDismissRequest = { checkinOpen = null },
            title = { Text("记录外食") },
            text = { Text("把「${checkinOpen!!.name}」记入本月外食小结？（¥${checkinOpen!!.price.toInt()}）") },
            confirmButton = { Button(onClick = { checkin(checkinOpen) }) { Text("记录") } },
            dismissButton = { TextButton(onClick = { checkinOpen = null }) { Text("算了") } },
        )
    }
}

private val WHEEL_COLORS = listOf(
    Color(0xFFF3B8A5), Color(0xFFE8C8DC), Color(0xFFC8D8E8),
    Color(0xFFD8E8C8), Color(0xFFF0DFA8), Color(0xFFD8C8E8),
    Color(0xFFE8D8C8), Color(0xFFC8E8D8),
)

/** 转盘弹窗：Canvas 扇区 + 减速旋转动画 + 结果结算 */
@Composable
private fun WheelDialog(items: List<StreetFood>, onDismiss: () -> Unit, onPick: (StreetFood) -> Unit, onRefresh: () -> Unit = {}) {
    val seg = 360f / items.size
    val rotation = remember { Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var spinning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StreetFood?>(null) }
    val textMeasurer = rememberTextMeasurer()
    LaunchedEffect(items) {
        rotation.snapTo(0f)
        result = null
    }

    fun spin() {
        if (spinning || items.isEmpty()) return
        spinning = true
        result = null
        // 结构性方案：赢家已被排到指针正下方（items[0]），动画只转整数圈回到原位——结果与画面必然一致
        scope.launch {
            rotation.animateTo(
                rotation.value + 5 * 360f,
                animationSpec = tween(durationMillis = 3200, easing = CubicBezierEasing(0.12f, 0.85f, 0.15f, 1f)),
            )
            rotation.snapTo(rotation.value)
            result = items.first()
            spinning = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!spinning) onDismiss() },
        title = { Text("转盘决定", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
        text = {
            val pointerColor = MaterialTheme.colorScheme.primary
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
                    Canvas(
                        Modifier.size(280.dp).then(
                            if (!spinning && result == null) Modifier.clickable { spin() } else Modifier,
                        ),
                    ) {
                        val r = min(size.width, size.height) / 2f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        rotate(rotation.value, pivot = c) {
                            items.forEachIndexed { i, f ->
                                val start = -90f - seg / 2f + i * seg
                                drawArc(
                                    color = WHEEL_COLORS[i % WHEEL_COLORS.size],
                                    startAngle = start,
                                    sweepAngle = seg,
                                    useCenter = true,
                                    topLeft = Offset(c.x - r, c.y - r),
                                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                )
                                if (result === f) {
                                    drawArc(
                                        color = Color.White.copy(alpha = 0.9f),
                                        startAngle = start,
                                        sweepAngle = seg,
                                        useCenter = false,
                                        topLeft = Offset(c.x - r, c.y - r),
                                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                        style = Stroke(width = 4.dp.toPx()),
                                    )
                                }
                            }
                            // 文字「印」在扇区上：随盘同角度旋转、沿扇区中线径向排布（真实转盘样式）
                            items.forEachIndexed { i, f ->
                                val mid = -90f + i * seg
                                rotate(mid, pivot = c) {
                                    val layout = textMeasurer.measure(
                                        f.name.take(6),
                                        style = TextStyle(
                                            fontSize = if (result === f) 14.sp else 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF3A2A24),
                                        ),
                                    )
                                    val dist = r - 24.dp.toPx()
                                    drawText(
                                        layout,
                                        topLeft = Offset(c.x - layout.size.width / 2f, c.y - dist - layout.size.height / 2f),
                                    )
                                }
                            }
                        }
                        drawCircle(Color(0xFFB08970), radius = 4.dp.toPx(), center = c)
                        // 顶部指针：主色小三角，指向当前扇区
                        val triY = c.y - r - 2.dp.toPx()
                        val tri = androidx.compose.ui.geometry.Rect(
                            c.x - 8.dp.toPx(), triY - 12.dp.toPx(),
                            c.x + 8.dp.toPx(), triY + 4.dp.toPx(),
                        )
                        drawPath(
                            androidx.compose.ui.graphics.Path().apply {
                                moveTo(c.x, tri.bottom)
                                lineTo(tri.left, tri.top)
                                lineTo(tri.right, tri.top)
                                close()
                            },
                            color = pointerColor,
                        )
                    }
                    if (!spinning && result == null) {
                        Text("点转盘\n开始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    if (spinning) {
                        Text("转动中…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                result?.let { f ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "就吃「${f.name}」！",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${f.category} · ¥${f.price.toInt()} · ${f.kcal} kcal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (result != null) {
                Button(onClick = { onPick(result!!) }) { Text("吃了，记一笔") }
            } else {
                Button(onClick = { spin() }, enabled = !spinning) { Text(if (spinning) "转动中…" else "开始转") }
            }
        },
        dismissButton = {
            Row {
                if (result == null && !spinning) {
                    TextButton(onClick = onRefresh) { Text("换一批") }
                }
                TextButton(onClick = { if (!spinning) { if (result != null) { result = null; spin() } else onDismiss() } }) {
                    Text(
                        if (result != null) "再转一次" else "关闭",
                        color = if (result != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

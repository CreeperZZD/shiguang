package com.shiguang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val MEAL_LABELS = linkedMapOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐")

private data class DiaryEntry(
    val id: Long,
    val mealKey: String,
    val recipeId: Long?,
    val name: String,
    val kcal: Int?,
    val servings: Double?,
    val note: String,
)

private data class DiaryData(
    val date: String,
    val meals: Map<String, List<DiaryEntry>>,
    val totalKcal: Int,
)

private fun JSONObject.toDiaryEntry(): DiaryEntry = DiaryEntry(
    id = getLong("id"),
    mealKey = optString("meal_key"),
    recipeId = if (isNull("recipe_id")) null else optLong("recipe_id"),
    name = optString("recipe_name"),
    kcal = if (isNull("kcal")) null else optInt("kcal"),
    servings = if (isNull("servings")) null else optDouble("servings"),
    note = optString("note"),
)

/**
 * 三餐日记：只记录一日三餐吃了什么。按天浏览，支持手动添加与删除；
 * 主页餐卡上的「+日记」会把整餐一键记进来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(onBack: () -> Unit, onLogin: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(todayStr()) }
    var data by remember { mutableStateOf<DiaryData?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }

    var mealKey by remember { mutableStateOf("lunch") }
    var nameInput by remember { mutableStateOf("") }
    var kcalInput by remember { mutableStateOf("") }

    fun load(d: String) {
        scope.launch {
            try {
                val resp = Api.get("/diary?date=$d")
                val meals = mutableMapOf<String, List<DiaryEntry>>()
                val m = resp.optJSONObject("meals") ?: JSONObject()
                for (k in MEAL_LABELS.keys) {
                    val arr = m.optJSONArray(k) ?: JSONArray()
                    val list = mutableListOf<DiaryEntry>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i).toDiaryEntry())
                    meals[k] = list
                }
                data = DiaryData(resp.optString("date", d), meals, resp.optInt("total_kcal"))
                err = null
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    fun shiftDate(days: Int) {
        val next = shiftDateStr(date, days)
        date = next
        tip = null
        load(next)
    }

    fun addEntry() {
        if (nameInput.isBlank()) { err = "先写菜名"; return }
        scope.launch {
            try {
                val body = JSONObject()
                    .put("date", date)
                    .put("meal_key", mealKey)
                    .put("recipe_name", nameInput.trim())
                if (kcalInput.isNotBlank()) body.put("kcal", kcalInput.trim())
                Api.post("/diary", body)
                nameInput = ""
                kcalInput = ""
                tip = "已记录到${MEAL_LABELS[mealKey]}"
                load(date)
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    fun removeEntry(id: Long) {
        scope.launch {
            try {
                Api.delete("/diary/$id")
                tip = null
                load(date)
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    LaunchedEffect(Unit) { load(date) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("三餐日记") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        if (Api.token == null) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("日记需要登录后使用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "登录后日记云端同步，主页餐卡上点「+日记」即可一键记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onLogin) { Text("去登录 / 注册") }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 日期导航
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(vertical = 6.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { shiftDate(-1) }) { Text("‹ 前一天") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(date, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        data?.let { Text("共 ${it.totalKcal} kcal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = { shiftDate(1) }, enabled = date < todayStr()) { Text("后一天 ›") }
                }
            }
            Spacer(Modifier.height(10.dp))
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            tip?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            val d = data
            if (d == null) {
                LoadingBox()
            } else {
                MEAL_LABELS.forEach { (key, label) ->
                    val entries = d.meals[key] ?: emptyList()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text(label) })
                                Spacer(Modifier.size(8.dp))
                                val sum = entries.sumOf { it.kcal ?: 0 }
                                Text(
                                    if (entries.isEmpty()) "还没记录" else "${entries.size} 条 · $sum kcal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            entries.forEach { e ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).clickable(enabled = e.recipeId != null) { }) {
                                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                                        val parts = mutableListOf<String>()
                                        e.kcal?.let { parts.add("$it kcal") }
                                        e.servings?.let { parts.add("${it} 份") }
                                        if (e.note.isNotEmpty()) parts.add(e.note)
                                        if (parts.isNotEmpty()) {
                                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Text(
                                        " ×",
                                        Modifier
                                            .clickable { removeEntry(e.id) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // 手动添加
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("手动添加", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MEAL_LABELS.forEach { (k, label) ->
                                FilterChip(
                                    selected = mealKey == k,
                                    onClick = { mealKey = k },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("吃了什么？如：食堂的红烧肉") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = kcalInput,
                                onValueChange = { kcalInput = it.filter { c -> c.isDigit() } },
                                modifier = Modifier.width(150.dp),
                                placeholder = { Text("热量(选填)") },
                                singleLine = true,
                            )
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { addEntry() }, enabled = nameInput.isNotBlank()) { Text("记一笔") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun todayStr(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    .format(java.util.Date())

private fun shiftDateStr(date: String, days: Int): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val d = try { fmt.parse(date) } catch (e: Exception) { null } ?: return date
    val cal = java.util.Calendar.getInstance()
    cal.time = d
    cal.add(java.util.Calendar.DAY_OF_MONTH, days)
    return fmt.format(cal.time)
}

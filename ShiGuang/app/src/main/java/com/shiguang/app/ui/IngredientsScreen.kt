package com.shiguang.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.shiguang.app.R
import com.shiguang.app.data.Api
import com.shiguang.app.data.Recipe
import com.shiguang.app.data.toRecipe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private data class RecItem(val recipe: Recipe, val missing: List<String>)
private data class ShoppingItem(val id: Long, val name: String)
private data class ShelfAlert(val name: String, val daysLeft: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IngredientsScreen(onOpenRecipe: (Long) -> Unit, onOpenFridgeScan: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pantry by remember { mutableStateOf<List<String>>(emptyList()) }
    var shelfLife by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) } // name → 保质期天数
    var alerts by remember { mutableStateOf<List<ShelfAlert>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("strict") }
    var results by remember { mutableStateOf<List<RecItem>>(emptyList()) }
    var shopping by remember { mutableStateOf<List<ShoppingItem>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var suggestJob by remember { mutableStateOf<Job?>(null) }
    var shelfTarget by remember { mutableStateOf<String?>(null) } // 弹保质期菜单的食材

    fun onInputChange(s: String) {
        input = s
        suggestJob?.cancel()
        if (s.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(250)
            try {
                val arr = Api.get("/ingredients/suggest?q=${URLEncoder.encode(s.trim(), "UTF-8")}")
                    .optJSONArray("items") ?: JSONArray()
                suggestions = (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                suggestions = emptyList()
            }
        }
    }

    fun refreshRecommend() {
        scope.launch {
            try {
                val r = Api.get("/recommend/by-ingredients?mode=$mode")
                val arr = r.optJSONArray("items") ?: JSONArray()
                results = (0 until arr.length()).map { o ->
                    val item = arr.getJSONObject(o)
                    val missing = item.optJSONArray("missing")?.let { m ->
                        (0 until m.length()).map { m.getString(it) }
                    } ?: emptyList()
                    RecItem(item.getJSONObject("recipe").toRecipe(), missing)
                }
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    fun refreshShopping() {
        scope.launch {
            try {
                val arr = Api.get("/shopping-list").optJSONArray("items") ?: JSONArray()
                shopping = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ShoppingItem(o.optLong("id"), o.optString("name", o.optString("ingredient_name")))
                }
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val resp = Api.get("/me/ingredients")
            val names = resp.optJSONArray("names") ?: JSONArray()
            pantry = (0 until names.length()).map { names.getString(it) }
            val items = resp.optJSONArray("items") ?: JSONArray()
            val shelf = mutableMapOf<String, Int>()
            val alertList = mutableListOf<ShelfAlert>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val shelfDays = if (o.isNull("shelf_life_days")) null else o.optInt("shelf_life_days")
                val dl = if (o.isNull("days_left")) null else o.optInt("days_left")
                if (shelfDays != null) shelf[o.getString("name")] = shelfDays
                if (shelfDays != null && dl != null && dl <= 3) alertList.add(ShelfAlert(o.getString("name"), dl))
            }
            shelfLife = shelf
            alerts = alertList
            loaded = true
            refreshShopping()
            refreshRecommend()
        } catch (e: Exception) {
            err = e.message
        }
    }

    fun savePantry(newList: List<String>, newShelf: Map<String, Int?> = shelfLife) {
        pantry = newList
        shelfLife = newShelf
        scope.launch {
            try {
                val items = JSONArray()
                newList.forEach { n ->
                    val o = JSONObject().put("name", n)
                    newShelf[n]?.let { o.put("shelf_life_days", it) }
                    items.put(o)
                }
                Api.put("/me/ingredients", JSONObject().put("items", items))
                refreshRecommend()
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("冰箱里有什么？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onOpenFridgeScan) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("拍冰箱")
                }
            }
        }

        // 临期提醒横幅
        if (alerts.isNotEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_bell), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("临期提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            alerts.joinToString("、") { "${it.name}（剩 ${it.daysLeft.coerceAtLeast(0)} 天）" } + "，优先吃掉它们！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    if (pantry.isEmpty()) {
                        Text(
                            "添加你现有的食材，我来告诉你能做什么",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pantry.forEach { name ->
                                val dl = shelfLife[name]
                                val isExpiring = alerts.any { it.name == name }
                                AssistChip(
                                    onClick = { shelfTarget = name },
                                    label = {
                                        Text(
                                            name +
                                                (if (dl != null) " ·${dl}天" else "") + " ×",
                                            color = if (isExpiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { onInputChange(it.take(12)) },
                            label = { Text("如：鸡蛋") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                val n = input.trim()
                                if (n.isNotEmpty() && !pantry.contains(n)) savePantry(pantry + n, shelfLife + (n to null))
                                input = ""
                                suggestions = emptyList()
                            },
                        ) { Text("添加") }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "strict",
                    onClick = { mode = "strict"; refreshRecommend() },
                    label = { Text("有什么做什么") },
                )
                FilterChip(
                    selected = mode == "best",
                    onClick = { mode = "best"; refreshRecommend() },
                    label = { Text("最佳推荐(可补料)") },
                )
            }
        }

        item {
            Text(
                if (mode == "strict") "只用你已有的食材（油盐酱醋等基础调料默认算有）" else "只缺 1~2 样食材就能做的菜",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (results.isEmpty() && loaded && err == null) {
            item {
                Text(
                    if (pantry.isEmpty()) "先添加食材吧" else "暂时没有匹配的菜",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(results.size, key = { results[it].recipe.id }) { i ->
            val item = results[i]
            Card(Modifier.fillMaxWidth().clickable { onOpenRecipe(item.recipe.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.recipe.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${item.recipe.kcal} kcal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    if (item.missing.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "还缺：${item.missing.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            item.missing.forEach { miss ->
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        try {
                                            Api.post("/shopping-list", JSONObject().put("name", miss))
                                            refreshShopping()
                                        } catch (e: Exception) {
                                            err = e.message
                                        }
                                    }
                                }) { Text("+ 购物清单：$miss") }
                            }
                        }
                    }
                }
            }
        }

        if (shopping.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("购物清单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = {
                        val text = buildString {
                            appendLine("🛒 食光购物清单")
                            shopping.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.name}") }
                        }
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    },
                                    "分享购物清单",
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("分享")
                    }
                }
            }
            items(shopping.size, key = { shopping[it].id }) { i ->
                val s = shopping[i]
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = {
                            scope.launch {
                                try {
                                    Api.delete("/shopping-list/${s.id}")
                                    shopping = shopping - s
                                    val names = Api.get("/me/ingredients").optJSONArray("names") ?: JSONArray()
                                    pantry = (0 until names.length()).map { names.getString(it) }
                                    refreshRecommend()
                                } catch (e: Exception) {
                                    err = e.message
                                }
                            }
                        }) { Text("已买到") }
                    }
                }
            }
        }

        err?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
    }

    // 食材 chip 点击 → 保质期设置 / 移除菜单
    shelfTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { shelfTarget = null },
            title = { Text("「$target」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("设置保质期（临期会提醒）", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3, 7, 14, 30).forEach { d ->
                            FilledTonalButton(onClick = {
                                savePantry(pantry, shelfLife + (target to d))
                                shelfTarget = null
                            }) { Text("${d}天") }
                        }
                    }
                    TextButton(onClick = {
                        savePantry(pantry - target, shelfLife - target)
                        shelfTarget = null
                    }) { Text("🗑 移除此食材", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { shelfTarget = null }) { Text("取消") }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }
}

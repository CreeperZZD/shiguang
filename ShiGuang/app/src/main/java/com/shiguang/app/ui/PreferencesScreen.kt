package com.shiguang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.shiguang.app.R
import com.shiguang.app.data.Api
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val CUISINE_OPTIONS = listOf(
    "家常小炒", "烧炖", "蒸菜", "汤羹", "主食", "凉菜", "早餐", "海鲜",
)

/**
 * 饮食偏好编辑页：不喜欢的食材 / 忌口与过敏 / 健康状况（病理禁忌）/ 菜系偏好
 * 数据来源：GET/PUT /me/preferences；健康状况预设：GET /me/preferences/conditions
 * 未设置任何偏好时推荐与警示不会启用。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PreferencesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var savedTip by remember { mutableStateOf(false) }

    var dislikes by remember { mutableStateOf<List<String>>(emptyList()) }
    var allergens by remember { mutableStateOf<List<String>>(emptyList()) }
    var conditions by remember { mutableStateOf<List<String>>(emptyList()) }
    var cuisines by remember { mutableStateOf<List<String>>(emptyList()) }
    var conditionItems by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }

    var dislikeInput by remember { mutableStateOf("") }
    var allergenInput by remember { mutableStateOf("") }
    var dislikeSuggest by remember { mutableStateOf<List<String>>(emptyList()) }
    var allergenSuggest by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            val prefs = Api.get("/me/preferences")
            val p = prefs.optJSONObject("preferences") ?: JSONObject()
            dislikes = stringList(p, "dislikes")
            allergens = stringList(p, "allergens")
            conditions = stringList(p, "conditions")
            cuisines = stringList(p, "cuisines")
            val cs = Api.get("/me/preferences/conditions")
            val arr = cs.optJSONArray("items") ?: JSONArray()
            val items = mutableListOf<Triple<String, String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Triple(o.optString("key"), o.optString("label"), o.optString("avoid")))
            }
            conditionItems = items
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        }
        loading = false
    }

    fun save() {
        scope.launch {
            saving = true
            err = null
            try {
                val body = JSONObject()
                    .put("dislikes", JSONArray(dislikes))
                    .put("allergens", JSONArray(allergens))
                    .put("conditions", JSONArray(conditions))
                    .put("cuisines", JSONArray(cuisines))
                Api.put("/me/preferences", body)
                savedTip = true
            } catch (e: Exception) {
                err = e.message ?: "保存失败"
            }
            saving = false
        }
    }

    // 食材联想：复用 /ingredients/suggest 聚合索引
    fun suggestFor(input: String, apply: (List<String>) -> Unit) {
        if (input.isBlank()) { apply(emptyList()); return }
        scope.launch {
            runCatching {
                val r = Api.get("/ingredients/suggest?q=" + java.net.URLEncoder.encode(input.trim(), "UTF-8"))
                val arr = r.optJSONArray("suggestions") ?: JSONArray()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    if (out.size >= 6) break
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) out.add(s)
                }
                apply(out)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("饮食偏好") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        when {
            loading -> Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            else -> Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                err?.let {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "设置后，推荐与三餐将自动避开相关食材；未设置的项不会产生任何提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // ── 不喜欢的食材 ──────────────────────────────
                SectionTitle("不喜欢的食材")
                Spacer(Modifier.height(6.dp))
                Text("只是不爱吃：会尽量避开，偶尔仍可能出现", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                ChipEditor(
                    items = dislikes,
                    input = dislikeInput,
                    suggestions = dislikeSuggest.filter { it !in dislikes },
                    onInput = { v ->
                        dislikeInput = v
                        savedTip = false
                        suggestFor(v) { dislikeSuggest = it }
                    },
                    onAdd = { name ->
                        if (name.isNotBlank() && name !in dislikes) {
                            dislikes = dislikes + name.trim()
                            savedTip = false
                        }
                        dislikeInput = ""
                        dislikeSuggest = emptyList()
                    },
                    onRemove = { dislikes = dislikes - it; savedTip = false },
                    placeholder = "输入食材名，如：香菜",
                )
                Spacer(Modifier.height(16.dp))

                // ── 忌口与过敏 ──────────────────────────────
                SectionTitle("忌口与过敏")
                Spacer(Modifier.height(6.dp))
                Text("硬性排除：设置后推荐与三餐完全不再出现", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                ChipEditor(
                    items = allergens,
                    input = allergenInput,
                    suggestions = allergenSuggest.filter { it !in allergens },
                    onInput = { v ->
                        allergenInput = v
                        savedTip = false
                        suggestFor(v) { allergenSuggest = it }
                    },
                    onAdd = { name ->
                        if (name.isNotBlank() && name !in allergens) {
                            allergens = allergens + name.trim()
                            savedTip = false
                        }
                        allergenInput = ""
                        allergenSuggest = emptyList()
                    },
                    onRemove = { allergens = allergens - it; savedTip = false },
                    placeholder = "输入过敏/忌口食材，如：芒果、海鲜",
                )
                Spacer(Modifier.height(16.dp))

                // ── 健康状况 ──────────────────────────────
                SectionTitle("健康状况")
                Spacer(Modifier.height(6.dp))
                Text("常见病饮食禁忌；相关菜品详情页会显示提示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (conditionItems.isEmpty()) {
                    Text("加载中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        conditionItems.forEach { (key, label, avoid) ->
                            val selected = key in conditions
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    savedTip = false
                                    conditions = if (selected) conditions - key else conditions + key
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    conditions.forEach { key ->
                        conditionItems.firstOrNull { it.first == key }?.let { (_, label, avoid) ->
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$label 宜避免：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                Text(avoid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ── 菜系偏好 ──────────────────────────────
                SectionTitle("菜系偏好")
                Spacer(Modifier.height(6.dp))
                Text("多选；选中的菜系会在推荐中更常出现", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CUISINE_OPTIONS.forEach { c ->
                        val selected = c in cuisines
                        FilterChip(
                            selected = selected,
                            onClick = { savedTip = false; cuisines = if (selected) cuisines - c else cuisines + c },
                            label = { Text(c) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Button(onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (savedTip) "已保存 ✓" else "保存偏好")
                }
                if (savedTip) {
                    Spacer(Modifier.height(8.dp))
                    Text("推荐与三餐会立即按新偏好过滤", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

private fun stringList(o: JSONObject, key: String): List<String> {
    val arr = o.optJSONArray(key) ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) out.add(arr.optString(i))
    return out.filter { it.isNotEmpty() }
}

/** 输入框 + 已选标签 + 联想候选 的通用编辑器 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipEditor(
    items: List<String>,
    input: String,
    suggestions: List<String>,
    onInput: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    placeholder: String,
) {
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            trailingIcon = {
                if (input.isNotBlank()) {
                    Text("添加", Modifier
                        .clickable { onAdd(input) }
                        .padding(horizontal = 10.dp),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                suggestions.forEach { s ->
                    AssistChip(onClick = { onAdd(s) }, label = { Text(s) })
                }
            }
        }
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    Row(
                        Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            " ×",
                            Modifier.clickable { onRemove(item) },
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

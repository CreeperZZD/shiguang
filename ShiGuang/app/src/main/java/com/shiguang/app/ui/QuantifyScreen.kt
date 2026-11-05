package com.shiguang.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.shiguang.app.data.ApiException
import com.shiguang.app.data.Ingredient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** AI 量化结果（结构化菜谱）。荤素/难度/健康评分 LLM 可能不给，全部可空，展示时"有则显示" */
data class QuantifiedRecipe(
    val name: String,
    val intro: String,
    val category: String,
    val meatTag: String?,
    val difficulty: Int?,
    val minutes: Int,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val kcal: Int,
    val protein: Int,
    val fat: Int,
    val carb: Int,
    val healthScore: Int?,
)

/**
 * 量化请求专用客户端：Api 公共客户端 readTimeout 只有 30s，
 * 而 /api/ai/quantify-recipe 是真实 LLM 生成（实测 10~40s），必须放长超时。
 */
private val quantifyClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(150, TimeUnit.SECONDS)
    .build()

private val quantifyJson = "application/json; charset=utf-8".toMediaType()

/** POST /api/ai/quantify-recipe，返回 {ok:true, recipe:{...}}（兼容直接返回结构化对象的形态） */
private suspend fun quantifyRequest(text: String): JSONObject = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url(Api.BASE + "/ai/quantify-recipe")
        .post(JSONObject().put("text", text).toString().toRequestBody(quantifyJson))
    Api.token?.let { builder.header("Authorization", "Bearer $it") }
    quantifyClient.newCall(builder.build()).execute().use { resp ->
        val bodyText = resp.body?.string() ?: "{}"
        val json = try {
            JSONObject(bodyText)
        } catch (e: Exception) {
            JSONObject()
        }
        if (!resp.isSuccessful) {
            throw ApiException(resp.code, json.optString("detail", "请求失败(${resp.code})"))
        }
        if (json.optString("error").isNotEmpty()) {
            throw ApiException(resp.code, json.optString("detail", json.getString("error")))
        }
        json
    }
}

/* ---------- 宽容解析：服务端 LLM 输出以中文键为主（菜名/简介/分类/食材/步骤/时长分钟/营养/健康评分），同时兼容英文键 ---------- */

private fun JSONObject.qs(vararg keys: String): String {
    for (k in keys) {
        val v = optString(k, "")
        if (v.isNotBlank()) return v.trim()
    }
    return ""
}

private fun JSONObject.qi(vararg keys: String): Int? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val d = when (val v = opt(k)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: continue
            else -> continue
        }
        if (!d.isNaN() && !d.isInfinite()) return Math.round(d).toInt()
    }
    return null
}

private fun collectIngredients(arr: JSONArray, into: MutableList<Ingredient>) {
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is JSONObject -> {
                val name = v.optString("name", "").trim()
                if (name.isNotEmpty()) into.add(Ingredient(name, v.optString("amount", "").trim()))
            }
            is String -> if (v.isNotBlank()) into.add(Ingredient(v.trim(), ""))
        }
    }
}

private fun JSONObject.parseQuantifiedRecipe(): QuantifiedRecipe {
    val ing = mutableListOf<Ingredient>()
    for (key in listOf("食材", "ingredients")) {
        optJSONArray(key)?.let { collectIngredients(it, ing) }
        if (ing.isNotEmpty()) break
    }
    val steps = mutableListOf<String>()
    for (key in listOf("步骤", "steps")) {
        val arr = optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> if (v.isNotBlank()) steps.add(v.trim())
                is JSONObject -> {
                    val t = v.optString("text", v.optString("step", "")).trim()
                    if (t.isNotEmpty()) steps.add(t)
                }
            }
        }
        if (steps.isNotEmpty()) break
    }
    val nut = optJSONObject("营养") ?: optJSONObject("nutrition")
    fun fromNut(vararg keys: String): Int =
        if (nut == null) 0 else nut.qi(*keys) ?: 0
    return QuantifiedRecipe(
        name = qs("菜名", "name"),
        intro = qs("简介", "intro"),
        category = qs("分类", "category"),
        meatTag = qs("荤素", "meat_tag", "meatTag").ifBlank { null },
        difficulty = qi("难度", "difficulty"),
        minutes = qi("时长分钟", "minutes", "时长") ?: 0,
        ingredients = ing,
        steps = steps,
        kcal = fromNut("kcal", "热量"),
        protein = fromNut("protein", "蛋白质"),
        fat = fromNut("fat", "脂肪"),
        carb = fromNut("carb", "碳水"),
        healthScore = qi("健康评分", "health_score"),
    )
}

/** 错误文案兜底：LLM 解析失败时 detail 可能是"模型原始输出：…超长文本"，截断显示 */
private fun prettyError(e: Exception): String {
    val raw = e.message ?: "网络异常，请稍后重试"
    return if (raw.length > 160) raw.take(160) + "…" else raw
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuantifyScreen(onBack: () -> Unit, onPublished: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var quantifying by remember { mutableStateOf(false) }
    var quantErr by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<QuantifiedRecipe?>(null) }
    var nameEdit by remember { mutableStateOf("") }
    var introEdit by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var publishErr by remember { mutableStateOf<String?>(null) }
    var publishedName by remember { mutableStateOf<String?>(null) }

    fun startQuantify() {
        val text = input.trim()
        if (text.isEmpty() || quantifying || publishing) return
        scope.launch {
            quantifying = true
            quantErr = null
            result = null
            publishErr = null
            publishedName = null
            try {
                val resp = quantifyRequest(text)
                val recipeObj = resp.optJSONObject("recipe") ?: resp
                val q = recipeObj.parseQuantifiedRecipe()
                if (q.name.isBlank() || q.steps.isEmpty() || q.ingredients.isEmpty()) {
                    quantErr = "AI 返回的内容不完整（缺菜名、食材或步骤），请换个说法再试一次"
                } else {
                    result = q
                    nameEdit = q.name
                    introEdit = q.intro
                }
            } catch (e: Exception) {
                quantErr = prettyError(e)
            } finally {
                quantifying = false
            }
        }
    }

    fun publish() {
        val q = result ?: return
        if (quantifying || publishing) return
        val name = nameEdit.trim()
        when {
            name.isEmpty() -> publishErr = "菜名不能为空"
            name.length > 30 -> publishErr = "菜名不能超过 30 个字"
            q.kcal < 10 || q.kcal > 2000 -> publishErr = "热量需在 10~2000 kcal 之间，当前为 ${q.kcal}，无法发布"
            q.ingredients.isEmpty() || q.steps.isEmpty() -> publishErr = "食材或步骤为空，无法发布"
            else -> scope.launch {
                publishing = true
                publishErr = null
                try {
                    val body = JSONObject().apply {
                        put("name", name)
                        put("intro", introEdit.trim())
                        put("category", q.category.ifBlank { "家常小炒" })
                        // 服务端只认 "荤"/"素"，LLM 未给出时按素菜提交
                        put("meat_tag", if (q.meatTag == "荤") "荤" else "素")
                        put("difficulty", (q.difficulty ?: 1).coerceIn(1, 3))
                        put("minutes", if (q.minutes in 1..600) q.minutes else 30)
                        put(
                            "ingredients",
                            JSONArray().apply {
                                q.ingredients.forEach {
                                    put(JSONObject().put("name", it.name).put("amount", it.amount))
                                }
                            },
                        )
                        put("steps", JSONArray().apply { q.steps.forEach { put(it) } })
                        put(
                            "nutrition",
                            JSONObject()
                                .put("kcal", q.kcal)
                                .put("protein", q.protein)
                                .put("fat", q.fat)
                                .put("carb", q.carb),
                        )
                    }
                    Api.post("/recipes", body)
                    publishedName = name
                } catch (e: Exception) {
                    publishErr = prettyError(e)
                } finally {
                    publishing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("✨ AI 帮你规范菜谱") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
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
                "把任意做法粘贴进来（语音转文字、乱序口语都行），AI 帮你整理成标准菜谱。整理约需 10~40 秒，请耐心等待。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
                enabled = !quantifying && publishedName == null,
                placeholder = {
                    Text(
                        "比如：先炒鸡蛋盛出来，然后番茄炒出汁，倒回鸡蛋加点糖和盐…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
            )

            Button(
                onClick = { startQuantify() },
                enabled = input.isNotBlank() && !quantifying && !publishing && publishedName == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (quantifying) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  AI 整理中…")
                } else {
                    Text(if (result == null) "开始量化" else "重新量化")
                }
            }

            quantErr?.let { e ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        "量化失败：$e",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            result?.let { q ->
                ResultCard(
                    q = q,
                    nameEdit = nameEdit,
                    introEdit = introEdit,
                    onNameEdit = { nameEdit = it },
                    onIntroEdit = { introEdit = it },
                )

                publishErr?.let { e ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            "发布失败：$e",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                if (publishedName == null) {
                    Button(
                        onClick = { publish() },
                        enabled = !quantifying && !publishing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (publishing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("  发布中…")
                        } else {
                            Text("发布到菜谱库")
                        }
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🎉 发布成功！", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "《$publishedName》已进入菜谱库，可在菜谱页搜索查看。",
                                style = MaterialTheme.typography.bodyMedium,
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
private fun ResultCard(
    q: QuantifiedRecipe,
    nameEdit: String,
    introEdit: String,
    onNameEdit: (String) -> Unit,
    onIntroEdit: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("整理结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = nameEdit,
            onValueChange = onNameEdit,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("菜名") },
        )
        OutlinedTextField(
            value = introEdit,
            onValueChange = onIntroEdit,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            label = { Text("简介") },
        )

        val infoParts = buildList {
            add("分类：${q.category.ifBlank { "未知" }}")
            q.meatTag?.let { add("荤素：$it") }
            q.difficulty?.let { add("难度：" + "★".repeat(it.coerceIn(1, 3))) }
            if (q.minutes > 0) add("时长：${q.minutes} 分钟")
            q.healthScore?.let { add("健康评分：$it/10") }
        }
        Text(
            infoParts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("食材清单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (q.ingredients.isEmpty()) {
                    Text(
                        "（未识别到食材）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                q.ingredients.forEach { ing ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ing.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ing.amount,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text("做法步骤", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                q.steps.forEachIndexed { i, step ->
                    Row {
                        Text(
                            "${i + 1}. ",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Text("营养（每份）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
                NutritionCell("热量", "${q.kcal}", "kcal", Modifier.weight(1f))
                NutritionCell("蛋白质", "${q.protein}", "g", Modifier.weight(1f))
                NutritionCell("脂肪", "${q.fat}", "g", Modifier.weight(1f))
                NutritionCell("碳水", "${q.carb}", "g", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NutritionCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value $unit",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

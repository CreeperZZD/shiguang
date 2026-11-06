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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.DailyMeal
import com.shiguang.app.data.Recipe
import com.shiguang.app.data.WeekDayPlan
import com.shiguang.app.data.WeeklyPlan
import com.shiguang.app.data.toRecipe
import com.shiguang.app.data.toWeeklyPlan
import kotlinx.coroutines.launch

/** 一次换餐操作的上下文：哪天哪餐、当前菜 id、同天已占用的菜 id 集合（用于过滤候选） */
private class ReplaceCtx(
    val dayIndex: Int,
    val mealKey: String,
    val label: String,
    val currentId: Long,
    val usedIds: Set<Long>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyPlanScreen(target: Int?, onOpenRecipe: (Long) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<WeeklyPlan?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    /** 本地替换单餐：key = "天索引-餐key"（如 "0-lunch"），仅会话内有效，不落库 */
    val replacements = remember { mutableStateMapOf<String, Recipe>() }

    var replaceCtx by remember { mutableStateOf<ReplaceCtx?>(null) }
    var candidates by remember { mutableStateOf<List<Recipe>?>(null) }
    var candidatesErr by remember { mutableStateOf<String?>(null) }
    var loadingCandidates by remember { mutableStateOf(false) }
    var familyMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(target, familyMode) {
        try {
            val q = buildString {
                append("/recipes/weekly?target=")
                append(target ?: 2000)
                if (familyMode) append("&family=1")
            }
            plan = Api.get(q).toWeeklyPlan()
        } catch (e: Exception) {
            err = e.message
        }
    }

    /** 分页拉全部菜谱（每页 10 条，最多 5 页），过滤同天已用与当前菜后作为候选 */
    fun openReplaceDialog(dayIndex: Int, meal: DailyMeal) {
        val p = plan ?: return
        val day = p.days.getOrNull(dayIndex) ?: return
        val usedIds = buildSet {
            day.meals.forEach { m ->
                add(m.recipe.id)
                replacements["$dayIndex-${m.key}"]?.let { add(it.id) }
            }
        }
        val ctx = ReplaceCtx(dayIndex, meal.key, meal.label, meal.recipe.id, usedIds)
        replaceCtx = ctx
        candidates = null
        candidatesErr = null
        scope.launch {
            loadingCandidates = true
            try {
                val picked = mutableListOf<Recipe>()
                val seenIds = mutableSetOf<Long>()
                for (page in 1..5) {
                    val resp = Api.get("/recipes?page=$page&page_size=10")
                    val arr = resp.optJSONArray("items") ?: break
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i).toRecipe()
                        if (seenIds.add(r.id) && r.id != ctx.currentId && r.id !in ctx.usedIds) {
                            picked.add(r)
                        }
                    }
                    if (arr.length() == 0 || page >= resp.optInt("total_pages", 1)) break
                }
                candidates = picked
            } catch (e: Exception) {
                candidatesErr = e.message
            } finally {
                loadingCandidates = false
            }
        }
    }

    /** 当天总热量重算：被替换的餐用新菜谱热量，其余用服务端给的 kcal（已含份数） */
    fun dayTotalKcal(dayIndex: Int, day: WeekDayPlan): Int {
        var total = 0
        day.meals.forEach { m ->
            val rep = replacements["$dayIndex-${m.key}"]
            total += rep?.kcal ?: m.kcal
        }
        return total
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一周饮食计划") },
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
            plan == null -> Column(Modifier.padding(pad)) { LoadingBox() }
            else -> {
                val p = plan!!
                LazyColumn(
                    Modifier.padding(pad),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "共 7 天 · 每天 ≈ ${p.target} kcal · 点「换」替换单餐",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.FilterChip(
                                selected = familyMode,
                                onClick = { familyMode = !familyMode },
                                label = { Text("🍽 全家餐桌") },
                            )
                        }
                        p.breakdown?.let { parts ->
                            Text(
                                "全家目标：${parts.joinToString(" + ") { "${it.name} ${it.target}" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    items(p.days.size) { i ->
                        val d = p.days[i]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${d.date} ${d.weekday}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${dayTotalKcal(i, d)} kcal",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                d.meals.forEach { m ->
                                    val key = "$i-${m.key}"
                                    val rep = replacements[key]
                                    val repKcal = rep?.kcal
                                    val baseList = m.dishes
                                        ?: listOf(com.shiguang.app.data.DishItem(m.recipe, m.servings, m.kcal, if (m.servings <= 1.75) "中份" else "大份"))
                                    val dishList = if (rep != null) {
                                        baseList.mapIndexed { idx, d2 ->
                                            if (idx == 0) com.shiguang.app.data.DishItem(rep, m.servings, repKcal ?: d2.kcal, d2.servingLabel) else d2
                                        }
                                    } else {
                                        baseList
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AssistChip(onClick = {}, label = { Text(m.label) }, modifier = Modifier.padding(end = 10.dp))
                                        Text(
                                            "共 ${dishList.sumOf { it.kcal }} kcal",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = { openReplaceDialog(i, m) },
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                        ) { Text("换") }
                                    }
                                    dishList.forEachIndexed { idx, d2 ->
                                        val replaced = rep != null && idx == 0
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { onOpenRecipe(d2.recipe.id) }
                                                .padding(vertical = 6.dp, horizontal = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(d2.recipe.name, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    "${d2.recipe.minutes} 分钟${if (replaced) " · 已手动换" else ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (replaced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                "${d2.servingLabel} ${d2.kcal} kcal",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (d.drinks.isNotEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AssistChip(onClick = {}, label = { Text("饮品") }, modifier = Modifier.padding(end = 10.dp))
                                        Text(
                                            d.drinks.joinToString("、") { "${it.name} ${it.kcal} kcal" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
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

    replaceCtx?.let { ctx ->
        AlertDialog(
            onDismissRequest = { replaceCtx = null },
            title = { Text("换掉${ctx.label}") },
            text = {
                val list = candidates
                Column {
                    when {
                        loadingCandidates -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.height(0.dp))
                            Text(
                                "  正在找候选菜谱…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        candidatesErr != null -> Text(
                            "候选加载失败：$candidatesErr\n请关闭后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        list == null || list.isEmpty() -> Text(
                            "没有可替换的候选菜谱（其余菜谱与本餐或当天重复）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(
                            Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(list.size) { idx ->
                                val r = list[idx]
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            replacements["${ctx.dayIndex}-${ctx.mealKey}"] = r
                                            replaceCtx = null
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${r.kcal} kcal · ${r.minutes} 分钟",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text("选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { replaceCtx = null }) { Text("取消") }
            },
        )
    }
}

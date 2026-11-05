package com.shiguang.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.Api
import org.json.JSONObject

private data class ReportDay(val date: String, val totalKcal: Int, val withinRange: Boolean, val deviation: Int)

private data class Report(
    val target: Int,
    val days: List<ReportDay>,
    val avgKcal: Int,
    val onTargetDays: Int,
    val score: Int,
)

private fun parseReport(json: JSONObject): Report {
    val arr = json.optJSONArray("days")
    val days = mutableListOf<ReportDay>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            days.add(ReportDay(o.optString("date"), o.optInt("total_kcal"), o.optBoolean("withinRange"), o.optInt("deviation")))
        }
    }
    return Report(
        target = json.optInt("target"),
        days = days,
        avgKcal = json.optInt("avg_kcal"),
        onTargetDays = json.optInt("on_target_days"),
        score = json.optInt("score"),
    )
}

/** 一周热量报告：柱状曲线 + 贴合评分 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReportScreen(target: Int?, onBack: () -> Unit) {
    var report by remember { mutableStateOf<Report?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target) {
        try {
            report = parseReport(Api.get("/recipes/weekly-report?target=${target ?: 2000}"))
        } catch (e: Exception) {
            err = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_report), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("一周热量报告")
                    }
                },
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
            report == null -> Column(Modifier.padding(pad)) { LoadingBox() }
            else -> {
                val r = report!!
                LazyColumn(
                    Modifier.padding(pad),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${r.score} 分",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        r.score >= 85 -> MaterialTheme.colorScheme.secondary
                                        r.score >= 60 -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                )
                                Text(
                                    "计划贴合评分（${r.days.size} 天中 ${r.onTargetDays} 天贴合目标）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "日均 ${r.avgKcal} kcal / 目标 ${r.target} kcal",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("每日三餐总热量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                BarChart(r)
                                Text(
                                    "虚线 = 目标 ${r.target} kcal；陶土色柱表示贴合 ±15% 区间",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                    items(r.days.size) { i ->
                        val d = r.days[i]
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.date, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${if (d.deviation >= 0) "+" else ""}${d.deviation} kcal 相对目标",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    if (d.withinRange) "贴合" else "偏离",
                                    color = if (d.withinRange) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.padding(4.dp))
                                Text("${d.totalKcal}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 简易柱状图：Compose Canvas 绘制，无第三方依赖 */
@Composable
private fun BarChart(report: Report) {
    val maxVal = maxOf(report.days.maxOfOrNull { it.totalKcal } ?: 1, report.target) * 1.15f
    val barColor = MaterialTheme.colorScheme.primary
    val offColor = MaterialTheme.colorScheme.error
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chartHeight = 160.dp

    Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
        val n = report.days.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = slot * 0.5f
        val targetY = size.height * (1f - report.target / maxVal)
        val dash = 12f
        var x = 0f
        while (x < size.width) {
            drawLine(lineColor, Offset(x, targetY), Offset(x + dash / 2, targetY), strokeWidth = 3f)
            x += dash
        }
        report.days.forEachIndexed { i, d ->
            val h = (d.totalKcal / maxVal * size.height).coerceIn(4f, size.height)
            val left = i * slot + (slot - barW) / 2
            drawRoundRect(
                color = if (d.withinRange) barColor else offColor,
                topLeft = Offset(left, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(10f, 10f),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        report.days.forEach { d ->
            Text(
                text = d.date.substring(5),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

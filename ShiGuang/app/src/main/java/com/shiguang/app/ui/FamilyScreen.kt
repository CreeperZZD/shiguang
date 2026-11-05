package com.shiguang.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.Api
import com.shiguang.app.data.Session
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class Member(
    val id: Long,
    val name: String,
    val gender: String?,
    val age: Int?,
    val heightCm: Double?,
    val weightKg: Double?,
    val bodyFatPct: Double?,
    val activity: Double?,
    val goal: String?,
    val metrics: Map<String, Double?>,
    val method: String?,
)

private fun JSONObject.toMember(): Member {
    val m = optJSONObject("metrics") ?: JSONObject()
    fun mnum(key: String): Double? = if (m.isNull(key) || !m.has(key)) null else m.optDouble(key)
    return Member(
        id = getLong("id"),
        name = optString("name"),
        gender = if (isNull("gender")) null else optString("gender"),
        age = if (isNull("age")) null else optInt("age"),
        heightCm = if (isNull("height_cm")) null else optDouble("height_cm"),
        weightKg = if (isNull("weight_kg")) null else optDouble("weight_kg"),
        bodyFatPct = if (isNull("body_fat_pct")) null else optDouble("body_fat_pct"),
        activity = if (isNull("activity")) null else optDouble("activity"),
        goal = if (isNull("goal")) null else optString("goal"),
        metrics = mapOf(
            "bmi" to mnum("bmi"), "bmr" to mnum("bmr"), "tdee" to mnum("tdee"),
            "target_calories" to mnum("target_calories"),
        ),
        method = if (m.isNull("method")) null else m.optString("method"),
    )
}

private val ACTIVITIES = listOf("久坐" to 1.2, "轻度活动" to 1.375, "中度活动" to 1.55, "运动较多" to 1.725)
private val GOALS = listOf("减脂" to "lose", "维持" to "keep", "增肌" to "gain")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Member?>(null) } // null=关闭；Member=编辑；new Member(0)=新建
    var confirmDelete by remember { mutableStateOf<Member?>(null) }
    var currentId by remember { mutableStateOf(Session.currentMemberId) }

    fun reload() {
        scope.launch {
            loading = true
            try {
                val arr = Api.get("/family").optJSONArray("items") ?: JSONArray()
                members = (0 until arr.length()).map { arr.getJSONObject(it).toMember() }
                err = null
            } catch (e: Exception) {
                err = e.message
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_members), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("家庭成员")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FilledTonalButton(onClick = { editing = Member(0, "", null, null, null, null, null, null, null, emptyMap(), null) }) {
                Text("＋ 添加成员")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "全家人的健康档案。选填「体脂率」等实测数据可以算得更准；不填则用默认公式按每个人的身体计算。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (err != null) ErrorBox(err!!)
            if (loading) LoadingBox()
            members.forEach { m ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { editing = m },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.name + if (currentId == m.id) " · 当前使用" else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (currentId == m.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val arr = Api.get("/family").optJSONArray("items") ?: return@launch
                                        for (i in 0 until arr.length()) {
                                            val o = arr.getJSONObject(i)
                                            if (o.getLong("id") == m.id) {
                                                val t = o.optJSONObject("metrics")?.optDouble("target_calories")?.toInt()
                                                Session.currentMemberId = m.id
                                                Session.currentMemberName = m.name
                                                Session.currentMemberTarget = t
                                                currentId = m.id
                                                break
                                            }
                                        }
                                    } catch (e: Exception) { err = e.message }
                                }
                            }) { Text(if (currentId == m.id) "✓ 使用中" else "用TA的目标") }
                        }
                        val desc = buildString {
                            m.gender?.let { append(if (it == "m") "男" else "女") }
                            m.age?.let { append(" · ${it}岁") }
                            m.heightCm?.let { append(" · ${it.toInt()}cm") }
                            m.weightKg?.let { append(" · ${it.toInt()}kg") }
                        }
                        if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        m.metrics["target_calories"]?.let { t ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "每日目标 ${t.toInt()} kcal · ${if (m.method == "katch-mcardle") "按实测体脂率计算" else "按默认公式计算"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { confirmDelete = m }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            if (members.isEmpty() && !loading && err == null) {
                Text(
                    "还没有家庭成员，点右下角「＋ 添加成员」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { m ->
        MemberDialog(
            initial = m.takeIf { it.id > 0 },
            onDismiss = { editing = null },
            onSave = { body ->
                scope.launch {
                    try {
                        if (m.id > 0) Api.put("/family/${m.id}", body) else Api.post("/family", body)
                        editing = null
                        reload()
                    } catch (e: Exception) {
                        err = e.message
                    }
                }
            },
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除成员") },
            text = { Text("确定删除「${m.name}」的健康档案吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            if (Session.currentMemberId == m.id) Session.clearCurrentMember()
                            Api.delete("/family/${m.id}")
                            confirmDelete = null
                            reload()
                        } catch (e: Exception) { err = e.message }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberDialog(initial: Member?, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var gender by remember { mutableStateOf(initial?.gender ?: "m") }
    var age by remember { mutableStateOf(initial?.age?.toString() ?: "") }
    var height by remember { mutableStateOf(initial?.heightCm?.trimText() ?: "") }
    var weight by remember { mutableStateOf(initial?.weightKg?.trimText() ?: "") }
    var bodyFat by remember { mutableStateOf(initial?.bodyFatPct?.trimText() ?: "") }
    var activity by remember { mutableStateOf(initial?.activity ?: 1.375) }
    var goal by remember { mutableStateOf(initial?.goal ?: "keep") }
    var formErr by remember { mutableStateOf<String?>(null) }

    fun buildBody(): JSONObject? {
        val a = age.toIntOrNull()
        val h = height.toDoubleOrNull()
        val w = weight.toDoubleOrNull()
        val bf = bodyFat.toDoubleOrNull()
        if (name.isBlank()) { formErr = "请填写称呼"; return null }
        if (a == null) { formErr = "请填写年龄"; return null }
        if (h == null || w == null) { formErr = "请填写身高体重"; return null }
        val body = JSONObject()
            .put("name", name.trim())
            .put("gender", gender)
            .put("age", a)
            .put("height_cm", h)
            .put("weight_kg", w)
            .put("activity", activity)
            .put("goal", goal)
        if (bf != null) body.put("body_fat_pct", bf) // 权威实测数据，选填
        return body
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "＋ 添加成员" else "编辑成员") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("称呼（如：爸爸）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = gender == "m", onClick = { gender = "m" }, label = { Text("男") })
                    FilterChip(selected = gender == "f", onClick = { gender = "f" }, label = { Text("女") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(age, { age = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("年龄") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(height, { height = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("身高cm") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("体重kg") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    bodyFat,
                    { bodyFat = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                    label = { Text("体脂率%（选填，实测更准）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("活动水平", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ACTIVITIES.forEach { (label, f) ->
                        FilterChip(selected = activity == f, onClick = { activity = f }, label = { Text(label) }, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("目标", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GOALS.forEach { (label, g) ->
                        FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(label) })
                    }
                }
                formErr?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val body = buildBody() ?: return@TextButton
                onSave(body)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun Double?.trimText(): String = when {
    this == null -> ""
    this == this.toLong().toDouble() -> this.toLong().toString()
    else -> this.toString()
}

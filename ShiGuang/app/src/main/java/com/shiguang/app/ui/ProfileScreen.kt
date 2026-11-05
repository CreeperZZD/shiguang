package com.shiguang.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.parseMe
import kotlinx.coroutines.launch
import org.json.JSONObject

private val ACTIVITY_OPTIONS = listOf("久坐" to 1.2, "轻度活动" to 1.375, "中度活动" to 1.55, "运动较多" to 1.725)
private val GOAL_OPTIONS = listOf("减脂" to "lose", "维持健康" to "keep", "增肌" to "gain")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(initialMeJson: String?, onSaved: (String) -> Unit, canBack: Boolean = false, onBack: () -> Unit = {}) {
    val user = remember(initialMeJson) { initialMeJson?.let { parseMe(it) } }
    val scope = rememberCoroutineScope()

    var nickname by remember { mutableStateOf(user?.nickname?.takeIf { !it.startsWith("食光用户") } ?: "") }
    var gender by remember { mutableStateOf(user?.gender ?: "m") }
    var age by remember { mutableStateOf(user?.age?.toString() ?: "") }
    var height by remember { mutableStateOf(user?.heightCm?.trimString() ?: "") }
    var weight by remember { mutableStateOf(user?.weightKg?.trimString() ?: "") }
    var activity by remember { mutableStateOf(user?.activity ?: 1.55) }
    var goal by remember { mutableStateOf(user?.goal ?: "keep") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("健康档案") },
                navigationIcon = {
                    if (canBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { pad ->
    Column(
        modifier = Modifier
            .padding(pad)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("为你计算 BMI、基础代谢与每日热量目标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = nickname, onValueChange = { nickname = it.take(12) },
            label = { Text("昵称（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Text("性别", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = gender == "m", onClick = { gender = "m" }, label = { Text("男") })
            FilterChip(selected = gender == "f", onClick = { gender = "f" }, label = { Text("女") })
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = age, onValueChange = { age = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("年龄") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = height, onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                label = { Text("身高 cm") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = weight, onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
            label = { Text("体重 kg") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Text("日常活动水平", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ACTIVITY_OPTIONS.forEach { (label, factor) ->
                FilterChip(
                    selected = activity == factor,
                    onClick = { activity = factor },
                    label = { Text(label) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("目标", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GOAL_OPTIONS.forEach { (label, key) ->
                FilterChip(selected = goal == key, onClick = { goal = key }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    msg = null
                    val ageInt = age.toIntOrNull()
                    val h = height.toDoubleOrNull()
                    val w = weight.toDoubleOrNull()
                    if (ageInt == null || h == null || w == null || gender == null) {
                        msg = "请完整填写年龄、身高、体重"
                        return@launch
                    }
                    busy = true
                    try {
                        val body = JSONObject()
                            .putOpt("nickname", nickname.ifBlank { null })
                            .put("gender", gender)
                            .put("age", ageInt)
                            .put("height_cm", h)
                            .put("weight_kg", w)
                            .put("activity", activity)
                            .put("goal", goal)
                        val resp = Api.put("/me", body)
                        onSaved(resp.toString())
                    } catch (e: Exception) {
                        msg = e.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "保存中…" else "保存") }
        msg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(32.dp))
    }
    }
}

private fun Double?.trimString(): String = when {
    this == null -> ""
    this == this.toLong().toDouble() -> this.toLong().toString()
    else -> this.toString()
}

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.Api
import com.shiguang.app.data.Session
import com.shiguang.app.data.flexRecipeList
import com.shiguang.app.data.parseMe

@Composable
fun MineScreen(
    onEditProfile: () -> Unit,
    onLogout: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onOpenFamily: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenReport: () -> Unit = {},
) {
    var nickname by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var favs by remember { mutableStateOf<List<com.shiguang.app.data.Recipe>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val me = Api.get("/me")
            Session.saveMeJson(me.toString())
            parseMe(me.toString())?.let { u ->
                nickname = u.nickname
                metrics = listOf(
                    Triple("BMI", u.bmi?.toString() ?: "-", "身体质量指数"),
                    Triple("基础代谢", u.bmr?.toInt()?.toString() ?: "-", "kcal/天"),
                    Triple("每日消耗", u.tdee?.toInt()?.toString() ?: "-", "kcal/天"),
                    Triple("热量目标", u.targetCalories?.toInt()?.toString() ?: "-", "kcal/天"),
                )
            }
            favs = Api.get("/me/favorites").getJSONArray("items").flexRecipeList()
        } catch (e: Exception) {
            err = e.message
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_menu), contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(nickname.ifBlank { "食光用户" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    metrics.forEach { (title, value, sub) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) {
                        Text("编辑健康档案")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenFamily, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_members), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("家庭成员")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenReport, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_report), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("一周热量报告")
                    }
                }
            }
        }
        item {
            Text("我的收藏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (favs.isEmpty()) {
            item {
                Text(
                    "还没有收藏，去食谱页发现好菜吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(favs.size, key = { favs[it].id }) { i ->
                val r = favs[i]
                Card(Modifier.fillMaxWidth().clickable { onOpenRecipe(r.id) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${r.kcal} kcal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        }
        err?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

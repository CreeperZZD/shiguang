package com.shiguang.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.AppSettings
import com.shiguang.app.data.Session
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrefs: () -> Unit = {}) {
    val ctx = LocalContext.current
    var showTheme by remember { mutableStateOf(false) }
    // 自定义 AI 识别接口（游客/登录均可用，Key 只存本机）
    var aiBase by remember { mutableStateOf(Session.aiBaseUrl ?: "") }
    var aiKey by remember { mutableStateOf(Session.aiApiKey ?: "") }
    var aiModel by remember { mutableStateOf(Session.aiModel ?: "") }
    var aiSaved by remember { mutableStateOf(false) }

    fun openAppDetails() {
        runCatching {
            val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = android.net.Uri.fromParts("package", ctx.packageName, null)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
    fun permGranted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { showTheme = true }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_theme), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("深浅模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (AppSettings.themeMode) {
                            "light" -> "当前：浅色模式"
                            "dark" -> "当前：深色模式"
                            else -> "当前：跟随系统"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("切换 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("省流模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "不加载菜品图片，仅显示文字（弱网/省流量适用）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = AppSettings.dataSaver,
                    onCheckedChange = { AppSettings.setDataSaver(ctx, it) },
                )
            }
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("权限管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "权限被拒绝时对应功能不可用，会提示无权限；点「去设置」可随时打开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("相机（拍冰箱识食材）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (permGranted(android.Manifest.permission.CAMERA)) "已允许" else "未允许（拍照识别不可用）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { openAppDetails() }) { Text("去设置 ›") }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("通知（临期提醒）", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (permGranted(android.Manifest.permission.POST_NOTIFICATIONS)) "已允许" else "未允许（临期提醒不弹通知）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { openAppDetails() }) { Text("去设置 ›") }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("AI 识别接口", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "拍冰箱识食材默认使用官方服务器（需登录）。有自己 API 的用户可在此接入（OpenAI 兼容的视觉接口，Key 只保存在本机）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aiBase,
                onValueChange = { aiBase = it.trim(); aiSaved = false },
                label = { Text("BASE URL，如 https://dashscope.aliyuncs.com/compatible-mode/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = aiKey,
                onValueChange = { aiKey = it.trim(); aiSaved = false },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = aiModel,
                onValueChange = { aiModel = it.trim(); aiSaved = false },
                label = { Text("模型名，如 qwen-vl-max / gpt-4o-mini") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    Session.aiBaseUrl = aiBase.ifBlank { null }
                    Session.aiApiKey = aiKey.ifBlank { null }
                    Session.aiModel = aiModel.ifBlank { null }
                    aiSaved = true
                }) { Text(if (aiSaved) "已保存 ✓" else "保存") }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = {
                    aiBase = ""; aiKey = ""; aiModel = ""
                    Session.aiBaseUrl = null; Session.aiApiKey = null; Session.aiModel = null
                    aiSaved = false
                }) { Text("恢复使用官方服务") }
            }
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("饮食偏好", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onOpenPrefs() }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_wand), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("饮食偏好设置", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "不喜欢吃、忌口过敏、常见病禁忌与菜系偏好",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("编辑 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            val versionName = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?"
            Text("食光 v$versionName", style = MaterialTheme.typography.bodyLarge)
            Text(
                "开源项目 · 图片来源 Wikimedia Commons（CC 协议）与 pollinations.ai",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("深浅模式") },
            text = {
                Column {
                    listOf(
                        Triple("system", "跟随系统", "默认"),
                        Triple("light", "浅色模式", "奶油白 × 陶土橘"),
                        Triple("dark", "深色模式", "夜间友好"),
                    ).forEach { (key, label, sub) ->
                        Text(
                            (if (AppSettings.themeMode == key) "● " else "○ ") + label + "　" + sub,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppSettings.setThemeMode(ctx, key)
                                    showTheme = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTheme = false }) { Text("关闭") }
            },
        )
    }
}

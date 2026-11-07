package com.shiguang.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.Session
import com.shiguang.app.data.parseMe
import kotlinx.coroutines.launch
import org.json.JSONObject

private val EMAIL_RE = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/** 登录/注册：手机号验证码（服务器测试模式）或邮箱验证码；也可「先逛逛」游客浏览 */
@Composable
fun LoginScreen(onLoginDone: (profileComplete: Boolean, meJson: String?) -> Unit, onGuest: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf("phone") } // phone | email | password
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgGood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun done(token: String) {
        scope.launch {
            try {
                Session.token = token
                Api.token = token
                val me = Api.get("/me")
                Session.saveMeJson(me.toString())
                val complete = parseMe(me.toString())?.bmi != null
                onLoginDone(complete, me.toString())
            } catch (e: Exception) {
                // 登录成功但拉档案失败：仍进入主界面（离线兜底）
                Session.saveMeJson("")
                onLoginDone(false, null)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "食光",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("好好吃饭的时光", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { mode = "phone"; msg = null }) {
                    Text(if (mode == "phone") "验证码 ✓" else "验证码", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { mode = "email"; msg = null }) {
                    Text(if (mode == "email") "邮箱 ✓" else "邮箱", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { mode = "password"; msg = null }) {
                    Text(if (mode == "password") "密码 ✓" else "密码", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(16.dp))

            when (mode) {
                "phone" -> OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                    label = { Text("手机号") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                "email" -> OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("邮箱") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> OutlinedTextField(
                    value = if (phone.isNotEmpty()) phone else email,
                    onValueChange = { v ->
                        if (v.isEmpty()) { phone = ""; email = "" }
                        else if (v.all { c -> c.isDigit() } && v.length <= 11) phone = v
                        else email = v.trim()
                    },
                    label = { Text("手机号或邮箱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (mode == "password") {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("验证码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            msg = null
                            busy = true
                            try {
                                val r = if (mode == "phone") {
                                    if (phone.length != 11) { msg = "请输入 11 位手机号"; msgGood = false; busy = false; return@launch }
                                    Api.post("/auth/request-code", JSONObject().put("phone", phone), auth = false)
                                } else {
                                    if (!EMAIL_RE.matches(email)) { msg = "邮箱格式不正确"; msgGood = false; busy = false; return@launch }
                                    Api.post("/auth/email-code", JSONObject().put("email", email), auth = false)
                                }
                                val dev = r.optString("dev_code")
                                if (dev.isNotEmpty()) {
                                    code = dev
                                    msg = "测试模式：验证码已自动填入"
                                } else {
                                    msg = "验证码已发送，请查收"
                                }
                                msgGood = true
                            } catch (e: Exception) {
                                msg = e.message ?: "请求异常"; msgGood = false
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && (if (mode == "phone") phone.length == 11 else email.contains('@')),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("获取验证码") }
            }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        msg = null; busy = true
                        try {
                            val r = when (mode) {
                                "phone" -> Api.post("/auth/login", JSONObject().put("phone", phone).put("code", code), auth = false)
                                "email" -> Api.post("/auth/email-login", JSONObject().put("email", email).put("code", code), auth = false)
                                else -> {
                                    val acc = if (phone.isNotEmpty()) phone else email
                                    Api.post("/auth/password-login", JSONObject().put("account", acc).put("password", password), auth = false)
                                }
                            }
                            done(r.getString("token"))
                        } catch (e: Exception) {
                            msg = e.message ?: "登录失败"; msgGood = false
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && (if (mode == "password") password.isNotEmpty() else code.isNotEmpty()) && (if (mode == "phone") phone.isNotEmpty() else if (mode == "email") email.isNotEmpty() else (phone.isNotEmpty() || email.isNotEmpty())),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(if (busy) "登录中…" else "登录 / 注册", style = MaterialTheme.typography.titleMedium)
            }
            msg?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (msgGood) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGuest) { Text("先逛逛（游客模式，不登录也能看） ›") }
            Spacer(Modifier.height(12.dp))
            Text(
                "游客数据仅保存在本机 · 登录后可云同步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

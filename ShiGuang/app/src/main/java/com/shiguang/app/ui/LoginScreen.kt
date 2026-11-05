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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

@Composable
fun LoginScreen(onLoginDone: (profileComplete: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgGood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

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
            Spacer(Modifier.height(36.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                label = { Text("手机号") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
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
                            if (phone.length != 11) {
                                msg = "请输入 11 位手机号"; msgGood = false
                                return@launch
                            }
                            busy = true
                            try {
                                val r = Api.post("/auth/request-code", JSONObject().put("phone", phone), auth = false)
                                val dev = r.optString("dev_code")
                                if (dev.isNotEmpty()) {
                                    code = dev
                                    msg = "测试模式：验证码已自动填入"
                                } else {
                                    msg = "验证码已发送"
                                }
                                msgGood = true
                            } catch (e: Exception) {
                                msg = e.message ?: (e.javaClass.simpleName + " 请求异常"); msgGood = false
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && phone.length == 11,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("获取验证码") }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        msg = null; busy = true
                        try {
                            val r = Api.post(
                                "/auth/login",
                                JSONObject().put("phone", phone).put("code", code),
                                auth = false,
                            )
                            Session.token = r.getString("token")
                            Api.token = Session.token
                            val me = Api.get("/me")
                            Session.saveMeJson(me.toString())
                            val complete = parseMe(me.toString())?.bmi != null
                            onLoginDone(complete)
                        } catch (e: Exception) {
                            msg = e.message ?: "登录失败"; msgGood = false
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && phone.isNotEmpty() && code.isNotEmpty(),
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
        }
        Text(
            "测试阶段验证码固定为 888888",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}

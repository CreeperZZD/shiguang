package com.shiguang.admin

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 食光审核台（开发者专用 APP）：
 *  1. 后台统计：用户/菜谱/帖子/日记/待审照片/在线会话（只读、非隐私聚合数据）
 *  2. 流程图审核：通过 / 拒绝用户上传的菜谱作品图
 * 安全性：管理员令牌（X-Admin-Token）由服务器 secrets/admin.json 签发，仅发给开发者本人。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AdminApp()
            }
        }
    }
}

private const val PREF = "shiguang_admin"

private fun tokenStore(ctx: Context): android.content.SharedPreferences =
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

private data class PhotoItem(
    val id: Long,
    val recipeId: Long,
    val recipeName: String,
    val nickname: String,
    val dataUrl: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var token by rememberSaveable { mutableStateOf(tokenStore(ctx).getString("token", "") ?: "") }
    var host by rememberSaveable { mutableStateOf(tokenStore(ctx).getString("host", "http://118.178.99.65:8000") ?: "http://118.178.99.65:8000") }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    var stats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val media = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder().url(host.trimEnd('/') + path)
            .header("X-Admin-Token", token)
            .apply {
                when (method) {
                    "POST" -> post((body ?: JSONObject()).toString().toRequestBody(media))
                    else -> get()
                }
            }
            .build()
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val json = JSONObject(text)
            if (!resp.isSuccessful) throw IllegalStateException(json.optString("detail", "请求失败(${resp.code})"))
            return json
        }
    }

    fun loadStats() {
        scope.launch {
            loading = true
            err = null
            try {
                val s = withContext(Dispatchers.IO) { call("/api/admin/stats") }
                stats = listOf(
                    "注册用户" to s.optString("users"),
                    "官方菜谱" to s.optString("recipes"),
                    "用户菜谱" to s.optString("user_recipes"),
                    "帖子" to s.optString("posts"),
                    "评论" to s.optString("comments"),
                    "日记条目" to s.optString("diary_entries"),
                    "待审照片" to s.optString("photos_pending"),
                    "已过照片" to s.optString("photos_approved"),
                    "在线会话" to s.optString("sessions_active"),
                )
            } catch (e: Exception) {
                err = e.message
            }
            loading = false
        }
    }

    fun loadPhotos() {
        scope.launch {
            loading = true
            err = null
            try {
                val s = withContext(Dispatchers.IO) { call("/api/admin/photos?status=pending&limit=30") }
                val arr = s.optJSONArray("items") ?: org.json.JSONArray()
                photos = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    PhotoItem(
                        id = o.getLong("id"),
                        recipeId = o.optLong("recipe_id"),
                        recipeName = o.optString("recipe_name"),
                        nickname = o.optString("nickname"),
                        dataUrl = if (o.isNull("data_url")) null else o.optString("data_url"),
                    )
                }
            } catch (e: Exception) {
                err = e.message
            }
            loading = false
        }
    }

    fun review(id: Long, action: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    call("/api/admin/photos/$id/review", "POST", JSONObject().put("action", action))
                }
                photos = photos.filterNot { it.id == id }
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("食光审核台") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (token.isBlank()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("管理员登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("令牌由服务器 /opt/recipe-app/secrets/admin.json 签发", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = host, onValueChange = { host = it.trim() }, label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        label = { Text("管理员令牌") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        tokenStore(ctx).edit().putString("token", token).putString("host", host).apply()
                        loadStats()
                    }, enabled = token.isNotBlank()) { Text("进入审核台") }
                    err?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                }
                return@Column
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; if (stats.isEmpty()) loadStats() }, text = { Text("后台统计") })
                Tab(selected = tab == 1, onClick = { tab = 1; if (photos.isEmpty()) loadPhotos() }, text = { Text("待审照片 ${photos.size}") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { if (tab == 0) loadStats() else loadPhotos() }) { Text("刷新") }
                TextButton(onClick = { tokenStore(ctx).edit().remove("token").apply(); token = "" }) { Text("退出") }
            }
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
            }
            if (tab == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(stats) { (title, value) ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                if (photos.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(if (loading) "加载中…" else "没有待审核的照片")
                    }
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(photos.size, key = { photos[it].id }) { i ->
                            val p = photos[i]
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${p.recipeName} · ${p.nickname}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(8.dp))
                                    p.dataUrl?.let {
                                        AsyncImage(
                                            model = it,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1.2f),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { review(p.id, "approve") }) { Text("通过") }
                                        FilledTonalButton(onClick = { review(p.id, "reject") }) { Text("拒绝", color = Color.White) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

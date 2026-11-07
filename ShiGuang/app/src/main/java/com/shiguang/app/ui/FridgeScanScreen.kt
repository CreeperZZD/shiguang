package com.shiguang.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.shiguang.app.data.Api
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 拍冰箱识别食材：拍照 / 相册多选（最多4张）→ AI 识别 → 确认后一键入食材柜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridgeScanScreen(onDone: (Int) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var recognized by remember { mutableStateOf<List<String>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var noCamPerm by remember { mutableStateOf(false) }
    var renameFrom by remember { mutableStateOf<String?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var manualAdd by remember { mutableStateOf("") }

    // 拍照：先建临时文件，TakePicture 成功后加入列表
    val pendingCameraUri = remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri.value
        if (ok && uri != null && images.size < 4) images = images + uri
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) images = (images + uris).distinct().take(4)
    }

    fun shoot() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            noCamPerm = true
            msg = "还没有相机权限，无法拍照；可先用相册选图，或点下方「去设置」开启"
            return
        }
        val dir = File(ctx.cacheDir, "scan").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        pendingCameraUri.value = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { msg = "无法打开相机：${it.message}" }
    }

    fun recognize() {
        if (images.isEmpty() || scanning) return
        scope.launch {
            scanning = true
            msg = null
            try {
                val b64s = withContext(Dispatchers.IO) {
                    images.mapNotNull { compressToBase64(ctx, it) }
                }
                if (b64s.isEmpty()) throw IllegalStateException("图片读取失败")
                val customBase = com.shiguang.app.data.Session.aiBaseUrl
                val customKey = com.shiguang.app.data.Session.aiApiKey
                val names: List<String> = if (!customBase.isNullOrBlank() && !customKey.isNullOrBlank()) {
                    // 用户自带 OpenAI 兼容视觉接口，手机直连
                    callCustomVisionApi(customBase.trimEnd('/'), customKey, com.shiguang.app.data.Session.aiModel ?: "qwen-vl-max", b64s)
                } else {
                    if (Api.token == null) throw IllegalStateException("游客模式请先在「设置 → AI 识别接口」配置自己的 API，或登录后使用官方服务")
                    val resp = Api.post(
                        "/ai/recognize-ingredients",
                        JSONObject().put("images", JSONArray(b64s)),
                    )
                    val arr = resp.optJSONArray("ingredients") ?: JSONArray()
                    (0 until arr.length()).map { arr.getString(it) }
                }
                recognized = names
                picked = names.toSet()
            } catch (e: Exception) {
                msg = e.message ?: "识别失败，请重试"
            } finally {
                scanning = false
            }
        }
    }

    fun confirm() {
        val list = recognized ?: return
        val chosen = list.filter { it in picked }
        if (chosen.isEmpty()) return
        scope.launch {
            try {
                // 合并进食材柜（读当前 → 并集 → 全量保存）
                val cur = Api.get("/me/ingredients").optJSONArray("names") ?: JSONArray()
                val merged = (0 until cur.length()).map { cur.getString(it) }.toMutableSet()
                merged.addAll(chosen)
                Api.put("/me/ingredients", JSONObject().put("names", JSONArray(merged.toList())))
                onDone(chosen.size)
            } catch (e: Exception) {
                msg = e.message ?: "保存失败"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍冰箱识食材") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "给冰箱或厨房拍 1~4 张照片，AI 帮你认出里面有什么食材",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { shoot() }, enabled = images.size < 4, modifier = Modifier.weight(1f)) {
                    Text("拍照")
                }
                FilledTonalButton(
                    onClick = {
                        runCatching {
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.onFailure { msg = "无法打开相册：${it.message}" }
                    },
                    enabled = images.size < 4,
                    modifier = Modifier.weight(1f),
                ) { Text("🖼 相册（多选）") }
            }

            if (images.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height((images.size / 4 * 92 + 100).dp),
                ) {
                    items(images.size) { i ->
                        AsyncImage(
                            model = images[i],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.medium),
                        )
                    }
                }
            }

            Button(
                onClick = { recognize() },
                enabled = images.isNotEmpty() && !scanning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (scanning) "AI 正在看图…（10~30 秒）" else "开始识别")
            }

            recognized?.let { list ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("识别结果（点一下取消选择 · 点「改」可改名）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (list.isEmpty()) {
                            Text("没认出食材，试试光线充足再拍一张，或在下面手动添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            // FlowRow：chip 按内容自适应宽度，不再被固定列压成竖排
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                list.forEach { name ->
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        AssistChip(
                                            onClick = {
                                                picked = if (name in picked) picked - name else picked + name
                                            },
                                            label = { Text(if (name in picked) "✓ $name" else name) },
                                            modifier = Modifier.padding(2.dp),
                                        )
                                        Text(
                                            "改",
                                            Modifier
                                                .clickable { renameFrom = name; renameTo = name }
                                                .padding(horizontal = 6.dp, vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 手动补充 AI 没认出的食材
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manualAdd,
                                onValueChange = { manualAdd = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("AI 没认出的？手动补充", style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val n = manualAdd.trim()
                                    if (n.isNotEmpty() && n !in list) {
                                        recognized = list + n
                                        picked = picked + n
                                    }
                                    manualAdd = ""
                                },
                                enabled = manualAdd.trim().isNotEmpty(),
                            ) { Text("添加") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { confirm() },
                            enabled = picked.isNotEmpty() && !scanning,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("全部加入食材柜（${picked.size} 项）") }
                    }
                }
            }

            msg?.let {
                OutlinedButton(onClick = { msg = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            renameFrom?.let { from ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { renameFrom = null },
                    title = { Text("改个名") },
                    text = {
                        OutlinedTextField(
                            value = renameTo,
                            onValueChange = { renameTo = it },
                            singleLine = true,
                            label = { Text("食材名") },
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val to = renameTo.trim()
                            if (to.isNotEmpty() && to != from) {
                                recognized = recognized!!.map { if (it == from) to else it }
                                picked = if (from in picked) (picked - from) + to else picked
                            }
                            renameFrom = null
                        }, enabled = renameTo.trim().isNotEmpty()) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { renameFrom = null }) { Text("取消") }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

internal fun compressToBase64(context: android.content.Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    var maxSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxSide / sample > 1280) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
    bmp.recycle()
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

/** 直连用户自配的 OpenAI 兼容视觉接口（设置 → AI 识别接口），返回食材名列表 */
private fun callCustomVisionApi(baseUrl: String, apiKey: String, model: String, b64s: List<String>): List<String> {
    val prompt = "这些是用户冰箱/厨房的照片。识别所有照片中出现的可食用食材，去重合并为一个清单；" +
        "包装食品给出通用食材名（如\"牛奶\"），不要输出品牌、厨具、餐具。" +
        "只输出 JSON 字符串数组，例如 [\"番茄\",\"鸡蛋\"]，不要输出任何其他内容。"
    val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
    for (b in b64s) {
        content.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b")),
        )
    }
    val body = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        .put("max_tokens", 2048)
    val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val req = okhttp3.Request.Builder()
        .url(baseUrl + "/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .post(body.toString().toRequestBody(jsonType))
        .build()
    client.newCall(req).execute().use { resp ->
        val text = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) throw IllegalStateException("自定义 API 返回 ${resp.code}：${text.take(200)}")
        val msgContent = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
        val s = msgContent.indexOf('[')
        val e = msgContent.lastIndexOf(']')
        if (s < 0 || e <= s) throw IllegalStateException("识别结果解析失败：${msgContent.take(120)}")
        val arr = JSONArray(msgContent.substring(s, e + 1))
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out.filter { it.isNotBlank() }.distinct()
    }
}

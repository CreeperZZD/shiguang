package com.shiguang.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
                val resp = Api.post(
                    "/ai/recognize-ingredients",
                    JSONObject().put("images", JSONArray(b64s)),
                )
                val arr = resp.optJSONArray("ingredients") ?: JSONArray()
                recognized = (0 until arr.length()).map { arr.getString(it) }
                picked = recognized!!.toSet()
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
                title = { Text("📷 拍冰箱识食材") },
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
                    Text("📷 拍照")
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
                        Text("识别结果（点一下取消选择）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (list.isEmpty()) {
                            Text("没认出食材，试试光线充足再拍一张", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row {
                                Column {
                                    list.chunked(4).forEach { rowItems ->
                                        Row {
                                            rowItems.forEach { name ->
                                                AssistChip(
                                                    onClick = {
                                                        picked = if (name in picked) picked - name else picked + name
                                                    },
                                                    label = { Text(if (name in picked) "✓ $name" else name) },
                                                    modifier = Modifier.padding(2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun compressToBase64(context: android.content.Context, uri: Uri): String? = runCatching {
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

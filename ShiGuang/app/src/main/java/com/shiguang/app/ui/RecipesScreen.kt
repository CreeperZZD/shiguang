package com.shiguang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.Api
import com.shiguang.app.data.Recipe
import com.shiguang.app.data.Session
import com.shiguang.app.data.toRecipe
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

@Composable
fun RecipesScreen(onOpenRecipe: (Long) -> Unit, onOpenLeaderboard: () -> Unit = {}, onOpenSquare: () -> Unit = {}, onOpenQuantify: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Recipe>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun load(p: Int, q: String) {
        scope.launch {
            loading = true; err = null
            try {
                val r = if (q.isBlank()) {
                    Api.get("/recipes?page=$p")
                } else {
                    Api.get("/recipes?page=$p&q=${URLEncoder.encode(q, "UTF-8")}")
                }
                val list = r.getJSONArray("items").let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).toRecipe() }
                }
                items = if (p == 1) list else items + list
                total = r.optInt("total", items.size)
                page = p
            } catch (e: Exception) {
                err = e.message
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load(1, "") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.FilledTonalButton(
                onClick = onOpenLeaderboard,
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("排行榜")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = onOpenSquare,
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_chat), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("广场")
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = onOpenQuantify,
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_wand), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("AI量化")
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜菜名 / 食材") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { load(1, query) },
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { load(1, query) }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
        }
        if (err != null) {
            ErrorBox(err!!)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items.size, key = { items[it].id }) { i ->
                    val r = items[i]
                    Card(Modifier.fillMaxWidth().clickable { onOpenRecipe(r.id) }) {
                        Row {
                            DishImage(
                                r.name,
                                coverUrl = r.coverUrl,
                                modifier = Modifier
                                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                                    .size(width = 96.dp, height = 88.dp)
                                    .clip(MaterialTheme.shapes.medium),
                            )
                            Column(
                                Modifier.padding(14.dp).weight(1f),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${r.kcal} kcal",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${r.category} · ${r.meatTag} · ${r.minutes} 分钟",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatIcon(com.shiguang.app.R.drawable.ic_like, r.likeCount)
                                    Spacer(Modifier.width(10.dp))
                                    StatIcon(com.shiguang.app.R.drawable.ic_heart, r.favoriteCount)
                                    Spacer(Modifier.width(10.dp))
                                    StatIcon(com.shiguang.app.R.drawable.ic_eye, r.views)
                                }
                            }
                        }
                    }
                }
                if (items.size < total) {
                    item {
                        FilledTonalButton(
                            onClick = { load(page + 1, query) },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "加载中…" else "加载更多") }
                    }
                }
                if (items.isEmpty() && !loading && err == null) {
                    item {
                        Text(
                            "没有找到相关菜谱",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(id: Long, onBack: () -> Unit, onOpenVideos: (Long) -> Unit = {}) {
    var r by remember { mutableStateOf<Recipe?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var liked by remember { mutableStateOf(false) }
    var favd by remember { mutableStateOf(false) }
    var countDownMinutes by remember { mutableStateOf<Int?>(null) }
    var photos by remember { mutableStateOf<List<Triple<Long, String, String>>>(emptyList()) } // id,url,nickname
    var uploadingPhoto by remember { mutableStateOf(false) }
    var photoTip by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val pickPhotoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploadingPhoto = true
            photoTip = null
            try {
                val b64 = withContext(kotlinx.coroutines.Dispatchers.IO) { compressToBase64(ctx, uri) }
                    ?: throw IllegalStateException("图片读取失败")
                val resp = Api.post("/recipes/$id/photos", org.json.JSONObject().put("image_base64", "data:image/jpeg;base64," + b64))
                photoTip = resp.optString("message", "已提交，等待审核")
            } catch (e: Exception) {
                photoTip = e.message ?: "上传失败"
            } finally {
                uploadingPhoto = false
            }
        }
    }

    fun loadPhotos() {
        scope.launch {
            runCatching {
                val arr = Api.get("/recipes/$id/photos", auth = false).optJSONArray("items") ?: org.json.JSONArray()
                photos = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Triple(o.getLong("id"), o.getString("url"), o.optString("nickname"))
                }
            }
        }
    }

    LaunchedEffect(id) {
        loadPhotos()
        try {
            val item = Api.get("/recipes/$id").getJSONObject("item").toRecipe()
            r = item
            // 服务端为收藏/点赞状态的最终事实；本地集合仅作离线兜底缓存
            liked = item.liked || Session.likedIds().contains(id.toString())
            favd = item.favorited || Session.favIds().contains(id.toString())
        } catch (e: Exception) {
            err = e.message
        }
    }

    fun toggle(kind: String) {
        val recipe = r ?: return
        if (Api.token == null) {
            // 游客模式：只记在本机，不与服务器交互
            if (kind == "like") {
                liked = !liked
                r = recipe.copy(likeCount = recipe.likeCount + if (liked) 1 else -1, liked = liked)
                val s = Session.likedIds()
                if (liked) s.add(id.toString()) else s.remove(id.toString())
                Session.setLiked(s)
            } else {
                favd = !favd
                r = recipe.copy(favoriteCount = recipe.favoriteCount + if (favd) 1 else -1, favorited = favd)
                val s = Session.favIds()
                if (favd) s.add(id.toString()) else s.remove(id.toString())
                Session.setFavs(s)
            }
            return
        }
        scope.launch {
            try {
                if (kind == "like") {
                    val resp = if (liked) Api.delete("/recipes/$id/like") else Api.post("/recipes/$id/like")
                    val newLiked = resp.optBoolean("liked", !liked)
                    val count = resp.optInt("like_count", -1)
                    r = recipe.copy(
                        likeCount = if (count >= 0) count else recipe.likeCount + if (newLiked) 1 else -1,
                        liked = newLiked,
                    )
                    val s = Session.likedIds()
                    if (newLiked) s.add(id.toString()) else s.remove(id.toString())
                    Session.setLiked(s)
                    liked = newLiked
                } else {
                    val resp = if (favd) Api.delete("/recipes/$id/favorite") else Api.post("/recipes/$id/favorite")
                    val newFav = resp.optBoolean("favorited", !favd)
                    val count = resp.optInt("favorite_count", -1)
                    r = recipe.copy(
                        favoriteCount = if (count >= 0) count else recipe.favoriteCount + if (newFav) 1 else -1,
                        favorited = newFav,
                    )
                    val s = Session.favIds()
                    if (newFav) s.add(id.toString()) else s.remove(id.toString())
                    Session.setFavs(s)
                    favd = newFav
                }
            } catch (e: Exception) {
                err = e.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.name ?: "菜谱详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        countDownMinutes?.let { mins ->
            CountDownDialog(minutes = mins, onDismiss = { countDownMinutes = null })
        }
        val recipe = r
        when {
            err != null -> Column(Modifier.padding(pad)) { ErrorBox(err!!) }
            recipe == null -> Column(Modifier.padding(pad)) { LoadingBox() }
            else -> LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DishImage(
                        recipe.name,
                        coverUrl = recipe.coverUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.large),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(recipe.category) })
                        AssistChip(onClick = {}, label = { Text(recipe.meatTag) })
                        AssistChip(onClick = {}, label = { Text("${recipe.minutes} 分钟") })
                        AssistChip(onClick = {}, label = { Text("★".repeat(recipe.difficulty)) })
                    }
                }
                if (recipe.warnings.isNotEmpty()) {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.ic_bell), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(6.dp))
                                    Text("饮食提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                Spacer(Modifier.height(4.dp))
                                recipe.warnings.forEach { w ->
                                    Text("· $w", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 2.dp))
                                }
                                Text(
                                    "以上内容根据你的饮食偏好自动生成",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
                            Metric("热量", "${recipe.kcal}", "kcal", Modifier.weight(1f))
                            Metric("蛋白质", "${recipe.protein}", "g", Modifier.weight(1f))
                            Metric("脂肪", "${recipe.fat}", "g", Modifier.weight(1f))
                            Metric("碳水", "${recipe.carb}", "g", Modifier.weight(1f))
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { toggle("like") }, modifier = Modifier.weight(1f)) {
                            Icon(
                                androidx.compose.ui.res.painterResource(com.shiguang.app.R.drawable.ic_like),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (liked) "已赞 ${recipe.likeCount}" else "点赞 ${recipe.likeCount}")
                        }
                        FilledTonalButton(onClick = { toggle("fav") }, modifier = Modifier.weight(1f)) {
                            Icon(
                                androidx.compose.ui.res.painterResource(
                                    if (favd) com.shiguang.app.R.drawable.ic_heart else com.shiguang.app.R.drawable.ic_heart_outline
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (favd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (favd) "已收藏 ${recipe.favoriteCount}" else "收藏 ${recipe.favoriteCount}")
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("视频教程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { LinkUtils.openDouyinSearch(ctx, recipe.name) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(painterResource(R.drawable.ic_music), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("抖音搜教程")
                                }
                                FilledTonalButton(
                                    onClick = { LinkUtils.openBilibiliSearch(ctx, recipe.name) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(painterResource(R.drawable.ic_tv), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("B站搜索")
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            FilledTonalButton(
                                onClick = { onOpenVideos(id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("看B站精选视频 ▶") }
                        }
                    }
                }
                // 大家的作品：审核通过的用户上传图 + 上传入口
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("大家的作品", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            if (photos.isEmpty()) {
                                Text(
                                    "还没有人晒作品，来当第一个吧",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    photos.forEach { (pid, url, nick) ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            coil.compose.AsyncImage(
                                                model = com.shiguang.app.data.Api.BASE.substringBefore("/api") + url,
                                                contentDescription = "作品图",
                                                modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.medium),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(nick, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            photoTip?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    if (Api.token == null) {
                                        photoTip = "游客模式不能上传，请先登录"
                                        return@FilledTonalButton
                                    }
                                    pickPhotoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                enabled = !uploadingPhoto,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (uploadingPhoto) "上传中…" else "上传我的作品图（审核后展示）") }
                        }
                    }
                }
                if (recipe.intro.isNotEmpty()) {
                    item { Text(recipe.intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    Text("食材清单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recipe.ingredients.forEach { ing ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(ing.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(ing.amount, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item {
                    Text("做法步骤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            recipe.steps.forEachIndexed { i, step ->
                                val minutes = Regex("(\\d+)\\s*分钟").findAll(step)
                                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                                    .maxOrNull()
                                Column {
                                    Row {
                                        Text("${i + 1}. ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    }
                                    if (minutes != null && minutes > 0) {
                                        FilledTonalButton(
                                            onClick = { countDownMinutes = minutes },
                                            modifier = Modifier.padding(top = 4.dp),
                                        ) {
                                            Icon(painterResource(R.drawable.ic_timer), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(4.dp))
                                            Text("计时 $minutes 分钟")
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
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value $unit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 烹饪倒计时对话框：环形进度 + 提示音/振动 */
@Composable
fun CountDownDialog(minutes: Int, onDismiss: () -> Unit) {
    val totalSec = minutes * 60
    var remain by remember { mutableStateOf(totalSec) }
    var paused by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tone = remember { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 80) }
    var alarmed by remember { mutableStateOf(false) }

    LaunchedEffect(paused) {
        while (!paused && remain > 0) {
            kotlinx.coroutines.delay(1000)
            if (!paused) remain -= 1
        }
        if (remain <= 0 && !alarmed) {
            alarmed = true
            runCatching { tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 3000) }
            runCatching {
                val vib = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { runCatching { tone.release() } }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏱ 烹饪计时 $minutes 分钟") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { if (totalSec == 0) 0f else remain.toFloat() / totalSec },
                    modifier = Modifier.size(120.dp),
                    strokeWidth = 8.dp,
                    color = if (remain > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (remain > 0) {
                        val m = remain / 60; val s = remain % 60
                        "%d:%02d".format(m, s)
                    } else "时间到！火候到了～",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { paused = !paused }) {
                Text(if (paused) "继续" else "暂停")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun StatIcon(res: Int, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "$value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

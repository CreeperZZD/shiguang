package com.shiguang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shiguang.app.data.Api
import com.shiguang.app.data.Session
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

/** 广场帖子（/api/posts、/api/nearby 共用条目结构，探测字段：id/user_id/nickname/text/image_urls/recipe_id/city/comment_count/created_at） */
private data class SquarePost(
    val id: Long,
    val nickname: String,
    val text: String,
    val recipeId: Long?,
    val city: String?,
    val commentCount: Int,
    val createdAt: String,
)

/** 广场评论（探测字段：id/post_id/user_id/text/created_at/nickname） */
private data class SquareComment(
    val id: Long,
    val nickname: String,
    val text: String,
    val createdAt: String,
)

private enum class SquareTab(val label: String) {
    LATEST("最新"),
    RECOMMEND("推荐"),
    NEARBY("附近"),
}

private fun parseSquarePost(o: JSONObject): SquarePost = SquarePost(
    id = o.getLong("id"),
    nickname = o.optString("nickname").ifBlank { "匿名食客" },
    text = o.optString("text"),
    recipeId = if (o.isNull("recipe_id")) null else o.optLong("recipe_id"),
    city = o.optString("city").ifBlank { null },
    commentCount = o.optInt("comment_count"),
    createdAt = o.optString("created_at"),
)

private fun parseSquareComment(o: JSONObject): SquareComment = SquareComment(
    id = o.getLong("id"),
    nickname = o.optString("nickname").ifBlank { "匿名食客" },
    text = o.optString("text"),
    createdAt = o.optString("created_at"),
)

/** "2026-11-02 02:56:18" -> "11-02 02:56"；非预期格式原样返回 */
private fun prettyTime(raw: String): String {
    val t = raw.trim()
    return if (t.length >= 16 && t.getOrNull(4) == '-') t.substring(5, 16) else t
}

/** 社区广场：附近在吃 + 发帖 + 评论（数据源 最新/推荐/附近 可切换） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquareScreen(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(SquareTab.LATEST) }
    var posts by remember { mutableStateOf<List<SquarePost>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 附近模式（记住上次查询的城市）
    var cityInput by remember { mutableStateOf(Session.lastCity ?: "") }
    var nearbyCity by remember { mutableStateOf<String?>(null) } // 已生效的查询城市

    // 评论模式（detail != null 表示在帖子详情/评论页）
    var detail by remember { mutableStateOf<SquarePost?>(null) }
    var comments by remember { mutableStateOf<List<SquareComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsErr by remember { mutableStateOf<String?>(null) }
    var commentInput by remember { mutableStateOf("") }
    var sendingComment by remember { mutableStateOf(false) }

    // 发帖对话框
    var showComposer by remember { mutableStateOf(false) }
    var composeText by remember { mutableStateOf("") }
    var composeRecipe by remember { mutableStateOf("") }
    var composeCity by remember { mutableStateOf("") }
    var composeErr by remember { mutableStateOf<String?>(null) }
    var posting by remember { mutableStateOf(false) }

    // recipe_id -> 菜谱名 缓存（帖子接口不带菜谱名，按需补充）
    var recipeNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    fun queryNearby() {
        val c = cityInput.trim()
        if (c.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请先输入城市名称") }
            return
        }
        nearbyCity = c
        Session.lastCity = c
        refreshKey++
    }

    fun openDetail(p: SquarePost) {
        detail = p
        comments = emptyList()
        commentsErr = null
        commentInput = ""
    }

    fun exitDetail() {
        detail = null
        comments = emptyList()
        commentsErr = null
        commentInput = ""
    }

    fun publish() {
        val text = composeText.trim()
        if (text.isEmpty() || posting) return
        scope.launch {
            posting = true
            composeErr = null
            var successMsg = ""
            try {
                // 关联菜谱名 -> recipe_id：用菜谱搜索接口精确匹配，退而取第一条
                var recipeId: Long? = null
                val rn = composeRecipe.trim()
                if (rn.isNotEmpty()) {
                    try {
                        val resp = Api.get("/recipes?page=1&q=" + URLEncoder.encode(rn, "UTF-8"))
                        val arr = resp.optJSONArray("items")
                        if (arr != null && arr.length() > 0) {
                            var picked: JSONObject? = null
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                if (o.optString("name") == rn) {
                                    picked = o
                                    break
                                }
                            }
                            recipeId = (picked ?: arr.getJSONObject(0)).optLong("id").takeIf { it > 0 }
                        }
                    } catch (_: Exception) {
                        // 搜索失败不阻塞发帖，只是不关联菜谱
                    }
                }
                val body = JSONObject().put("text", text)
                recipeId?.let { body.put("recipe_id", it) }
                val city = composeCity.trim()
                if (city.isNotEmpty()) body.put("city", city)
                Api.post("/posts", body)
                successMsg = if (rn.isNotEmpty() && recipeId == null) "已发布，但没找到菜谱「$rn」" else "发布成功"
                showComposer = false
                composeText = ""
                composeRecipe = ""
                composeCity = ""
                refreshKey++
            } catch (e: Exception) {
                composeErr = e.message ?: "发布失败，请稍后再试"
            }
            posting = false
            if (successMsg.isNotEmpty()) snackbar.showSnackbar(successMsg)
        }
    }

    fun sendComment() {
        val p = detail ?: return
        val text = commentInput.trim()
        if (text.isEmpty() || sendingComment) return
        scope.launch {
            sendingComment = true
            try {
                Api.post("/posts/${p.id}/comments", JSONObject().put("text", text))
                commentInput = ""
                // POST 响应里没有 nickname，重新拉取评论列表保证显示完整
                try {
                    val resp = Api.get("/posts/${p.id}/comments")
                    val arr = resp.optJSONArray("items")
                    comments = if (arr == null) {
                        emptyList()
                    } else {
                        (0 until arr.length()).map { parseSquareComment(arr.getJSONObject(it)) }
                    }
                    commentsErr = null
                } catch (e: Exception) {
                    commentsErr = e.message ?: "评论刷新失败"
                }
                detail = p.copy(commentCount = p.commentCount + 1)
                posts = posts.map { if (it.id == p.id) it.copy(commentCount = it.commentCount + 1) else it }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "评论发送失败")
            }
            sendingComment = false
        }
    }

    // 按数据源加载列表
    LaunchedEffect(tab, nearbyCity, refreshKey) {
        if (tab == SquareTab.NEARBY && nearbyCity == null) {
            posts = emptyList()
            err = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        err = null
        try {
            val city = nearbyCity
            val resp = when (tab) {
                SquareTab.LATEST -> Api.get("/posts?tab=latest")
                SquareTab.RECOMMEND -> Api.get("/posts?tab=recommend")
                SquareTab.NEARBY -> Api.get("/nearby?city=" + URLEncoder.encode(city ?: "", "UTF-8"))
            }
            val arr = resp.optJSONArray("items")
            posts = if (arr == null) {
                emptyList()
            } else {
                (0 until arr.length()).map { parseSquarePost(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    // 补充帖子关联菜谱的名字（GET /recipes/:id -> {item:{name}})
    LaunchedEffect(posts) {
        val need = posts.mapNotNull { it.recipeId }.distinct().filter { recipeNames[it] == null }
        for (id in need) {
            try {
                val name = Api.get("/recipes/$id").optJSONObject("item")?.optString("name").orEmpty()
                recipeNames = recipeNames + (id to name)
            } catch (_: Exception) {
                recipeNames = recipeNames + (id to "") // 失败记空串，避免反复重试；界面显示 "菜谱 #id"
            }
        }
    }

    // 进入评论模式后加载评论列表
    LaunchedEffect(detail?.id) {
        val p = detail ?: return@LaunchedEffect
        commentsLoading = true
        commentsErr = null
        comments = emptyList()
        try {
            val resp = Api.get("/posts/${p.id}/comments")
            val arr = resp.optJSONArray("items")
            comments = if (arr == null) {
                emptyList()
            } else {
                (0 until arr.length()).map { parseSquareComment(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            commentsErr = e.message ?: "评论加载失败"
        } finally {
            commentsLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (detail == null) "广场" else "评论") },
                navigationIcon = {
                    IconButton(onClick = { if (detail != null) exitDetail() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (detail == null) "返回" else "返回列表",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (detail == null) {
                FloatingActionButton(onClick = { showComposer = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "发动态")
                }
            }
        },
    ) { pad ->
        val p = detail
        if (p == null) {
            // ---------- 列表模式 ----------
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SquareTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.label) },
                        )
                    }
                }
                if (tab == SquareTab.NEARBY) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = cityInput,
                            onValueChange = { cityInput = it },
                            label = { Text("城市，如：长沙") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { queryNearby() }) { Text("看附近") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        err != null -> ErrorBox(err!!)
                        loading -> LoadingBox()
                        tab == SquareTab.NEARBY && nearbyCity == null ->
                            CenterHint("输入城市名称，看看附近的人正在吃什么")
                        posts.isEmpty() ->
                            CenterHint("还没有动态，点 + 分享你的做菜心得")
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(posts.size, key = { posts[it].id }) { i ->
                                val post = posts[i]
                                PostCard(
                                    post = post,
                                    recipeName = recipeNames[post.recipeId],
                                    onOpenRecipe = onOpenRecipe,
                                    modifier = Modifier,
                                    onClick = { openDetail(post) },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ---------- 帖子详情 / 评论模式 ----------
            Column(Modifier.fillMaxSize().padding(pad)) {
                PostCard(
                    post = p,
                    recipeName = recipeNames[p.recipeId],
                    onOpenRecipe = onOpenRecipe,
                    modifier = Modifier.padding(16.dp),
                    onClick = null,
                )
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    when {
                        commentsLoading -> LoadingBox()
                        commentsErr != null -> ErrorBox(commentsErr!!)
                        comments.isEmpty() -> CenterHint("还没有评论，来抢沙发～")
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(comments.size, key = { comments[it].id }) { i ->
                                val c = comments[i]
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            c.nickname,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            prettyTime(c.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(12.dp).imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        placeholder = { Text("友善评论，鼓励一下～") },
                        maxLines = 3,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { sendComment() },
                        enabled = !sendingComment && commentInput.isNotBlank(),
                    ) {
                        if (sendingComment) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("发送")
                        }
                    }
                }
            }
        }
    }

    if (showComposer) {
        AlertDialog(
            onDismissRequest = { if (!posting) showComposer = false },
            title = { Text("✍️ 发动态") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = composeText,
                        onValueChange = { composeText = it },
                        label = { Text("分享你的做菜心得（必填）") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = composeRecipe,
                        onValueChange = { composeRecipe = it },
                        label = { Text("关联菜谱名（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = composeCity,
                        onValueChange = { composeCity = it },
                        label = { Text("所在城市（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val ce = composeErr
                    if (ce != null) {
                        Text(
                            ce,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { publish() },
                    enabled = !posting && composeText.isNotBlank(),
                ) {
                    if (posting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("发布")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showComposer = false }, enabled = !posting) {
                    Text("取消")
                }
            },
        )
    }
}

/** 帖子卡片：昵称 + 城市 + 时间 + 正文 + 关联菜谱 + 评论数；onClick 为空时不可点（详情页复用） */
@Composable
private fun PostCard(
    post: SquarePost,
    recipeName: String?,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    post.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    listOfNotNull(post.city?.let { "$it" }, prettyTime(post.createdAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(post.text, style = MaterialTheme.typography.bodyMedium)
            val rid = post.recipeId
            if (rid != null) {
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = { onOpenRecipe(rid) },
                    label = {
                        Text((recipeName?.takeIf { it.isNotBlank() } ?: "菜谱 #$rid"))
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${post.commentCount} 条评论",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 居中提示文案 */
@Composable
private fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

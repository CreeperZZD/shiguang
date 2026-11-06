package com.shiguang.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiguang.app.R
import com.shiguang.app.data.Api
import com.shiguang.app.data.Recipe
import com.shiguang.app.data.toRecipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 今天吃啥：进入即自动摇出一道菜；也可以真的摇动手机触发（MainActivity 的加速度传感器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeScreen(onBack: () -> Unit, onOpenRecipe: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var dish by remember { mutableStateOf<Recipe?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val spin by animateFloatAsState(
        targetValue = if (dish == null && err == null) 1080f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "spin",
    )

    fun roll() {
        scope.launch {
            dish = null
            err = null
            try {
                delay(500) // 让骰子转起来有仪式感
                dish = Api.get("/recipes/random").getJSONObject("item").toRecipe()
            } catch (e: Exception) {
                err = e.message ?: "网络异常，再摇一次试试"
            }
        }
    }

    LaunchedEffect(Unit) { roll() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("今天吃啥") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val d = dish
            when {
                d != null -> {
                    Text(
                        d.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${d.kcal} kcal · ${d.minutes} 分钟 · ${d.category}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = { onOpenRecipe(d.id); onBack() }) { Text("就吃它 →") }
                        FilledTonalButton(onClick = { roll() }) { Text("再摇一次") }
                    }
                }
                err != null -> {
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    FilledTonalButton(onClick = { roll() }) { Text("再摇一次") }
                }
                else -> {
                    Icon(
                        painterResource(R.drawable.ic_dice),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).graphicsLayer { rotationZ = spin },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("摇一摇中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "提示：在任意页面摇动手机也能摇出一道菜",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

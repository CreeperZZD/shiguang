package com.shiguang.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.shiguang.app.data.Api
import com.shiguang.app.data.ApiException
import com.shiguang.app.data.AppSettings
import com.shiguang.app.data.Session
import com.shiguang.app.data.parseMe
import com.shiguang.app.ui.FridgeScanScreen
import com.shiguang.app.ui.FamilyScreen
import com.shiguang.app.ui.IngredientsScreen
import com.shiguang.app.ui.LeaderboardScreen
import com.shiguang.app.ui.LoginScreen
import com.shiguang.app.ui.QuantifyScreen
import com.shiguang.app.ui.SettingsScreen
import com.shiguang.app.ui.PreferencesScreen
import com.shiguang.app.ui.DiaryScreen
import com.shiguang.app.ui.ShakeScreen
import com.shiguang.app.ui.StreetScreen
import com.shiguang.app.ui.SquareScreen
import com.shiguang.app.ui.WeeklyReportScreen
import com.shiguang.app.ui.MineScreen
import com.shiguang.app.ui.ProfileScreen
import com.shiguang.app.ui.RecipeDetailScreen
import com.shiguang.app.ui.RecipesScreen
import com.shiguang.app.ui.TodayScreen
import com.shiguang.app.ui.VideosScreen
import com.shiguang.app.ui.WeeklyPlanScreen
import com.shiguang.app.ui.theme.ShiGuangTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        Api.token = Session.token
        setContent {
            com.shiguang.app.data.AppSettings.init(this)
            // 主题模式：AppSettings.themeMode（Compose 状态，切换立即生效）
            val darkTheme = when (com.shiguang.app.data.AppSettings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ShiGuangTheme(darkTheme = darkTheme) {
                // 全局铺主题底色：杜绝"浅色窗口背景 × 深色组件颜色"的混用问题
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App()
                }
            }
        }
    }
}

private data class TabItem(val label: String, val iconRes: Int)

@Composable
fun App(onSetTheme: (String) -> Unit = {}) {
    // 路由：boot / login / profile / edit / main / weekly / detail:<id>
    var route by rememberSaveable { mutableStateOf("boot") }
    var meJson by rememberSaveable { mutableStateOf<String?>(null) }
    // 详情页返回时应回到的来源（main / weekly）
    var detailReturn by rememberSaveable { mutableStateOf("main") }

    LaunchedEffect(Unit) {
        if (Session.token == null) {
            // 游客身份仍可进入浏览（不登录，数据存本机）
            if (Session.guest) { route = "main"; return@LaunchedEffect }
            route = "login"
            return@LaunchedEffect
        }
        try {
            val me = Api.get("/me")
            meJson = me.toString()
            Session.saveMeJson(meJson!!)
            route = if (parseMe(meJson!!)?.bmi != null) "main" else "profile"
        } catch (e: ApiException) {
            if (e.code == 401) {
                Session.clear(); Api.token = null; route = "login"
            } else {
                // 网络异常时使用缓存，离线也能浏览
                meJson = Session.meJson()
                route = if (parseMe(meJson ?: "")?.bmi != null) "main" else "login"
            }
        } catch (e: Exception) {
            meJson = Session.meJson()
            route = if (parseMe(meJson ?: "")?.bmi != null) "main" else "login"
        }
    }

    BackHandler(enabled = route.startsWith("detail:") || route == "edit" || route == "weekly" || route.startsWith("videos:") || route == "leaderboard" || route == "square" || route == "quantify" || route == "fridgescan" || route == "family" || route == "report" || route == "settings" || route == "prefs" || route == "diary" || route == "shake" || route == "street") {
        route = when {
            route.startsWith("detail:") -> detailReturn
            route.startsWith("videos:") -> "detail:" + route.substringAfter("videos:")
            route == "prefs" -> "settings"
            else -> "main"
        }
    }

    when {
        route == "boot" -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text("食光", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        route == "login" -> LoginScreen(
            onLoginDone = { complete, json ->
                Session.guest = false
                if (json != null) { meJson = json; Session.saveMeJson(json) }
                route = if (complete) "main" else "profile"
            },
            onGuest = { Session.guest = true; route = "main" },
        )
        route == "profile" -> ProfileScreen(
            initialMeJson = Session.meJson(),
            onSaved = { json -> meJson = json; Session.saveMeJson(json); route = "main" },
        )
        route == "edit" -> ProfileScreen(
            initialMeJson = meJson,
            canBack = true,
            onBack = { route = "main" },
            onSaved = { json -> meJson = json; Session.saveMeJson(json); route = "main" },
        )
        route == "weekly" -> WeeklyPlanScreen(
            target = parseMe(meJson ?: "")?.targetCalories?.toInt(),
            onOpenRecipe = { detailReturn = "weekly"; route = "detail:$it" },
            onBack = { route = "main" },
        )
        route.startsWith("detail:") -> RecipeDetailScreen(
            id = route.substringAfter("detail:").toLong(),
            onBack = { route = detailReturn },
            onOpenVideos = { route = "videos:$it" },
        )
        route.startsWith("videos:") -> VideosScreen(
            recipeId = route.substringAfter("videos:").toLong(),
            onBack = { route = "detail:" + route.substringAfter("videos:") },
        )
        route == "leaderboard" -> LeaderboardScreen(
            onOpenRecipe = { detailReturn = "leaderboard"; route = "detail:$it" },
            onBack = { route = "main" },
        )
        route == "square" -> SquareScreen(
            onOpenRecipe = { detailReturn = "square"; route = "detail:$it" },
            onBack = { route = "main" },
        )
        route == "quantify" -> QuantifyScreen(
            onBack = { route = "main" },
            onPublished = { route = "main" },
        )
        route == "fridgescan" -> FridgeScanScreen(
            onDone = { added -> route = "main" },
            onBack = { route = "main" },
        )
        route == "family" -> FamilyScreen(onBack = { route = "main" })
        route == "settings" -> SettingsScreen(onBack = { route = "main" }, onOpenPrefs = { route = "prefs" })
        route == "prefs" -> PreferencesScreen(onBack = { route = "settings" })
        route == "diary" -> DiaryScreen(onBack = { route = "main" }, onLogin = { route = "login" })
        route == "shake" -> ShakeScreen(
            onBack = { route = "main" },
            onOpenRecipe = { detailReturn = "main"; route = "detail:$it" },
        )
        route == "street" -> StreetScreen(onBack = { route = "main" })
        route == "report" -> WeeklyReportScreen(
            target = Session.currentMemberTarget ?: parseMe(meJson ?: "")?.targetCalories?.toInt(),
            onBack = { route = "main" },
        )
        else -> MainTabs(
            meJson = meJson,
            onOpenRecipe = { detailReturn = "main"; route = "detail:$it" },
            onOpenWeekly = { route = "weekly" },
            onOpenLeaderboard = { route = "leaderboard" },
            onOpenSquare = { route = "square" },
            onOpenQuantify = { route = "quantify" },
            onOpenFridgeScan = { route = "fridgescan" },
            onOpenFamily = { route = "family" },
            onOpenReport = { route = "report" },
            onOpenSettings = { route = "settings" },
            onOpenDiary = { route = "diary" },
            onOpenShake = { route = "shake" },
            onOpenLogin = { route = "login" },
            onOpenStreet = { route = "street" },
            onEditProfile = { route = "edit" },
            onSetTheme = onSetTheme,
            onLogout = {
                Session.clear(); Api.token = null; meJson = null; route = "login"
            },
        )
    }
}

@Composable
fun MainTabs(
    meJson: String?,
    onOpenRecipe: (Long) -> Unit,
    onOpenWeekly: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenSquare: () -> Unit,
    onOpenQuantify: () -> Unit,
    onOpenFridgeScan: () -> Unit,
    onOpenFamily: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiary: () -> Unit = {},
    onOpenShake: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
    onOpenStreet: () -> Unit = {},
    onEditProfile: () -> Unit,
    onSetTheme: (String) -> Unit = {},
    onLogout: () -> Unit,
) {
    val tabs = remember { listOf(TabItem("今日", com.shiguang.app.R.drawable.ic_tab_home), TabItem("食材", com.shiguang.app.R.drawable.ic_tab_ingredients), TabItem("食谱", com.shiguang.app.R.drawable.ic_tab_recipes), TabItem("我的", com.shiguang.app.R.drawable.ic_tab_mine)) }
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected == index) 1.18f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "tabIcon$index",
                    )
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                androidx.compose.ui.res.painterResource(tab.iconRes),
                                contentDescription = tab.label,
                                modifier = Modifier.scale(iconScale),
                                tint = if (selected == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) + slideInVertically(animationSpec = tween(220)) { it / 16 })
                    .togetherWith(fadeOut(animationSpec = tween(90)))
            },
            label = "tabContent",
            modifier = Modifier.padding(padding),
        ) { tab ->
            Box(Modifier) {
                when (tab) {
                0 -> TodayScreen(
                    target = Session.currentMemberTarget ?: parseMe(meJson ?: "")?.targetCalories?.toInt(),
                    onOpenRecipe = onOpenRecipe,
                    onOpenWeekly = onOpenWeekly,
                    onOpenDiary = onOpenDiary,
                    onOpenStreet = onOpenStreet,
                    onOpenShake = onOpenShake,
                )
                    1 -> IngredientsScreen(onOpenRecipe = onOpenRecipe, onOpenFridgeScan = onOpenFridgeScan)
                    2 -> RecipesScreen(
                        onOpenRecipe = onOpenRecipe,
                        onOpenLeaderboard = onOpenLeaderboard,
                        onOpenSquare = onOpenSquare,
                        onOpenQuantify = onOpenQuantify,
                    )
                    else -> MineScreen(
                    onEditProfile = onEditProfile,
                    onLogout = onLogout,
                    onOpenRecipe = onOpenRecipe,
                    onOpenFamily = onOpenFamily,
                    onOpenReport = onOpenReport,
                    onOpenSettings = onOpenSettings,
                    onOpenShake = onOpenShake,
                    onLogin = onOpenLogin,
                    onOpenStreet = onOpenStreet,
                )
                }
            }
        }
    }
}

package com.shiguang.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

object Api {
    // 安全组已放行 8000，手机直连公网；如需回到 USB 隧道模式改为 http://127.0.0.1:8000/api
    const val HOST = "http://118.178.99.65:8000"
    const val BASE = "$HOST/api"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    var token: String? = null

    /** 全部为 suspend，内部切到 IO 线程，调用方在任意协程作用域中均可安全调用 */
    suspend fun get(path: String, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { call("GET", path, null, auth) }

    suspend fun post(path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { call("POST", path, body, auth) }

    suspend fun put(path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { call("PUT", path, body, auth) }

    suspend fun delete(path: String, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { call("DELETE", path, null, auth) }

    private fun call(method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject {
        val builder = Request.Builder().url(BASE + path)
        if (auth && token != null) builder.header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(JSON))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(JSON))
            "DELETE" -> builder.delete()
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (resp.code == 401) {
                throw ApiException(401, json.optString("detail", "登录已过期，请重新登录"))
            }
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, json.optString("detail", "请求失败(${resp.code})"))
            }
            if (json.optString("error").isNotEmpty()) {
                throw ApiException(resp.code, json.optString("detail", json.getString("error")))
            }
            return json
        }
    }
}

data class Ingredient(val name: String, val amount: String)

data class Recipe(
    val id: Long,
    val name: String,
    val intro: String,
    val category: String,
    val meatTag: String,
    val difficulty: Int,
    val minutes: Int,
    val calorieTier: String,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val kcal: Int,
    val protein: Int,
    val fat: Int,
    val carb: Int,
    val views: Int,
    val likeCount: Int,
    val favoriteCount: Int,
    val coverUrl: String? = null,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    /** 基于个人饮食偏好（忌口/过敏/病理）生成的提示文案 */
    val warnings: List<String> = emptyList(),
)

data class User(
    val id: Long,
    val phone: String,
    val nickname: String,
    val gender: String?,
    val age: Int?,
    val heightCm: Double?,
    val weightKg: Double?,
    val activity: Double?,
    val goal: String?,
    val bmi: Double?,
    val bmr: Double?,
    val tdee: Double?,
    val targetCalories: Double?,
)

/** 解析 /me 或 PUT /me 的整体响应：{user:{...}, profile:{bmi,bmr,tdee,target_calories,...}} */
fun parseMe(json: String): User? = try {
    val root = JSONObject(json)
    val u = root.optJSONObject("user") ?: return null
    val merged = JSONObject(u.toString())
    root.optJSONObject("profile")?.let { merged.put("profile", it) }
    merged.toUser()
} catch (e: Exception) {
    null
}

private fun JSONObject.toUser(): User {
    val p = optJSONObject("profile")
    fun JSONObject.d(key: String): Double? = if (isNull(key)) null else optDouble(key)
    fun JSONObject.s(key: String): String? = if (isNull(key)) null else optString(key)
    return User(
        id = getLong("id"),
        phone = getString("phone"),
        nickname = optString("nickname"),
        gender = s("gender"),
        age = if (isNull("age")) null else optInt("age"),
        heightCm = d("height_cm"),
        weightKg = d("weight_kg"),
        activity = d("activity"),
        goal = s("goal"),
        bmi = p?.d("bmi"),
        bmr = p?.d("bmr"),
        tdee = p?.d("tdee"),
        targetCalories = p?.d("target_calories"),
    )
}

fun JSONObject.toRecipe(): Recipe {
    val ing = mutableListOf<Ingredient>()
    optJSONArray("ingredients")?.let { a ->
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            ing.add(Ingredient(o.getString("name"), o.optString("amount")))
        }
    }
    val st = mutableListOf<String>()
    optJSONArray("steps")?.let { a -> for (i in 0 until a.length()) st.add(a.getString(i)) }
    val n = optJSONObject("nutrition")
    return Recipe(
        id = getLong("id"),
        name = getString("name"),
        intro = optString("intro"),
        category = optString("category"),
        meatTag = optString("meat_tag"),
        difficulty = optInt("difficulty"),
        minutes = optInt("minutes"),
        calorieTier = optString("calorie_tier"),
        ingredients = ing,
        steps = st,
        kcal = n?.optInt("kcal") ?: 0,
        protein = n?.optInt("protein") ?: 0,
        fat = n?.optInt("fat") ?: 0,
        carb = n?.optInt("carb") ?: 0,
        views = optInt("views"),
        likeCount = optInt("like_count"),
        favoriteCount = optInt("favorite_count"),
        coverUrl = optString("cover_url").takeIf { it.isNotEmpty() },
        liked = optBoolean("liked"),
        favorited = optBoolean("favorited"),
        warnings = optStringArray("warnings"),
    )
}

/** 警示字段兼容解析：服务端返回 {tag,label,reason} 对象数组，也兼容纯字符串数组 */
private fun JSONObject.optStringArray(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    val list = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is String -> if (v.isNotEmpty()) list.add(v)
            is JSONObject -> {
                val s = v.optString("reason").ifEmpty { v.optString("label") }
                if (s.isNotEmpty()) list.add(s)
            }
        }
    }
    return list
}

data class WeekDayPlan(
    val date: String,
    val weekday: String,
    val totalKcal: Int,
    val withinRange: Boolean,
    val meals: List<DailyMeal>,
    val drinks: List<DrinkItem> = emptyList(),
)

data class DrinkItem(val name: String, val kcal: Int)

/** 结构化一餐里的单道菜（含份量标签） */
data class DishItem(val recipe: Recipe, val servings: Double, val kcal: Int, val servingLabel: String)

data class WeeklyPlan(val startDate: String, val target: Int, val days: List<WeekDayPlan>, val breakdown: List<TargetPart>? = null)

fun JSONObject.toWeeklyPlan(): WeeklyPlan {
    val arr = getJSONArray("days")
    val days = mutableListOf<WeekDayPlan>()
    for (i in 0 until arr.length()) {
        val d = arr.getJSONObject(i)
        val meals = mutableListOf<DailyMeal>()
        val m = d.getJSONObject("meals")
        val labels = mapOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐")
        for (key in listOf("breakfast", "lunch", "dinner")) {
            val o = m.optJSONObject(key) ?: continue
            o.toDailyMeal(key, labels[key] ?: key)?.let { meals.add(it) }
        }
        days.add(
            WeekDayPlan(
                date = d.optString("date"),
                weekday = d.optString("weekday"),
                totalKcal = d.optInt("total_kcal"),
                withinRange = d.optBoolean("within_range"),
                meals = meals,
                drinks = d.parseDrinks(),
            )
        )
    }
    return WeeklyPlan(startDate = optString("start_date"), target = optInt("target"), days = days, breakdown = parseBreakdown())
}

data class DailyMeal(
    val key: String,
    val label: String,
    val recipe: Recipe,
    val servings: Double,
    val kcal: Int,
    /** 结构化多道菜（几菜一汤）；null=旧版单菜 */
    val dishes: List<DishItem>? = null,
)

data class TargetPart(val name: String, val target: Int)

private fun JSONObject.parseBreakdown(): List<TargetPart>? {
    val arr = optJSONArray("breakdown") ?: return null
    val list = mutableListOf<TargetPart>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        list.add(TargetPart(o.optString("name"), o.optInt("target")))
    }
    return list.ifEmpty { null }
}

data class DailyPlan(
    val date: String,
    val target: Int,
    val totalKcal: Int,
    val withinRange: Boolean,
    val meals: List<DailyMeal>,
    val breakdown: List<TargetPart>? = null,
    val drinks: List<DrinkItem> = emptyList(),
)

/** 解析一餐：优先 dishes 数组（结构化多道），缺失时退回单菜字段 */
private fun JSONObject.toDailyMeal(key: String, label: String): DailyMeal? {
    val dishesArr = optJSONArray("dishes")
    if (dishesArr != null && dishesArr.length() > 0) {
        val dishes = mutableListOf<DishItem>()
        for (i in 0 until dishesArr.length()) {
            val o = dishesArr.getJSONObject(i)
            dishes.add(
                DishItem(
                    recipe = o.getJSONObject("recipe").toRecipe(),
                    servings = o.optDouble("servings", 1.0),
                    kcal = o.optInt("kcal"),
                    servingLabel = o.optString("serving_label"),
                )
            )
        }
        val first = dishes[0]
        return DailyMeal(key, label, first.recipe, first.servings, dishes.sumOf { it.kcal }, dishes)
    }
    val o = optJSONObject("recipe") ?: return null
    return DailyMeal(key, label, o.toRecipe(), optDouble("servings", 1.0), optInt("kcal"), null)
}

private fun JSONObject.parseDrinks(): List<DrinkItem> {
    val arr = optJSONArray("drinks") ?: return emptyList()
    val list = mutableListOf<DrinkItem>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        list.add(DrinkItem(o.optString("name"), o.optInt("kcal")))
    }
    return list
}

fun JSONObject.toDailyPlan(): DailyPlan {
    val meals = mutableListOf<DailyMeal>()
    val m = getJSONObject("meals")
    val labels = mapOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐")
    for (key in listOf("breakfast", "lunch", "dinner")) {
        val o = m.optJSONObject(key) ?: continue
        o.toDailyMeal(key, labels[key] ?: key)?.let { meals.add(it) }
    }
    return DailyPlan(
        date = optString("date"),
        target = optInt("target"),
        totalKcal = optInt("total_kcal"),
        withinRange = optBoolean("within_range"),
        meals = meals,
        breakdown = parseBreakdown(),
        drinks = parseDrinks(),
    )
}

fun JSONArray.recipeList(): List<Recipe> {
    val list = mutableListOf<Recipe>()
    for (i in 0 until length()) list.add(getJSONObject(i).toRecipe())
    return list
}

/** 收藏/推荐等接口条目兼容解析：{recipe:{...}, warnings:[...]} 或直接是菜谱对象 */
fun JSONArray.flexRecipeList(): List<Recipe> {
    val list = mutableListOf<Recipe>()
    for (i in 0 until length()) {
        val o = getJSONObject(i)
        val r = o.optJSONObject("recipe") ?: o
        if (r.has("name")) list.add(r.toRecipe().copy(warnings = o.optStringArray("warnings")))
    }
    return list
}

data class VideoItem(
    val title: String,
    val author: String,
    val duration: String,
    val bvid: String,
    val playUrl: String,
    val cover: String,
    val viewCount: Long,
)

data class VideoResult(
    val items: List<VideoItem>,
    val source: String,
    val cached: Boolean,
    val fallback: String,
)

fun JSONObject.toVideoResult(): VideoResult {
    val arr = optJSONArray("items")
    val items = mutableListOf<VideoItem>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items.add(
                VideoItem(
                    title = o.optString("title"),
                    author = o.optString("author"),
                    duration = o.optString("duration"),
                    bvid = o.optString("bvid"),
                    playUrl = o.optString("play_url"),
                    cover = o.optString("cover"),
                    viewCount = o.optLong("view_count", 0),
                )
            )
        }
    }
    return VideoResult(
        items = items,
        source = optString("source", "none"),
        cached = optBoolean("cached"),
        fallback = optString("fallback"),
    )
}

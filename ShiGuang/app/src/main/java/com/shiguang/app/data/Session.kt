package com.shiguang.app.data

import android.content.Context
import android.content.SharedPreferences

/** 本地会话：token、用户档案缓存、点赞/收藏态（服务端为最终事实，本地仅作快速显示） */
object Session {
    private const val NAME = "shiguang"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    fun saveMeJson(json: String) = prefs.edit().putString("me_json", json).apply()
    fun meJson(): String? = prefs.getString("me_json", null)

    fun likedIds(): MutableSet<String> =
        (prefs.getStringSet("liked", emptySet()) ?: emptySet()).toMutableSet()

    fun favIds(): MutableSet<String> =
        (prefs.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()

    fun setLiked(ids: Set<String>) = prefs.edit().putStringSet("liked", ids).apply()

    fun setFavs(ids: Set<String>) = prefs.edit().putStringSet("favs", ids).apply()

    /** 游客模式：不登录使用，数据仅存本机 */
    var guest: Boolean
        get() = prefs.getBoolean("guest", false)
        set(v) = prefs.edit().putBoolean("guest", v).apply()

    /** 自定义 AI 识别接口（用户自带 API；留空=用官方服务器） */
    var aiBaseUrl: String?
        get() = prefs.getString("ai_base_url", null)
        set(v) = prefs.edit().putString("ai_base_url", v).apply()
    var aiApiKey: String?
        get() = prefs.getString("ai_api_key", null)
        set(v) = prefs.edit().putString("ai_api_key", v).apply()
    var aiModel: String?
        get() = prefs.getString("ai_model", null)
        set(v) = prefs.edit().putString("ai_model", v).apply()

    fun clear() = prefs.edit().clear().apply()

    /** 附近在吃：记住上次查询的城市 */
    var lastCity: String?
        get() = prefs.getString("last_city", null)
        set(v) = prefs.edit().putString("last_city", v).apply()

    /** 家庭成员：当前生效的成员（影响今日/一周计划的计算目标） */
    var currentMemberId: Long
        get() = prefs.getString("member_id", null)?.toLongOrNull() ?: 0L
        set(v) = prefs.edit().putString("member_id", if (v == 0L) null else v.toString()).apply()

    var currentMemberName: String?
        get() = prefs.getString("member_name", null)
        set(v) = prefs.edit().putString("member_name", v).apply()

    var currentMemberTarget: Int?
        get() = prefs.getString("member_target", null)?.toIntOrNull()
        set(v) = prefs.edit().putString("member_target", v?.toString()).apply()

    fun clearCurrentMember() {
        currentMemberId = 0L
        currentMemberName = null
        currentMemberTarget = null
    }

    /** 主题模式：system（默认跟随系统）/ light / dark */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(v) = prefs.edit().putString("theme_mode", v).apply()
}

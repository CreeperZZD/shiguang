package com.shiguang.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 跳转到抖音 / B站搜索或指定视频。
 * 抖音：把菜名复制进剪贴板 → 直开抖音 APP（getLaunchIntentForPackage 最可靠），
 *      打开搜索框时抖音会自动提示粘贴剪贴板内容，一点即搜。
 * B站：deep link 直达搜索/视频页，失败退回网页版。
 */
object LinkUtils {

    private const val DOUYIN_PKG = "com.ss.android.ugc.aweme"
    private const val BILIBILI_PKG = "tv.danmaku.bili"

    private fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun tryStart(context: Context, uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun openWeb(context: Context, webUri: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("shiguang-dish", text))
        }
    }

    /**
     * 抖音搜索：直开抖音 APP（不经过网页），菜名已进剪贴板，
     * 抖音的搜索框会自动提示"粘贴"——一点即搜。未安装才退回网页。
     */
    fun openDouyinSearch(context: Context, keyword: String) {
        copyToClipboard(context, keyword)
        if (isInstalled(context, DOUYIN_PKG)) {
            val launch = context.packageManager.getLaunchIntentForPackage(DOUYIN_PKG)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "已打开抖音，「$keyword」已复制，搜索框粘贴即可", Toast.LENGTH_LONG).show()
                return
            }
            // deep link 备选（部分版本支持直开搜索页）
            val kw = Uri.encode(keyword)
            for (uri in listOf(
                "snssdk1233://search?keyword=$kw",
                "aweme://search?keyword=$kw",
            )) {
                if (tryStart(context, uri)) return
            }
        }
        openWeb(context, "https://www.douyin.com/search/${Uri.encode(keyword)}")
    }

    /** 在B站内搜索关键词：已装B站则 deep link 直开搜索页，否则网页版 */
    fun openBilibiliSearch(context: Context, keyword: String) {
        val kw = Uri.encode(keyword)
        if (isInstalled(context, BILIBILI_PKG)) {
            for (uri in listOf(
                "bilibili://search?keyword=$kw",
                "bilibili://root?search_keyword=$kw",
            )) {
                if (tryStart(context, uri)) return
            }
        }
        openWeb(context, "https://search.bilibili.com/all?keyword=$kw")
    }

    /** 打开B站指定视频（BV 号）：优先APP，失败网页 */
    fun openBilibiliVideo(context: Context, bvid: String) {
        if (isInstalled(context, BILIBILI_PKG)) {
            for (uri in listOf(
                "bilibili://video/$bvid",
                "bilibili://bangumi/season/$bvid",
            )) {
                if (tryStart(context, uri)) return
            }
        }
        openWeb(context, "https://www.bilibili.com/video/$bvid")
    }
}

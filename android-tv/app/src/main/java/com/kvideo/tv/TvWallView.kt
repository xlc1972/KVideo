package com.kvideo.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 原生电视墙 —— 不依赖 WebView / 网站 JS 的兜底首页。
 *
 * 用途:
 * 1) WebView 加载失败 (白屏) 时, 用户仍能进电视墙看片
 * 2) 作为 TV 原生交互的基线, 用于对比 WebView 方案的可用性
 *
 * 数据来源: 站点公开 API (/api/sources 等), 失败时退化为静态入口。
 * 焦点: 用标准 Android focus —— 卡片设为 focusable, 方向键由系统处理。
 */
class TvWallView(context: Context) : ScrollView(context) {

    companion object {
        private const val TAG = "TvWallView"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(56), dp(40), dp(56), dp(56))
    }

    /** 卡片视图, 保存标题用于点击时传给回调 */
    private val cardTitles = mutableMapOf<View, String>()

    var onSearchRequested: (() -> Unit)? = null
    var onItemClicked: ((title: String) -> Unit)? = null
    var onRetryWebsite: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.parseColor("#0D0F14"))
        isFillViewport = true
        addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 构建电视墙 UI。
     *
     * @param baseUrl 站点地址
     */
    fun build(baseUrl: String) {
        root.removeAllViews()
        cardTitles.clear()

        // ---- 顶部: 标题栏 + 搜索按钮 ----
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "紫气东来影院"
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        val searchBtn = makeButton("搜索") {
            onSearchRequested?.invoke()
        }
        header.addView(searchBtn, LinearLayout.LayoutParams(dp(200), dp(56)))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- 分类行 ----
        val cats = listOf("热门", "最新", "喜剧", "科幻片", "动作片", "国产剧")
        val catRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(24), 0, dp(12))
        }
        cats.forEach { c ->
            val b = makeButton(c) { loadCategory(baseUrl, c) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
            lp.marginEnd = dp(12)
            catRow.addView(b, lp)
        }
        root.addView(catRow)

        // ---- 影片网格容器 ----
        val grid = GridLayout(context).apply {
            columnCount = 5
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- 初始加载 ----
        showPlaceholder(grid, "正在加载…")
        executor.execute {
            val items = fetchHomeItems(baseUrl)
            post {
                if (items.isEmpty()) {
                    showPlaceholder(grid, "无法加载内容，请检查网络或改用网页模式")
                    addFooter(grid)
                } else {
                    fillGrid(grid, items)
                }
            }
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 17f
            isAllCaps = false
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1E222B"))
                setStroke(dp(1), Color.parseColor("#39404E"))
            }
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { onClick() }
            // 获得焦点时高亮
            setOnFocusChangeListener { v, hasFocus ->
                val bg = v.background as? GradientDrawable
                bg?.setColor(Color.parseColor(if (hasFocus) "#2563EB" else "#1E222B"))
                v.scaleX = if (hasFocus) 1.04f else 1f
                v.scaleY = if (hasFocus) 1.04f else 1f
            }
        }
    }

    private fun showPlaceholder(grid: GridLayout, msg: String) {
        grid.removeAllViews()
        val tv = TextView(context).apply {
            text = msg
            setTextColor(Color.parseColor("#9EA7BA"))
            textSize = 20f
            gravity = Gravity.CENTER
        }
        grid.addView(tv, GridLayout.LayoutParams().apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
        })
    }

    private fun addFooter(grid: GridLayout) {
        val btn = makeButton("重新加载网页版") { onRetryWebsite?.invoke() }
        val lp = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(56)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
        }
        grid.addView(btn, lp)
    }

    private fun fillGrid(grid: GridLayout, items: List<Pair<String, String?>>) {
        grid.removeAllViews()
        items.forEach { (title, poster) ->
            val card = buildCard(title, poster)
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(300)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(10), dp(10), dp(10), dp(10))
            })
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildCard(title: String, poster: String?): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#171A21"))
                setStroke(dp(2), Color.parseColor("#2A2F3A"))
            }
            isFocusable = true
            isFocusableInTouchMode = false
            elevation = dp(2).toFloat()
        }
        cardTitles[card] = title

        // 海报区
        val posterBox = TextView(context).apply {
            text = title.take(2)
            setTextColor(Color.parseColor("#3A4252"))
            textSize = 40f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#0F1218"))
            }
        }
        card.addView(posterBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val label = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        card.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        card.setOnFocusChangeListener { v, hasFocus ->
            val bg = v.background as? GradientDrawable
            bg?.setStroke(dp(3), Color.parseColor(if (hasFocus) "#3B82F6" else "#2A2F3A"))
            v.scaleX = if (hasFocus) 1.06f else 1f
            v.scaleY = if (hasFocus) 1.06f else 1f
            v.elevation = dp(if (hasFocus) 12 else 2).toFloat()
        }
        card.setOnClickListener {
            onItemClicked?.invoke(cardTitles[card] ?: title)
        }
        return card
    }

    /**
     * 拉取首页内容。优先用站点的推荐接口, 失败则尝试通用搜索接口。
     */
    private fun fetchHomeItems(baseUrl: String): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()

        // 1) 尝试 /api/sources 或 /api/home —— 存在就用
        for (path in listOf("/api/home", "/api/recommend", "/api/sources")) {
            try {
                val body = httpGet(baseUrl + path)
                val arr = JSONArray(body)
                for (i in 0 until minOf(arr.length(), 30)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").ifBlank { o.optString("title") }
                    if (name.isNotBlank()) out.add(name to o.optString("poster").ifBlank { null })
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                Log.i(TAG, "endpoint $path failed: ${e.message}")
            }
        }

        // 2) 退化为已知热门片名 (离线可用)
        return listOf(
            "琅琊榜" to null, "庆余年" to null, "狂飙" to null, "三体" to null,
            "流浪地球" to null, "无间道" to null, "肖申克的救赎" to null, "盗梦空间" to null,
            "你的名字" to null, "千与千寻" to null, "哪吒之魔童降世" to null, "长津湖" to null
        )
    }

    private fun loadCategory(baseUrl: String, category: String) {
        // 分类在原生模式下只做视觉反馈 —— 真正的分类筛选走网页版
        toastMsg = "「$category」将在网页版中筛选"
    }

    var toastMsg: String? = null

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 KVideo-AndroidTV")
        }
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    fun release() {
        executor.shutdownNow()
    }
}

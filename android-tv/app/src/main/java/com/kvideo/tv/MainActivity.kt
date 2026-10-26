package com.kvideo.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.net.http.SslError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "kvideo_tv_settings"
        private const val PREF_SERVER_URL = "server_url"
        private const val TAG = "KVideoMainActivity"
        /** 同一播放地址的去重窗口 (毫秒) */
        private const val NATIVE_PLAY_DEDUP_MS = 3000L
    }

    private lateinit var webView: WebView
    private lateinit var setupContainer: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var openButton: Button
    private lateinit var saveButton: Button
    private lateinit var prefs: android.content.SharedPreferences
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var wasSetupVisibleBeforeFullscreen = false
    private lateinit var appUpdater: AppUpdater
    private var errorOverlay: View? = null
    private var tvWall: TvWallView? = null
    private var lastLoadError: String? = null
    private var lastLoadedUrl: String = ""
    /** 页面加载开始时间 (elapsedRealtime 基准) */
    private var pageLoadStartedAt: Long = 0L
    /** 页面是否已 onPageFinished */
    private var pageLoadFinished: Boolean = false
    /** 超时兜底定时器 */
    private var loadTimeoutRunnable: Runnable? = null
    /**
     * 最近一次交给原生播放器的地址 + 时间戳。
     * 站点的 React effect 可能重跑 (StrictMode / 依赖变化), 不做幂等会重复拉起播放器。
     */
    private var lastNativePlayUrl: String = ""
    private var lastNativePlayAt: Long = 0L
    private var debugDialog: android.app.Dialog? = null
    private var nativeHome: androidx.compose.ui.platform.ComposeView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        setContentView(R.layout.activity_main)
        installProbeBridge()
        webView = findViewById(R.id.webview)
        setupContainer = findViewById(R.id.setup_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        urlInput = findViewById(R.id.url_input)
        statusText = findViewById(R.id.status_text)
        openButton = findViewById(R.id.open_button)
        saveButton = findViewById(R.id.save_button)

        saveButton.setOnClickListener {
            // 电视设备: 默认走原生电视墙 (Compose for TV), WebView 留给详情/播放/搜索
        if (isTvDevice()) {
            showTvWall()
        } else {
            openConfiguredUrl()
        }
        }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            val isKeyboardConfirmAction = actionId == EditorInfo.IME_NULL ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_NEXT
            val isEnterKey = event?.action == KeyEvent.ACTION_DOWN && (
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                )

            if (isKeyboardConfirmAction || isEnterKey) {
                openConfiguredUrl()
                true
            } else {
                false
            }
        }

        // ---- 电视端诊断层: 加载失败时可见 (避免白屏无信息) ----
        installErrorOverlay()

        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                databaseEnabled = true
                // 注入 Android TV UA, 让 KVideo 前端 TV detection 命中 (TCL/小米/海信等非 Google TV 也覆盖)
                userAgentString = settings.userAgentString + " Android TV"
                // 电视 WebView 常见问题: 图片解码/合成被限制, 关掉这些可让 <img> 正常渲染
                blockNetworkImage = false
                blockNetworkLoads = false
                loadsImagesAutomatically = true
                // 允许 WebView 自己处理视频, 否则播放端全屏时遥控键被吞
                setGeolocationEnabled(false)
            }
            // 电视上 LAYER_TYPE_HARDWARE 在部分厂商 GPU 上会白屏/花屏, 用 SOFTWARE 更稳
            if (isTvDevice()) {
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    lastLoadedUrl = url ?: lastLoadedUrl
                    lastLoadError = null
                    hideErrorOverlay()
                    startLoadTimeoutWatchdog()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    lastLoadedUrl = url ?: lastLoadedUrl
                    pageLoadFinished = true
                    cancelLoadTimeoutWatchdog()
                    // 页面加载完做一次能力探测 (延迟等 React 挂载完)
                    webView.postDelayed({ probeWebViewCapabilities() }, 2500)
                    // 页面加载完成但内容为空 —— 电视上常见空 DOM, 也当作失败处理
                    view?.postDelayed({
                        view.evaluateJavascript(
                            "document.body ? document.body.innerText.trim().length : -1"
                        ) { value ->
                            val n = value?.trim()?.toIntOrNull() ?: -1
                            if (n <= 0) {
                                showErrorOverlay("页面内容为空", "页面已加载但没有任何内容\n可能是前端脚本未执行")
                            }
                        }
                    }, 4000)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorRequest: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, errorRequest, error)
                    val code = error?.errorCode ?: -1
                    val desc = error?.description?.toString() ?: "未知错误"
                    // 只处理主文档错误, 子资源失败不打扰
                    if (errorRequest?.isForMainFrame != true) return
                    val msg = when (code) {
                        -2 -> "网络连接失败 (ERR_HOST_UNREACHABLE)"
                        -6 -> "无法解析域名 (ERR_NAME_NOT_RESOLVED)"
                        -7 -> "无法连接服务器 (ERR_CONNECTION_REFUSED)"
                        -8 -> "连接超时 (ERR_CONNECTION_TIMED_OUT)"
                        -10 -> "不支持的协议 (ERR_UNSUPPORTED_SCHEME)"
                        -11 -> "TLS 证书错误 (ERR_BAD_SSL_CERTIFICATE)"
                        else -> "错误码 $code: $desc"
                    }
                    lastLoadError = msg
                    showErrorOverlay("无法打开页面", msg)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame != true) return
                    val code = errorResponse?.statusCode ?: -1
                    lastLoadError = "HTTP $code"
                    showErrorOverlay("服务器返回错误", "HTTP $code ${request.url}")
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    val url = error?.url ?: ""
                    // 只对"私有网络地址"放行自签名/过期证书 —— 家庭路由器上的
                    // 本地调试服务器常带自签证书, 电视上直接报错等于白屏。
                    // 公网域名 (kvideo.djjk.cc 等) 的证书错误必须拒绝, 否则
                    // 中间人可以完全劫持电视流量。
                    if (isPrivateNetworkUrl(url)) {
                        Log.w(TAG, "SSL error on private host $url: ${error?.primaryError} (放行)")
                        handler?.proceed()
                        lastLoadError = "SSL 证书异常 (局域网放行)"
                    } else {
                        Log.e(TAG, "SSL error on public host $url: ${error?.primaryError} (拒绝)")
                        handler?.cancel()
                        lastLoadError = "SSL 证书校验失败 (已拒绝)"
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
            // 捕获 JS 运行时错误 (电视上遥控失效/图片不显示的真因)
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                try {
                    if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        jsErrors.add("[" + msg.sourceId().substringAfterLast('/') + ":" + msg.lineNumber() + "] " + msg.message().take(220))
                        if (jsErrors.size > 40) jsErrors.poll()
                    }
                } catch (e: Exception) { }
                return super.onConsoleMessage(msg)
            }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || callback == null) {
                        callback?.onCustomViewHidden()
                        return
                    }

                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }

                    wasSetupVisibleBeforeFullscreen = isSetupVisible()
                    customView = view
                    customViewCallback = callback

                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fullscreenContainer.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                    setupContainer.visibility = View.GONE
                    applyImmersiveMode()
                }

                override fun onHideCustomView() {
                    exitCustomFullscreen()
                }
            }
            addJavascriptInterface(AndroidPlayerBridge(), "KVideoAndroid")
        }

        appUpdater = AppUpdater(this)

        val configuredUrl = getConfiguredUrl()
        if (configuredUrl.isNotEmpty()) {
            urlInput.setText(configuredUrl)
            loadConfiguredUrl(configuredUrl)
            // 自动更新检查: 延迟启动, 不与首屏渲染抢资源
            appUpdater.scheduleStartupCheck()
        } else {
            showSetup(getString(R.string.status_first_launch))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isSetupVisible() && keyCode == KeyEvent.KEYCODE_BACK && customView != null) {
            exitCustomFullscreen()
            return true
        }

        if (!isSetupVisible() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            // 菜单键: 打开诊断信息 —— 电视端出问题时能直接看到 UA / URL / 错误原因
            showDebugDialog()
            return true
        }

        // Map D-pad center to Enter for spatial navigation
        // 原生电视墙模式下让系统处理焦点确认, 不派发给 WebView
        if (!isSetupVisible() && keyCode == KeyEvent.KEYCODE_DPAD_CENTER && !isTvWallVisible()) {
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isSetupVisible() && keyCode == KeyEvent.KEYCODE_DPAD_CENTER && !isTvWallVisible()) {
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (customView != null) {
            exitCustomFullscreen()
            return
        }

        if (isSetupVisible()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }

        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            showSetup(getString(R.string.status_settings_hint))
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        // 回到前台时再检查一次更新 (静默, 同版本不重复提示)
        if (::appUpdater.isInitialized) {
            val url = getConfiguredUrl()
            if (url.isNotBlank()) {
                appUpdater.checkForUpdate(silent = true)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dispatchPictureInPictureChange(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            applyImmersiveMode()
        }
    }

    override fun onDestroy() {
        exitCustomFullscreen()
        webView.destroy()
        super.onDestroy()
    }

    fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun openConfiguredUrl() {
        hideTvWall()
        hideNativeHome()
        val normalizedUrl = normalizeUrl(urlInput.text.toString())
        if (!isValidUrl(normalizedUrl)) {
            statusText.text = getString(R.string.status_invalid_url)
            urlInput.requestFocus()
            return
        }

        prefs.edit().putString(PREF_SERVER_URL, normalizedUrl).apply()
        loadConfiguredUrl(normalizedUrl)
    }

    private fun loadConfiguredUrl(url: String) {
        setupContainer.visibility = View.GONE
        statusText.text = getString(R.string.status_ready)
        webView.loadUrl(url)
    }

    private fun showSetup(message: String) {
        val currentUrl = getConfiguredUrl()
        if (currentUrl.isNotEmpty() && urlInput.text.toString().isBlank()) {
            urlInput.setText(currentUrl)
        }

        if (currentUrl.isNotEmpty()) {
            openButton.text = getString(R.string.button_open_saved)
            openButton.setOnClickListener {
                urlInput.setText(currentUrl)
                loadConfiguredUrl(currentUrl)
            }
        } else {
            openButton.text = getString(R.string.button_exit)
            openButton.setOnClickListener {
                finish()
            }
        }

        statusText.text = message
        setupContainer.visibility = View.VISIBLE
        urlInput.requestFocus()
    }

    private fun getConfiguredUrl(): String {
        val savedUrl = prefs.getString(PREF_SERVER_URL, null)?.trim().orEmpty()
        if (isValidUrl(savedUrl)) {
            return savedUrl
        }

        val defaultUrl = normalizeUrl(BuildConfig.DEFAULT_KVIDEO_URL)
        return if (isValidUrl(defaultUrl)) defaultUrl else ""
    }

    private fun isSetupVisible(): Boolean = setupContainer.visibility == View.VISIBLE

    private fun normalizeUrl(rawUrl: String): String = UrlNormalizer.normalize(rawUrl)

    private fun isValidUrl(url: String): Boolean = UrlNormalizer.isValid(url)

    private fun isPrivateNetworkUrl(url: String): Boolean = UrlNormalizer.isPrivateNetworkUrl(url)


    // ============================================================
    // 电视端诊断层
    // ============================================================

    /**
     * 注入错误覆盖层。WebView 加载失败时显示中文原因 + 重试/电视墙/设置三个出口。
     */
    private fun installErrorOverlay() {
        val root = findViewById<android.widget.FrameLayout>(R.id.root_container) ?: return
        val view = layoutInflater.inflate(R.layout.view_load_error, root, false)
        root.addView(view)
        errorOverlay = view

        view.findViewById<Button>(R.id.error_retry_button).setOnClickListener {
            hideErrorOverlay()
            retryLoad()
        }
        view.findViewById<Button>(R.id.error_wall_button).setOnClickListener {
            hideErrorOverlay()
            showTvWall()
        }
        view.findViewById<Button>(R.id.error_settings_button).setOnClickListener {
            hideErrorOverlay()
            showSetup(getString(R.string.status_settings_hint))
        }
    }

    /**
     * 启动页面加载超时看门狗。
     *
     * 电视上页面卡住时不会自动报错, 只会一直白屏, 用户按遥控器没反应。
     * 12 秒还没 onPageFinished 就显示可操作的错误页 (重试 / 返回 / 设置)。
     */
    private fun startLoadTimeoutWatchdog() {
        cancelLoadTimeoutWatchdog()
        pageLoadStartedAt = android.os.SystemClock.elapsedRealtime()
        pageLoadFinished = false
        val runnable = Runnable {
            val elapsed = android.os.SystemClock.elapsedRealtime() - pageLoadStartedAt
            when (LoadTimeoutPolicy.decide(pageLoadFinished, elapsed)) {
                LoadTimeoutPolicy.Decision.TIMEOUT -> {
                    Log.w("KVideoLoad", "page load timeout after ${elapsed}ms: $lastLoadedUrl")
                    lastLoadError = "加载超时"
                    showErrorOverlay(
                        "页面加载超时",
                        "超过 ${elapsed / 1000} 秒仍未加载完成\n可能是网络较慢或服务器暂时不可用"
                    )
                }
                else -> Unit
            }
        }
        loadTimeoutRunnable = runnable
        webView.postDelayed(runnable, LoadTimeoutPolicy.DEFAULT_TIMEOUT_MS)
    }

    private fun cancelLoadTimeoutWatchdog() {
        loadTimeoutRunnable?.let { webView.removeCallbacks(it) }
        loadTimeoutRunnable = null
    }

    private fun showErrorOverlay(title: String, detail: String) {
        val v = errorOverlay ?: return
        runOnUiThread {
            v.findViewById<TextView>(R.id.error_title).text = title
            v.findViewById<TextView>(R.id.error_message).text = title
            v.findViewById<TextView>(R.id.error_detail).text = getString(
                R.string.error_detail_format,
                lastLoadedUrl.ifBlank { "(未加载)" },
                detail,
                webView.settings.userAgentString.take(60)
            )
            v.visibility = View.VISIBLE
            v.bringToFront()
        }
    }

    private fun hideErrorOverlay() {
        errorOverlay?.visibility = View.GONE
    }

    /**
     * 重新加载当前地址。
     */
    private fun retryLoad() {
        val url = getConfiguredUrl()
        if (url.isBlank()) {
            showSetup(getString(R.string.status_first_launch))
            return
        }
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    /**
     * 切换到原生电视墙 (不依赖 WebView / 网站 JS)。
     */
    private fun showTvWall() {
        val root = findViewById<android.widget.FrameLayout>(R.id.root_container) ?: return

        // 已存在原生 Compose 电视墙 -> 直接显示
        nativeHome?.let {
            it.visibility = View.VISIBLE
            return
        }

        // 已有旧版 View 电视墙 -> 复用
        tvWall?.let {
            it.visibility = View.VISIBLE
            it.build(getConfiguredUrl())
            return
        }

        // 优先: 原生 Compose for TV 电视墙 (焦点由系统管理, 电视上最可靠)
        try {
            val compose = androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    TvHomeScreen(
                        baseUrl = getConfiguredUrl(),
                        onOpenWeb = { url ->
                            hideNativeHome()
                            webView.visibility = View.VISIBLE
                            webView.loadUrl(url)
                        }
                    )
                }
                setBackgroundColor(android.graphics.Color.parseColor("#0B0D12"))
            }
            nativeHome = compose
            root.addView(compose, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            compose.requestFocus()
            return
        } catch (e: Exception) {
            Log.e(TAG, "native home unavailable, fallback to view wall", e)
        }

        // 兜底: 旧版 View 电视墙
        val wall = TvWallView(this)
        tvWall = wall
        root.addView(wall, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        wall.build(getConfiguredUrl())
        wall.onRetryWebsite = {
            wall.visibility = View.GONE
            webView.visibility = View.VISIBLE
            retryLoad()
        }
        wall.onSearchRequested = {
            wall.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(getConfiguredUrl() + "?focus=search")
        }
    }

    private fun hideNativeHome() {
        nativeHome?.visibility = View.GONE
    }

    private fun isNativeHomeVisible(): Boolean = nativeHome?.visibility == View.VISIBLE

    private fun hideTvWall() {
        tvWall?.visibility = View.GONE
    }

    /**
     * 判断是否电视设备 (非手机)。TV 上默认使用原生电视墙。
     *
     * 注意: UI_MODE_SERVICE 返回的是 UiModeManager, 不是 Configuration ——
     * 之前这里 cast 成 Configuration, 运行时必然 ClassCastException 被 catch 吞掉,
     * 导致 isTvDevice() 永远返回 false。直接读 resources.configuration 才对。
     */
    private fun isTvDevice(): Boolean {
        return try {
            val uiMode = resources.configuration.uiMode
            uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        } catch (e: Exception) {
            false
        }
    }

    private fun isTvWallVisible(): Boolean =
        tvWall?.visibility == View.VISIBLE || nativeHome?.visibility == View.VISIBLE

    /**
     * 查询当前生效的 WebView 组件包名 (电视上系统 WebView 版本差异是常见坑)。
     */
    private fun currentWebViewPackageName(): String {
        return try {
            val provider = Class.forName("android.webkit.WebViewFactory")
            val m = provider.getDeclaredMethod("getProvider")
            m.isAccessible = true
            val p = m.invoke(null)
            val cm = p.javaClass.getDeclaredMethod("getPackageName")
            cm.isAccessible = true
            cm.invoke(p) as? String ?: "(未知)"
        } catch (e: Exception) {
            "(查询失败: ${e.javaClass.simpleName})"
        }
    }

    /**
     * 诊断对话框: 显示 UA / URL / 加载状态 / WebView 版本, 便于远程判断电视端问题。
     */

    // ================= TV 真机诊断: JS 错误 + WebView 能力探测 =================

    /** 收集 WebView 里的 JS 运行时错误 (遥控失效/图片不显示时唯一的真相来源) */
    private val jsErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** WebView 能力探测结果 (JS 执行后回填) */
    @Volatile private var jsCapabilities: String = "(未探测)"

    /**
     * 在页面里注入能力探测脚本。
     * 老电视 WebView (Chrome 83 / 厂商魔改) 常常缺 API, 导致 React/Next 运行时直接抛错,
     * 结果就是: HTML 文字在, 但图片不显示、遥控器完全无效。
     */
    private fun probeWebViewCapabilities() {
        val js = """
        (function(){
          function has(n){ try { return typeof window[n] !== 'undefined'; } catch(e){ return false; } }
          var checks = [
            'TextDecoder','TextEncoder','fetch','AbortController','IntersectionObserver',
            'ResizeObserver','MutationObserver','structuredClone','URL','URLSearchParams',
            'Promise','Symbol','Map','Set','WeakMap','Proxy','Reflect',
            'requestAnimationFrame','matchMedia','requestIdleCallback',
            'Object.fromEntries','Object.entries','Array.from','Array.includes',
            'String.prototype.padStart','String.prototype.replaceAll',
            'Element.prototype.matches','Element.prototype.closest'
          ];
          var ok = [], miss = [];
          checks.forEach(function(n){
            var parts = n.split('.');
            var cur = window, good = true;
            for (var i=0;i<parts.length;i++){
              if (cur == null) { good=false; break; }
              cur = cur[parts[i]];
            }
            (good ? ok : miss).push(n);
          });
          // CSS 特性
          var css = [];
          try {
            if (!CSS.supports('aspect-ratio','1/1')) css.push('no aspect-ratio');
            if (!CSS.supports('selector(:has(*))')) css.push('no :has()');
            if (!CSS.supports('content-visibility','hidden')) css.push('no content-visibility');
            if (!CSS.supports('gap','1px')) css.push('no gap');
          } catch(e){ css.push('CSS.supports ERR '+e.message); }
          var out = 'OK[' + ok.length + ']: ' + ok.join(',') + ' || MISS[' + miss.length + ']: ' + miss.join(',')
                    + ' || CSS: ' + (css.length?css.join(';'):'all-ok')
                    + ' || UA: ' + navigator.userAgent.slice(0,120);
          if (window.React) out += ' || React:' + React.version;
          if (window.next) out += ' || next.router:' + (next.version||'?');
          try { out += ' || viewport:' + window.innerWidth + 'x' + window.innerHeight + ' dpr:' + window.devicePixelRatio; } catch(e){}
          try {
            var imgs = document.querySelectorAll('img');
            var loaded = 0, broken = 0, pending = 0;
            imgs.forEach(function(i){
              if (!i.currentSrc) { pending++; }
              else if (i.complete) { if (i.naturalWidth > 0) loaded++; else broken++; }
              else { pending++; }
            });
            out += ' || IMG total:' + imgs.length + ' loaded:' + loaded + ' broken:' + broken + ' pending:' + pending;
            var sheets = document.styleSheets.length;
            out += ' || sheets:' + sheets;
          } catch(e){ out += ' || IMG ERR ' + e.message; }
          if (window.__kvideoProbe) window.__kvideoProbe(out);
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /** 在 WebView 里注册一个 JS 桥, 能力探测结果通过它回传 */
    private fun installProbeBridge() {
        try {
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun report(result: String) {
                    jsCapabilities = result
                }
            }, "__kvideoProbe")
        } catch (e: Exception) {
            Log.w(TAG, "probe bridge failed", e)
        }
    }

    private fun showDebugDialog() {
        if (debugDialog?.isShowing == true) return
        // 先刷新一次能力探测, 让对话框显示最新数据
        probeWebViewCapabilities()
        val info = buildString {
            appendLine("URL: ${lastLoadedUrl.ifBlank { "(未加载)" }}")
            appendLine("错误: ${lastLoadError ?: "无"}")
            appendLine()
            appendLine("UA: ${webView.settings.userAgentString}")
            appendLine()
            appendLine("WebView: ${currentWebViewPackageName()}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("屏幕: ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
            appendLine("density: ${resources.displayMetrics.density}")
            appendLine()
            appendLine("版本: ${packageManager.getPackageInfo(packageName, 0).versionName} (${packageManager.getPackageInfo(packageName, 0).versionCode})")
            appendLine()
            appendLine("--- JS 能力探测 ---")
            appendLine(jsCapabilities)
            appendLine()
            appendLine("--- JS 错误 (最近 ${jsErrors.size} 条) ---")
            if (jsErrors.isEmpty()) {
                appendLine("(无)")
            } else {
                jsErrors.take(12).forEach { appendLine(it) }
            }
        }

        val scroll = android.widget.ScrollView(this)
        val tv = TextView(this).apply {
            text = info
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(36, 28, 36, 28)
        }
        scroll.addView(tv)

        val d = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.debug_title)
            .setView(scroll)
            .setPositiveButton(R.string.debug_close, null)
            .setNeutralButton(R.string.debug_copy) { _, _ ->
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("kvideo-debug", info))
                toast(getString(R.string.debug_copied))
            }
            .create()
        d.show()
        debugDialog = d
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun exitCustomFullscreen() {
        val currentCustomView = customView ?: return
        fullscreenContainer.removeView(currentCustomView)
        fullscreenContainer.visibility = View.GONE
        customView = null
        webView.visibility = View.VISIBLE
        if (wasSetupVisibleBeforeFullscreen) {
            setupContainer.visibility = View.VISIBLE
        }
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        wasSetupVisibleBeforeFullscreen = false
        applyImmersiveMode()
    }

    private fun isPictureInPictureSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun dispatchPictureInPictureChange(isInPictureInPictureMode: Boolean) {
        val js = """
            window.dispatchEvent(new CustomEvent('kvideo-android-pip-change', {
                detail: { inPictureInPicture: ${if (isInPictureInPictureMode) "true" else "false"} }
            }));
        """.trimIndent()

        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to dispatch Picture-in-Picture change event", error)
            }
        }
    }

    private fun createSourceRectHint(left: Int, top: Int, right: Int, bottom: Int): Rect? {
        if (right <= left || bottom <= top) {
            return null
        }

        val density = resources.displayMetrics.density
        return Rect(
            (left * density).roundToInt(),
            (top * density).roundToInt(),
            (right * density).roundToInt(),
            (bottom * density).roundToInt()
        )
    }

    private inner class AndroidPlayerBridge {
        /**
         * 用原生 Media3 播放器播这个地址。
         *
         * 站点播放页在检测到 App 桥存在时, 会把最终播放地址交给这里,
         * 而不是自己在 WebView 里用 MSE 播 —— 老电视的 WebView 播 HLS 常常黑屏。
         *
         * 返回 true 表示已交给原生播放器, false 表示请站点继续用 WebView 播。
         */
        @JavascriptInterface
        fun playNative(url: String, title: String): Boolean {
            // 幂等: 同一个地址在短时间内只拉起一次 (站点的 effect 会重跑)
            val now = android.os.SystemClock.elapsedRealtime()
            if (NativePlayDedup.shouldSkip(lastNativePlayUrl, lastNativePlayAt, now, url)) {
                Log.i(TAG, "playNative: dedup, same url within ${NativePlayDedup.DEDUP_WINDOW_MS}ms")
                return true
            }

            val siteBase = getConfiguredUrl()
            val resolution = PlaybackUrlResolver.resolve(url, siteBase)
            if (resolution !is PlaybackUrlResolver.Resolution.Native) {
                Log.i(TAG, "playNative: not native-playable, keep WebView: $url")
                return false
            }
            if (!PlaybackUrlResolver.isMedia3Compatible(resolution.url)) {
                Log.w(TAG, "playNative: unsupported scheme, keep WebView: $resolution.url")
                return false
            }
            lastNativePlayUrl = url
            lastNativePlayAt = now
            runOnUiThread {
                try {
                    PlayerActivity.start(
                        context = this@MainActivity,
                        url = resolution.url,
                        title = title
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "playNative: failed to start PlayerActivity", error)
                    // 起不来就退回 WebView
                    Toast.makeText(this@MainActivity, "原生播放器启动失败", Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }

        @JavascriptInterface
        fun isPictureInPictureSupported(): Boolean = this@MainActivity.isPictureInPictureSupported()

        @JavascriptInterface
        fun enterPictureInPicture(
            width: Int,
            height: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ): Boolean {
            if (!this@MainActivity.isPictureInPictureSupported()) {
                return false
            }

            val didEnterPiP = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            runOnUiThread {
                try {
                    val builder = android.app.PictureInPictureParams.Builder()
                    if (width > 0 && height > 0) {
                        builder.setAspectRatio(Rational(width, height))
                    }
                    createSourceRectHint(left, top, right, bottom)?.let(builder::setSourceRectHint)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        builder.setSeamlessResizeEnabled(true)
                    }
                    didEnterPiP.set(enterPictureInPictureMode(builder.build()))
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Failed to enter Picture-in-Picture mode", error)
                } finally {
                    latch.countDown()
                }
            }

            return try {
                latch.await(1500, TimeUnit.MILLISECONDS)
                didEnterPiP.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while waiting for Picture-in-Picture result", error)
                false
            }
        }
    }
}

package com.reze.offline
import android.graphics.PixelFormat
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient.FileChooserParams
import java.io.ByteArrayInputStream
import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : Activity() {

    companion object {
        // 使用虚拟 https 域名（AndroidX WebViewAssetLoader 同款约定），
        // 让页面拥有真实的 https origin —— 解决 BetterAuth 拒绝 file:// 的问题
        const val VIRTUAL_HOST = "appassets.androidplatform.net"
        const val URL_HOME = "https://appassets.androidplatform.net/index.html"
        // v8.3 新增：联网模式直连线上站点
        const val ONLINE_URL = "https://reze.design/"
        const val APP_VERSION = "v1.0"
        const val APP_VERSION_NOTE = "Reze Design · IK 单骨骼控制"
        const val APP_NAME = "Reze Design"
        const val TAG_LOG = "RezeDL"

        private const val DP_BTN_HEIGHT = 44
        private const val DP_TOOLBAR_PAD = 8
        private const val GLASS_BASE_ALPHA = 0.52f
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var isLandscape = false

    private var backgroundView: View? = null
    private var glassToolbar: View? = null
    private var blurAnimator: ValueAnimator? = null
    private var blobAnimator1: ValueAnimator? = null
    private var blobAnimator2: ValueAnimator? = null
    private var blobAnimator3: ValueAnimator? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // ===== v8.3 联网/离线双模式 =====
    private var isOnline = false
    private var onlineBtn: TextView? = null
    private val prefs by lazy { getSharedPreferences("reze_mode", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        tryEnableWebGPU()
        setupWebView()
        registerDownloadBridge()
        requestStoragePermission()
        isOnline = prefs.getBoolean("online", false)
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(if (isOnline) ONLINE_URL else URL_HOME)
        }
        isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        webView.saveState(out)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        blurAnimator?.cancel()
        blobAnimator1?.cancel()
        blobAnimator2?.cancel()
        blobAnimator3?.cancel()
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            val cb = fileChooserCallback ?: run {
                android.util.Log.w(TAG_LOG, "[FILE] no callback pending")
                return
            }
            fileChooserCallback = null
            if (resultCode != RESULT_OK || data == null) {
                android.util.Log.i(TAG_LOG, "[FILE] cancelled")
                cb.onReceiveValue(null)
                return
            }
            val uris: Array<Uri>? = when {
                data.clipData != null -> {
                    val list = ArrayList<Uri>()
                    for (i in 0 until data.clipData!!.itemCount) {
                        data.clipData!!.getItemAt(i).uri?.let { list.add(it) }
                    }
                    list.toTypedArray()
                }
                else -> {
                    val u = data.data
                    if (u == null) null else arrayOf(u)
                }
            }
            android.util.Log.i(TAG_LOG, "[FILE] selected count=${uris?.size ?: 0} first=${uris?.firstOrNull()}")
            cb.onReceiveValue(uris)
        }
    }

    // ==================== WebGPU 反射 ====================
    private fun tryEnableWebGPU() {
        try {
            val webviewClass = Class.forName("android.webkit.WebView")
            val enableMethod = webviewClass.getMethod("enableFeature", String::class.java)
            val features = arrayOf(
                "ENABLED_WEBGPU",
                "ENABLED_WEBGL",
                "ENABLED_WEBASSEMBLY",
                "WEBGPU"
            )
            for (feature in features) {
                try {
                    enableMethod.invoke(webView, feature)
                    android.util.Log.i(TAG_LOG, "[GPU] enableFeature($feature) called")
                    try {
                        val supportedMethod = webviewClass.getMethod("isFeatureSupported", String::class.java)
                        val supported = supportedMethod.invoke(webView, feature) as? Boolean ?: false
                        android.util.Log.i(TAG_LOG, "[GPU] $feature supported=$supported")
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    android.util.Log.w(TAG_LOG, "[GPU] enableFeature($feature) failed: ${e.message}")
                }
            }
            try {
                val configMethod = webviewClass.getMethod("getConfiguration", android.webkit.WebSettings::class.java)
                configMethod.invoke(webView, webView.settings)
                android.util.Log.i(TAG_LOG, "[GPU] getConfiguration applied")
            } catch (_: Exception) {}
        } catch (_: Exception) {
            android.util.Log.w(TAG_LOG, "[GPU] tryEnableWebGPU outer failed")
        }
    }

    private fun getDp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 布局 ====================
    private fun buildLayout() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#05060B"))

        // ==================== 动态渐变背景（3 个大 blob + 噪点底色） ====================
        val bg = View(this)
        backgroundView = bg
        root.addView(bg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        bg.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                bg.viewTreeObserver.removeOnGlobalLayoutListener(this)
                startBackgroundAnimation(bg)
            }
        })

        // ==================== 上层布局 ====================
        val upper = FrameLayout(this)
        root.addView(upper, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // WebView 容器（含进度条）
        val contentFrame = FrameLayout(this)
        webView = WebView(this)
        contentFrame.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        try {
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#7ee787")))
        } catch (_: Exception) {}
        contentFrame.addView(progressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, getDp(3), Gravity.TOP
        ))
        val contentParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentParams.topMargin = getDp(4) + getDp(DP_BTN_HEIGHT) + getDp(4)
        upper.addView(contentFrame, contentParams)

        // ==================== 顶部玻璃工具栏（v6 新增，悬浮） ====================
        val tbH = getDp(DP_BTN_HEIGHT)
        val padH = getDp(DP_TOOLBAR_PAD)

        val toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(padH, getDp(6), padH, getDp(6))
        toolbar.background = makeGlassBg(getDp(22), GLASS_BASE_ALPHA, true)

        // 左侧：标题 + 版本号
        val leftCol = LinearLayout(this)
        leftCol.orientation = LinearLayout.VERTICAL
        leftCol.gravity = Gravity.CENTER_VERTICAL
        leftCol.setPadding(getDp(12), getDp(2), getDp(10), getDp(2))

        val title = TextView(this)
        title.text = "\uD83C\uDFAC $APP_NAME"
        title.setTextColor(Color.parseColor("#E6F7EA"))
        title.textSize = 15f
        title.setTypeface(title.typeface, Typeface.BOLD)

        val ver = TextView(this)
        ver.text = APP_VERSION
        ver.setTextColor(Color.parseColor("#B0B8C4"))
        ver.textSize = 10f

        leftCol.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        leftCol.addView(ver, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        toolbar.addView(leftCol, LinearLayout.LayoutParams(0, tbH, 1f))

        // 按钮组
        val btnW = getDp(44)
        val btnPad = getDp(10)

        val refreshBtn = makeGlassBtn(this, "\u21BB", "#E6F7EA", "刷新")
        refreshBtn.setOnClickListener {
            webView.reload()
            android.util.Log.i(TAG_LOG, "[UI] refresh clicked")
        }
        toolbar.addView(refreshBtn, LinearLayout.LayoutParams(btnW, tbH))


        // 旋转按钮（v6 新增）
        val rotateBtn = makeGlassBtn(this, "\u27F3", "#E6F7EA", "旋转")
        rotateBtn.setOnClickListener {
            toggleOrientation()
            android.util.Log.i(TAG_LOG, "[UI] rotate clicked -> landscape=$isLandscape")
        }
        toolbar.addView(rotateBtn, LinearLayout.LayoutParams(btnW, tbH))
        // ===== v8.3 联网 / 离线 切换按钮 =====
        onlineBtn = makeGlassBtn(this, "🌐 联网", "#8FE3FF", "切换联网/离线")
        onlineBtn!!.textSize = 12f
        onlineBtn!!.setPadding(getDp(8), 0, getDp(8), 0)
        onlineBtn!!.setOnClickListener {
            toggleOnlineMode()
        }
        toolbar.addView(onlineBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, tbH))
        // 让 toolbar 悬浮在顶部（留出状态栏 + 4dp 间隙）
        val toolbarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            tbH
        )
        toolbarParams.gravity = Gravity.TOP
        toolbarParams.leftMargin = padH
        toolbarParams.rightMargin = padH
        toolbarParams.topMargin = getDp(4)
        upper.addView(toolbar, toolbarParams)

        setContentView(root)
        glassToolbar = toolbar
    }

    // ==================== 玻璃拟态背景工具 ====================

    /**
     * 生成玻璃拟态背景：
     * - 底色：半透明深灰 + 白色 1px 描边 + 柔和阴影
     * - 左上高光渐变模拟"透光"
     */
    private fun makeGlassBg(radiusPx: Int, alphaF: Float, highlight: Boolean): Drawable {
        val layer = android.graphics.drawable.LayerDrawable(
            arrayOf(
                // 阴影层
                object : Drawable() {
                    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        setShadowLayer(24f, 0f, 8f, Color.parseColor("#33000000"))
                    }
                    override fun draw(canvas: Canvas) {
                        val r = bounds
                        canvas.drawRoundRect(RectF(r), radiusPx.toFloat(), radiusPx.toFloat(), p)
                    }
                    override fun setAlpha(a: Int) {}
                    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                    override fun getOpacity() = PixelFormat.TRANSLUCENT
                },
                // 玻璃主体
                object : Drawable() {
                    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                    }
                    override fun draw(canvas: Canvas) {
                        val r = bounds
                        val glassColor = Color.argb((alphaF * 255).toInt(), 18, 20, 30)
                        p.shader = null
                        p.color = glassColor
                        canvas.drawRoundRect(RectF(r), radiusPx.toFloat(), radiusPx.toFloat(), p)
                        // 白色描边
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 1f
                        p.color = Color.argb(70, 255, 255, 255)
                        canvas.drawRoundRect(RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f), radiusPx.toFloat(), radiusPx.toFloat(), p)
                    }
                    override fun setAlpha(a: Int) {}
                    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                    override fun getOpacity() = PixelFormat.TRANSLUCENT
                },
                // 顶部高光
                if (highlight) object : Drawable() {
                    override fun draw(canvas: Canvas) {
                        val r = bounds
                        val rect = RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom * 0.55f)
                        val shader = LinearGradient(
                            0f, rect.top, 0f, rect.bottom,
                            intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                            null, Shader.TileMode.CLAMP
                        )
                        val p = Paint(Paint.ANTI_ALIAS_FLAG)
                        p.shader = shader
                        val path = Path()
                        val rr = radiusPx.toFloat()
                        path.addRoundRect(rect, rr, rr, Path.Direction.CW)
                        canvas.drawPath(path, p)
                    }
                    override fun setAlpha(a: Int) {}
                    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                    override fun getOpacity() = PixelFormat.TRANSLUCENT
                } else null
            ).filterNotNull().toTypedArray()
        )
        return layer
    }

    /** 胶囊背景（用于 Chrome 主 CTA） */
    private fun makeCapsuleBg(colorHex: String, alphaF: Float): Drawable {
        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                val r = bounds
                val rr = r.height() / 2f
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.parseColor(colorHex).let {
                        Color.argb((alphaF * 255).toInt(), Color.red(it), Color.green(it), Color.blue(it))
                    }
                }
                canvas.drawRoundRect(RectF(r), rr, rr, p)
                // 顶部小高光
                val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, r.top + 2f, 0f, r.top + r.height() * 0.4f,
                        intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                        null, Shader.TileMode.CLAMP
                    )
                }
                val path = Path()
                path.addRoundRect(RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom * 0.5f), rr, rr, Path.Direction.CW)
                canvas.drawPath(path, hi)
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }.also {
            it.setTint(Color.TRANSPARENT)
        }
    }

    /** 玻璃按钮（圆形/胶囊形图标按钮） */
    private fun makeGlassBtn(ctx: Context, text: String, colorHex: String, tag: String): TextView {
        val btn = TextView(ctx)
        btn.text = text
        btn.setTextColor(Color.parseColor(colorHex))
        btn.textSize = 15f
        btn.gravity = Gravity.CENTER
        btn.background = makeGlassBg(getDp(16), 0.60f, true)
        btn.setMinWidth(getDp(44))
        btn.setMinHeight(getDp(44))
        btn.setPadding(getDp(2), 0, getDp(2), 0)
        btn.setContentDescription(tag)
        return btn
    }

    // ==================== 动态背景动画（3 个 blob） ====================
    private fun startBackgroundAnimation(bg: View) {
        val ctx = bg.context
        bg.background = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val p1 = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val p2 = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val p3 = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            override fun draw(canvas: android.graphics.Canvas) {
                val w = bounds.width(); val h = bounds.height()
                if (w <= 0 || h <= 0) return
                val t = System.currentTimeMillis() / 1000f
                val bx1 = w * (0.25f + 0.15f * Math.cos(t * 0.08).toFloat())
                val by1 = h * (0.30f + 0.12f * Math.sin(t * 0.11).toFloat())
                val br1 = w * 0.55f
                val bx2 = w * (0.80f + 0.12f * Math.sin(t * 0.09 + 1.0).toFloat())
                val by2 = h * (0.60f + 0.15f * Math.cos(t * 0.07).toFloat())
                val br2 = w * 0.50f
                val bx3 = w * (0.50f + 0.18f * Math.cos(t * 0.06 + 2.0).toFloat())
                val by3 = h * (0.85f + 0.10f * Math.sin(t * 0.10).toFloat())
                val br3 = w * 0.45f

                paint.shader = null
                paint.color = android.graphics.Color.parseColor("#05060B")
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

                val g1 = android.graphics.RadialGradient(bx1, by1, br1,
                    intArrayOf(android.graphics.Color.argb(140, 90, 120, 220), android.graphics.Color.argb(0, 90, 120, 220)),
                    null, android.graphics.Shader.TileMode.CLAMP)
                p1.shader = g1
                canvas.drawCircle(bx1, by1, br1, p1)

                val g2 = android.graphics.RadialGradient(bx2, by2, br2,
                    intArrayOf(android.graphics.Color.argb(120, 90, 220, 140), android.graphics.Color.argb(0, 90, 220, 140)),
                    null, android.graphics.Shader.TileMode.CLAMP)
                p2.shader = g2
                canvas.drawCircle(bx2, by2, br2, p2)

                val g3 = android.graphics.RadialGradient(bx3, by3, br3,
                    intArrayOf(android.graphics.Color.argb(110, 180, 100, 220), android.graphics.Color.argb(0, 180, 100, 220)),
                    null, android.graphics.Shader.TileMode.CLAMP)
                p3.shader = g3
                canvas.drawCircle(bx3, by3, br3, p3)

                val dark = android.graphics.LinearGradient(0f, h * 0.5f, 0f, h.toFloat(),
                    intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(180, 5, 6, 11)),
                    null, android.graphics.Shader.TileMode.CLAMP)
                paint.shader = dark
                canvas.drawRect(0f, h * 0.5f, w.toFloat(), h.toFloat(), paint)
                paint.shader = null
            }

            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Suppress("DEPRECATION")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                bg.invalidate()
                handler.postDelayed(this, 33)
            }
        }
        handler.post(ticker)
    }

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        if (isLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            android.util.Log.i(TAG_LOG, "[UI] orientation -> LANDSCAPE")
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.util.Log.i(TAG_LOG, "[UI] orientation -> PORTRAIT")
        }
        updateRotateButton()
    }

    private fun updateRotateButton() {
        // 通过遍历视图找旋转按钮（可选，简化：不改文本）
    }

    /** 判断是否属于线上站点域（reze.design 及其子域 / 常用 CDN 资源域） */
    private fun isOnlineHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host == "reze.design" || host.endsWith(".reze.design")) return true
        if (host == "reze.one" || host.endsWith(".reze.one")) return true
        // 线上站点依赖的静态资源 / 字体 / 图片 CDN
        val cdnSuffix = listOf(
            "vercel.app", "vercel.com", "googleapis.com", "gstatic.com",
            "cloudflare.com", "cloudfront.net", "jsdelivr.net",
            "unpkg.com", "supabase.co", "reze.design",
            // v9.1: scene thumbs/poster.webp live on assets.reze.one; avatars on github/google
            "reze.one",
            "githubusercontent.com", "github.com",
            "googleusercontent.com",
            "cloudflareinsights.com"
        )
        return cdnSuffix.any { host == it || host.endsWith("." + it) }
    }

    // ==================== v8.3 联网 / 离线 切换 ====================
    /**
     * 切换联网/离线：
     *  - 联网：加载 https://reze.design/ （可使用服务器端已发布场景）
     *  - 离线：加载内嵌 assets 版
     */
    private fun toggleOnlineMode() {
        isOnline = !isOnline
        prefs.edit().putBoolean("online", isOnline).apply()
        // 清缓存，避免本地 assets 与线上资源串味
        webView.clearCache(true)
        webView.loadUrl(if (isOnline) ONLINE_URL else URL_HOME)
        updateOnlineButton()
        android.util.Log.i(TAG_LOG, "[UI] mode -> " + (if (isOnline) "ONLINE" else "OFFLINE"))
        android.widget.Toast.makeText(this, if (isOnline) "已切换到联网模式" else "已切换到离线模式", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 按钮文字随状态变化：离线时显示“联网”（点击切入），联网时显示“离线”（点击切回） */
    private fun updateOnlineButton() {
        val btn = onlineBtn ?: return
        if (isOnline) {
            btn.text = "🌐 离线"
            btn.setTextColor(Color.parseColor("#FFD9A0"))
        } else {
            btn.text = "🌐 联网"
            btn.setTextColor(Color.parseColor("#8FE3FF"))
        }
    }

    // ==================== Chrome 打开 ====================
    private fun openChrome() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URL_HOME))
        try {
            val pm = packageManager
            pm.getPackageInfo("com.android.chrome", 0)
            intent.setPackage("com.android.chrome")
        } catch (e: PackageManager.NameNotFoundException) {
            // fallback
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e(TAG_LOG, "[Chrome] start failed: ${e.message}")
        }
        android.util.Log.i(TAG_LOG, "[GPU] topbar openChrome clicked")
    }

    // ==================== 关于对话框 ====================
    private fun showAbout() {
        val msg = APP_NAME + " " + APP_VERSION + "\n" + APP_VERSION_NOTE + "\n\n" +
                "【v6 更新】\n" +
                "• 玻璃拟态 UI（iOS Liquid Glass 风格）\n" +
                "• 动态渐变背景（3 个 blob 缓慢漂移）\n" +
                "• 新增旋转按钮（竖屏 / 横屏切换）\n" +
                "• 顶部工具栏悬浮，不遮挡内容\n\n" +
                "【功能清单】\n" +
                "• 内嵌 WebView（UA 伪装 Chrome 131）\n" +
                "• WebGPU 反射尝试\n" +
                "• 下载桥 __RezeDownloadBridge\n" +
                "• 图片导出到 /sdcard/Download/OfflineViewer/\n" +
                "• Chrome 精确拉起（com.android.chrome）\n\n" +
                "构建: versionCode=221, versionName=8.1"
        AlertDialog.Builder(this)
            .setTitle(APP_NAME)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ==================== WebView 设置 ====================
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowFileAccessFromFileURLs = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationDatabasePath(filesDir.absolutePath)
        WebView.setWebContentsDebuggingEnabled(true)
        settings.setGeolocationEnabled(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        try {
            settings.safeBrowsingEnabled = false
        } catch (_: Exception) {}

        // UA 伪装 Chrome 131
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 16; Pixel 8 Build/AP3A.240617.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

        webView.setBackgroundColor(Color.TRANSPARENT)

        // ChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                filePathCallback: FileChooserParams
            ): Boolean {
                try {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = callback
                    val acceptTypes = filePathCallback.acceptTypes?.toList() ?: emptyList()
                    val hasVideo = acceptTypes.any { it.startsWith("video") || it == "*/*" }
                    val hasJson = acceptTypes.any { it.contains("json") || it == "*/*" }
                    val hasScene = acceptTypes.any { it.contains("glb") || it.contains("gltf") || it.contains("3d") || it == "*/*" }
                    val mime = when {
                        acceptTypes.isEmpty() -> "*/*"
                        hasScene -> "*/*"
                        hasVideo -> "video/*"
                        hasJson -> "application/*"
                        else -> { val f = acceptTypes[0]; if (f.startsWith(".") || f.isBlank()) "*/*" else f }
                    }
                    val intent = Intent(Intent.ACTION_GET_CONTENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.type = mime
                    if (acceptTypes.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                    if (filePathCallback.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), 1001)
                    android.util.Log.i(TAG_LOG, "[FILE] chooser opened mime=$mime accept=${acceptTypes.joinToString()}")
                    return true
                } catch (e: Exception) {
                    android.util.Log.e(TAG_LOG, "[FILE] chooser fail: ${e.message}")
                    callback.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = null
                    return false
                }
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i(TAG_LOG, "[JS] " + msg.message() + " @" + msg.sourceId() + ":" + msg.lineNumber())
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                try {
                    val resources = resources
                    for (i in request.resources.indices) {
                        val r = request.resources[i]
                        when (r) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1003)
                                    return
                                }
                            }
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1003)
                                    return
                                }
                            }
                            else -> {}
                        }
                    }
                    request.grant(request.resources)
                } catch (_: Exception) {
                    request.deny()
                }
            }
        }

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                android.util.Log.i(TAG_LOG, "[Page] finished: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    android.util.Log.e(TAG_LOG, "[ERR] main frame failed: ${request.url}")
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val host = url.host ?: ""
                // 虚拟域名 → 从 assets 提供资源，页面即拥有真实 https origin
                if (host == VIRTUAL_HOST) {
                    return serveAsset(url.path ?: "/")
                }
                // v8.3：联网模式下放行线上站点及其 CDN 请求
                if (isOnline && isOnlineHost(host)) {
                    return super.shouldInterceptRequest(view, request)
                }
                val scheme = url.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https") {
                    android.util.Log.w(TAG_LOG, "[OFFLINE] blocked request: $url")
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if ((url.host ?: "") == VIRTUAL_HOST) return false
                // v8.3：联网模式下允许站内导航（reze.design 自身及其 CDN）
                if (isOnline && isOnlineHost(url.host ?: "")) return false
                val scheme = url.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https") {
                    android.util.Log.w(TAG_LOG, "[OFFLINE] blocked navigation: $url")
                    return true
                }
                if (scheme == "file" || scheme == "data" || scheme == "blob" || scheme == "about") {
                    return false
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                    return true
                } catch (_: Exception) {
                    return true
                }
            }
        }
    }

    private fun serveAsset(path: String): WebResourceResponse {
        var p = path
        if (p.startsWith("/")) p = p.substring(1)
        if (p.isEmpty()) p = "index.html"
        // 去掉查询串
        val q = p.indexOf('?'); if (q >= 0) p = p.substring(0, q)
        return try {
            val stream = assets.open(p)
            val mime = guessMime(p)
            val headers = HashMap<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Cache-Control"] = "no-cache"
            WebResourceResponse(mime, null, 200, "OK", headers, stream)
        } catch (e: Exception) {
            android.util.Log.w(TAG_LOG, "[ASSET] 404: $p")
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                HashMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun guessMime(p: String): String {
        val lower = p.lowercase()
        return when {
            lower.endsWith(".html") -> "text/html"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".ico") -> "image/x-icon"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".wasm") -> "application/wasm"
            lower.endsWith(".bin") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    // ==================== 下载桥 ====================
    private val dlTmpDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "RezeViewer"
    )
    private val dlChunks = HashMap<String, ArrayList<ByteArray>>()
    private val dlChunkTotal = HashMap<String, Int>()
    private val dlFiles = ArrayList<String>()

    private fun dlToast(msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    private fun registerDownloadBridge() {
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun log(msg: String) {
                android.util.Log.i(TAG_LOG, msg)
            }
            @JavascriptInterface
            fun saveFile(name: String, dataUrl: String, mime: String): String {
                try {
                    if (!dlTmpDir.exists()) dlTmpDir.mkdirs()
                    val fname = name.substringAfterLast('/').takeIf { it.isNotEmpty() }
                        ?: "export_${System.currentTimeMillis()}.${mime.removePrefix("image/")}"
                    val f = File(dlTmpDir, fname)
                    val marker = "data:"
                    val comma = dataUrl.indexOf(',')
                    val payload = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
                    val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                    f.writeBytes(bytes)
                    android.util.Log.i(TAG_LOG, "[DL] saved ${f.absolutePath} ${bytes.size}B")
                    return f.absolutePath
                } catch (e: Exception) {
                    android.util.Log.e(TAG_LOG, "[DL] saveFile failed: ${e.message}")
                    return ""
                }
            }

            @JavascriptInterface
            fun addChunk(name: String, idx: Int, total: Int, chunk: String, isLast: Boolean) {
                val key = name.substringAfterLast('/').takeIf { it.isNotEmpty() }
                    ?: "reze_${System.currentTimeMillis()}.bin"
                try {
                    synchronized(dlChunks) {
                        if (!dlFiles.contains(key)) {
                            dlFiles.add(key)
                            dlChunkTotal[key] = total
                            dlChunks[key] = ArrayList<ByteArray>(total)
                        }
                        val b = android.util.Base64.decode(chunk, android.util.Base64.DEFAULT)
                        dlChunks[key]!!.add(b)
                        android.util.Log.v(TAG_LOG, "[DL] +chunk $key $idx/$total ${b.size}B")
                    }

                    if (isLast) {
                        val target = File(dlTmpDir, key)
                        if (!dlTmpDir.exists()) dlTmpDir.mkdirs()
                        val os = FileOutputStream(target)
                        var written = 0
                        try {
                            val arr: ArrayList<ByteArray>
                            val totalInt: Int
                            synchronized(dlChunks) {
                                val a = dlChunks[key]
                                val t = dlChunkTotal[key]
                                if (a == null || t == null || t <= 0) {
                                    android.util.Log.w(TAG_LOG, "[DL] finalize empty $key")
                                    return
                                }
                                arr = a
                                totalInt = t
                            }
                            for (i in 0 until totalInt) {
                                if (i < arr.size) {
                                    val piece = arr[i]
                                    os.write(piece)
                                    written += piece.size
                                } else {
                                    android.util.Log.w(TAG_LOG, "[DL] pad missing chunk $i/$totalInt $key")
                                }
                            }
                            os.flush()
                            android.util.Log.i(TAG_LOG, "[DL] saved $key ${target.absolutePath} ${target.length()}B")
                            synchronized(dlChunks) {
                                dlChunks.remove(key)
                                dlChunkTotal.remove(key)
                                dlFiles.remove(key)
                            }
                            // 通知相册/媒体库扫描此文件
                            try {
                                android.media.MediaScannerConnection.scanFile(
                                    this@MainActivity,
                                    arrayOf(target.absolutePath),
                                    null
                                ) { p, _ -> android.util.Log.i(TAG_LOG, "[DL] scanned $p") }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG_LOG, "[DL] scan err: ${e.message}")
                            }
                            dlToast("✅ 已保存: ${target.name}")
                        } catch (e: Exception) {
                            android.util.Log.e(TAG_LOG, "[DL] addChunk finalize err: ${e.message}")
                        } finally {
                            try { os.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG_LOG, "[DL] addChunk failed: ${e.message}")
                }
            }
        }, "__RezeDownloadBridge")
    }

    // ==================== 权限 ====================
    private fun requestStoragePermission() {
        val sdk = android.os.Build.VERSION.SDK_INT
        val needed = mutableListOf<String>()
        if (sdk >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (sdk >= 30) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1002)
        }
    }
}

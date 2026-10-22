package com.solo.teleprompter

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 悬浮提词窗前台服务。
 *
 * - TYPE_APPLICATION_OVERLAY 真悬浮窗，透明底、白字（可调），可整体拖动、右下角缩放。
 * - timed 模式：ValueAnimator 按 speed（逻辑像素/秒）匀速滚动。
 * - voice 模式：内部持有 SpeechEngine，用 ScrollMatcher 把识别全文映射为滚动目标，
 *   目标字符行停在可视高度约 40% 处，已读文字 alpha 降低。
 * - 顶部控制条：播放/暂停、加速、减速、关闭；事件经 eventListener 转发给插件。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.solo.teleprompter.overlay.START"
        const val ACTION_UPDATE = "com.solo.teleprompter.overlay.UPDATE"
        const val ACTION_STOP = "com.solo.teleprompter.overlay.STOP"

        private const val NOTIF_ID = 0x7E11
        private const val CHANNEL_ID = "teleprompter_overlay"

        /** 运行中的服务实例（同进程通信，进程被杀则自然为 null） */
        @Volatile
        var instance: OverlayService? = null

        /** 悬浮窗按钮事件回调：(action, position?) —— 由插件赋值，转发 overlayEvent 给 JS */
        @Volatile
        var eventListener: ((action: String, position: Int?) -> Unit)? = null
    }

    data class PrompterStyle(
        var text: String = "",
        var fontSize: Float = 34f,
        var color: String = "#FFFFFF",
        var bgOpacity: Float = 0.35f,
        var lineSpacing: Float = 1.4f,
        var speed: Float = 40f,
        var mode: String = "voice",
        var speechSource: String = "system",
        var serverUrl: String? = null,
        var lang: String = "zh-CN"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density: Float get() = resources.displayMetrics.density

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var rootView: View? = null
    private var controlBar: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var textView: TextView? = null
    private var playPauseBtn: TextView? = null
    private var speedLabel: TextView? = null
    private var windowAdded = false

    private var style = PrompterStyle()
    private var fullText: String = ""
    private var textColor: Int = Color.WHITE
    private var matcher: ScrollMatcher? = null

    @Volatile
    var isRunning = false
        private set

    private var playing = false
    private var timedAnimator: ValueAnimator? = null
    private var voiceEngine: SpeechEngine? = null
    private var foregroundStarted = false

    /** 最近一次程序驱动滚动的时间戳，用于区分用户手动滚动（voice seek） */
    private var lastProgrammaticScrollAt = 0L

    private val seekRunnable = Runnable { handleUserSeek() }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // 系统重启服务但没有指令，直接停掉避免幽灵悬浮窗
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                style = PrompterStyle()
                readStyleFromIntent(intent)
                // voice 模式且已授权录音时使用 microphone 类型；timed 模式用 specialUse，
                // 避免 Android 14 上未授权录音时 startForeground 抛 SecurityException
                startForegroundCompat(useMicrophone = style.mode != "timed" && micGranted())
                ensureWindow()
                if (!windowAdded) return START_NOT_STICKY
                playing = true
                matcher = null
                applyStyle(textChanged = true, resetScroll = true)
                updatePlayPauseLabel()
                isRunning = true
            }
            ACTION_UPDATE -> {
                if (windowAdded) {
                    readStyleFromIntent(intent)
                    applyStyle(textChanged = false, resetScroll = false)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        stopTimedScroll()
        stopVoiceEngine()
        if (windowAdded) {
            try {
                wm?.removeView(rootView)
            } catch (t: Throwable) {
                // ignore
            }
            windowAdded = false
        }
        rootView = null
        textView = null
        scrollView = null
        controlBar = null
        if (foregroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (t: Throwable) {
                // ignore
            }
            foregroundStarted = false
        }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================================================
    // 前台服务与通知
    // ============================================================

    private fun startForegroundCompat(useMicrophone: Boolean) {
        if (foregroundStarted) return
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = when {
                    useMicrophone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                }
                startForeground(NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            // 未成功进入前台的服务会被系统在 5 秒后判定为 ANR 并崩溃，直接停止
            stopSelf()
        }
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "提词器悬浮窗", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("提词器运行中")
            .setContentText("点击返回提词器")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ============================================================
    // 悬浮窗视图构建
    // ============================================================

    private fun ensureWindow() {
        if (windowAdded) return
        val windowManager = wm ?: return
        val dm = resources.displayMetrics

        // TYPE_APPLICATION_OVERLAY 是 API 26+，23~25 回退 TYPE_PHONE
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.9f).toInt(),
            (dm.heightPixels * 0.35f).toInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (dm.widthPixels * 0.05f).toInt()
        lp.y = (dm.heightPixels * 0.15f).toInt()
        params = lp

        // 顶部控制条
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drag = DragTouchListener()
        bar.setOnTouchListener(drag)

        playPauseBtn = makeButton("暂停").apply {
            setOnClickListener { togglePlayPause() }
        }
        val speedDownBtn = makeButton("减速").apply {
            setOnClickListener { changeSpeed(-10f) }
        }
        speedLabel = makeButton("").apply {
            isClickable = false
        }
        val speedUpBtn = makeButton("加速").apply {
            setOnClickListener { changeSpeed(10f) }
        }
        val closeBtn = makeButton("关闭").apply {
            setOnClickListener {
                emitEvent("close", currentPosition())
                stopSelf()
            }
        }
        // 空白拖动区
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        spacer.setOnTouchListener(drag)

        bar.addView(playPauseBtn)
        bar.addView(speedDownBtn)
        bar.addView(speedLabel)
        bar.addView(speedUpBtn)
        bar.addView(spacer)
        bar.addView(closeBtn)
        controlBar = bar

        // 文本区
        val tv = TextView(this).apply {
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        textView = tv
        val sv = ScrollView(this).apply {
            addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        sv.setOnScrollChangeListener { _, _, _, _, _ -> onContentScrolled() }
        scrollView = sv

        // 右下角缩放手柄
        val resizeHandle = View(this).apply {
            val size = (28 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)
            setBackgroundColor(Color.argb(120, 255, 255, 255))
            setOnTouchListener(ResizeTouchListener())
        }
        val content = FrameLayout(this).apply {
            addView(sv)
            addView(resizeHandle)
        }

        // 根容器
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            ))
            addView(content, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
        rootView = root

        try {
            windowManager.addView(root, lp)
            windowAdded = true
        } catch (t: Throwable) {
            // 无悬浮窗权限等情况：无法显示窗口，服务继续前台运行也无意义
            stopSelf()
        }
    }

    private fun makeButton(label: String): TextView {
        val padH = (10 * density).toInt()
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(padH, 0, padH, 0)
        }
    }

    // ============================================================
    // 样式
    // ============================================================

    private fun readStyleFromIntent(intent: Intent) {
        if (intent.hasExtra("text")) {
            style.text = intent.getStringExtra("text") ?: ""
        }
        if (intent.hasExtra("fontSize")) style.fontSize = intent.getFloatExtra("fontSize", style.fontSize)
        if (intent.hasExtra("color")) style.color = intent.getStringExtra("color") ?: style.color
        if (intent.hasExtra("bgOpacity")) style.bgOpacity = intent.getFloatExtra("bgOpacity", style.bgOpacity)
        if (intent.hasExtra("lineSpacing")) style.lineSpacing = intent.getFloatExtra("lineSpacing", style.lineSpacing)
        if (intent.hasExtra("speed")) style.speed = intent.getFloatExtra("speed", style.speed)
        if (intent.hasExtra("mode")) style.mode = intent.getStringExtra("mode") ?: style.mode
        if (intent.hasExtra("speechSource")) style.speechSource = intent.getStringExtra("speechSource") ?: style.speechSource
        if (intent.hasExtra("serverUrl")) style.serverUrl = intent.getStringExtra("serverUrl")
        if (intent.hasExtra("lang")) style.lang = intent.getStringExtra("lang") ?: style.lang
    }

    private fun applyStyle(textChanged: Boolean, resetScroll: Boolean) {
        val tv = textView ?: return
        val root = rootView ?: return

        textColor = try {
            Color.parseColor(style.color)
        } catch (t: Throwable) {
            Color.WHITE
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize)
        tv.setTextColor(textColor)
        tv.setLineSpacing(0f, style.lineSpacing)

        val alpha = (style.bgOpacity.coerceIn(0f, 1f) * 255).toInt()
        root.background = GradientDrawable().apply {
            setColor(Color.argb(alpha, 0, 0, 0))
            cornerRadius = 14f * density
        }
        speedLabel?.text = style.speed.toInt().toString()

        var realTextChanged = textChanged
        if (style.text != fullText) {
            fullText = style.text
            realTextChanged = true
        }
        if (realTextChanged) {
            matcher = null
            tv.text = SpannableString(fullText)
            if (resetScroll) scrollView?.scrollTo(0, 0)
        }

        if (style.mode == "timed") {
            matcher = null
            stopVoiceEngine()
            if (playing) {
                val sv = scrollView
                sv?.post { if (isRunning && playing && style.mode == "timed") startTimedScroll() }
            }
        } else {
            stopTimedScroll()
            if (matcher == null) matcher = ScrollMatcher(fullText)
            if (playing) startVoiceEngine()
        }
    }

    // ============================================================
    // timed 模式滚动
    // ============================================================

    private fun maxScroll(): Int {
        val tv = textView ?: return 0
        val sv = scrollView ?: return 0
        return (tv.height - sv.height).coerceAtLeast(0)
    }

    private fun startTimedScroll() {
        stopTimedScroll()
        val sv = scrollView ?: return
        val max = maxScroll()
        val from = sv.scrollY
        if (max <= from) return
        val pxPerSec = (style.speed * density).coerceAtLeast(1f)
        val duration = ((max - from) / pxPerSec * 1000f).toLong().coerceAtLeast(100L)
        timedAnimator = ValueAnimator.ofInt(from, max).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                lastProgrammaticScrollAt = System.currentTimeMillis()
                sv.scrollTo(0, a.animatedValue as Int)
            }
            start()
        }
    }

    private fun stopTimedScroll() {
        timedAnimator?.cancel()
        timedAnimator = null
    }

    // ============================================================
    // voice 模式：识别引擎 + ScrollMatcher
    // ============================================================

    private fun startVoiceEngine() {
        stopVoiceEngine()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 无录音权限：悬浮窗照常显示，仅不做语音跟读
            return
        }
        voiceEngine = SpeechEngine(
            applicationContext,
            SpeechEngine.Options(style.speechSource, style.serverUrl, style.lang)
        ) { text, _ ->
            onVoiceText(text)
        }
        voiceEngine?.start()
    }

    private fun stopVoiceEngine() {
        voiceEngine?.destroy()
        voiceEngine = null
    }

    private fun onVoiceText(text: String) {
        val m = matcher ?: return
        val before = m.currentIndex
        m.update(text)
        if (m.currentIndex != before) {
            updateReadHighlight()
            scrollToMatcherPosition()
        }
    }

    private fun scrollToMatcherPosition() {
        val m = matcher ?: return
        val tv = textView ?: return
        val sv = scrollView ?: return
        val layout = tv.layout ?: return
        if (fullText.isEmpty()) return
        val pos = m.originalPosition().coerceIn(0, fullText.length)
        val line = layout.getLineForOffset(pos)
        // 高亮线居中：目标字符行停在可视高度约 40% 处
        val target = (layout.getLineTop(line) - sv.height * 0.4f).toInt()
            .coerceIn(0, maxScroll())
        lastProgrammaticScrollAt = System.currentTimeMillis()
        sv.smoothScrollTo(0, target)
    }

    private fun updateReadHighlight() {
        val m = matcher ?: return
        val tv = textView ?: return
        val end = m.originalPosition().coerceIn(0, fullText.length)
        val spannable = SpannableString(fullText)
        if (end > 0) {
            val dim = Color.argb(
                (255 * 0.35f).toInt(),
                Color.red(textColor), Color.green(textColor), Color.blue(textColor)
            )
            spannable.setSpan(ForegroundColorSpan(dim), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = spannable
    }

    /** 用户手动滚动（区分程序滚动），voice 模式下反查字符位置并发出 seek 事件 */
    private fun onContentScrolled() {
        if (style.mode != "voice" || matcher == null) return
        val now = System.currentTimeMillis()
        if (now - lastProgrammaticScrollAt < 800L) {
            // 程序驱动滚动的延续（smoothScrollTo 动画期间）
            lastProgrammaticScrollAt = now
            mainHandler.removeCallbacks(seekRunnable)
        } else {
            mainHandler.removeCallbacks(seekRunnable)
            mainHandler.postDelayed(seekRunnable, 400L)
        }
    }

    private fun handleUserSeek() {
        val m = matcher ?: return
        val tv = textView ?: return
        val sv = scrollView ?: return
        val layout = tv.layout ?: return
        val line = layout.getLineForVertical(sv.scrollY)
        val orig = layout.getLineStart(line)
        m.seekToOriginal(orig)
        updateReadHighlight()
        emitEvent("seek", orig)
    }

    // ============================================================
    // 控制条交互
    // ============================================================

    private fun togglePlayPause() {
        if (playing) {
            playing = false
            if (style.mode == "timed") {
                timedAnimator?.pause()
            } else {
                stopVoiceEngine()
            }
            emitEvent("pause", currentPosition())
        } else {
            playing = true
            if (style.mode == "timed") {
                val a = timedAnimator
                if (a != null && a.isPaused) {
                    a.resume()
                } else {
                    scrollView?.post { if (isRunning && playing) startTimedScroll() }
                }
            } else {
                startVoiceEngine()
            }
            emitEvent("play", currentPosition())
        }
        updatePlayPauseLabel()
    }

    private fun updatePlayPauseLabel() {
        playPauseBtn?.text = if (playing) "暂停" else "播放"
    }

    private fun changeSpeed(delta: Float) {
        style.speed = (style.speed + delta).coerceIn(10f, 200f)
        speedLabel?.text = style.speed.toInt().toString()
        if (style.mode == "timed" && playing) startTimedScroll()
    }

    fun currentPosition(): Int {
        val m = matcher
        if (m != null) return m.originalPosition()
        val tv = textView ?: return 0
        val sv = scrollView ?: return 0
        val layout = tv.layout ?: return 0
        val line = layout.getLineForVertical(sv.scrollY)
        return layout.getLineStart(line)
    }

    private fun emitEvent(action: String, position: Int?) {
        val listener = eventListener ?: return
        try {
            listener(action, position)
        } catch (t: Throwable) {
            // JS 侧异常不影响悬浮窗
        }
    }

    // ============================================================
    // 拖动 / 缩放
    // ============================================================

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startWX = 0
        private var startWY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            val windowManager = wm ?: return false
            val root = rootView ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startWX = p.x
                    startWY = p.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startWX + (event.rawX - startX).toInt()
                    p.y = startWY + (event.rawY - startY).toInt()
                    try {
                        windowManager.updateViewLayout(root, p)
                    } catch (t: Throwable) {
                        // view 已被移除等情况
                    }
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startW = 0
        private var startH = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            val windowManager = wm ?: return false
            val root = rootView ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startW = p.width
                    startH = p.height
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minW = (200 * density).toInt()
                    val minH = (120 * density).toInt()
                    p.width = (startW + (event.rawX - startX)).toInt().coerceAtLeast(minW)
                    p.height = (startH + (event.rawY - startY)).toInt().coerceAtLeast(minH)
                    try {
                        windowManager.updateViewLayout(root, p)
                    } catch (t: Throwable) {
                        // ignore
                    }
                    return true
                }
            }
            return false
        }
    }
}

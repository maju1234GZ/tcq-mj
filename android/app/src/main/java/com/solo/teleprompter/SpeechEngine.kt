package com.solo.teleprompter

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * 语音识别引擎，双通道统一输出 "累计全文" 语义：
 *   onText(cumulativeText, isFinal)
 *
 * - system：Android SpeechRecognizer（SpeechRecognizer 必须在主线程创建/销毁）。
 * - cloud ：OkHttp WebSocket + AudioRecord 16kHz 单声道 PCM16（契约第四节协议）。
 */
class SpeechEngine(
    private val context: Context,
    private val options: Options,
    private val callback: (text: String, isFinal: Boolean) -> Unit
) {

    data class Options(
        val source: String = "system",
        val serverUrl: String? = null,
        val lang: String = "zh-CN"
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false

    // ---------- system 通道状态 ----------
    private var recognizer: SpeechRecognizer? = null
    private val committed = StringBuilder()
    private var retryCount = 0

    // ---------- cloud 通道状态 ----------
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var capturing = false

    fun start() {
        destroyed = false
        if (options.source == "cloud") startCloud() else startSystem()
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        destroySystem()
        destroyCloud()
    }

    /** 统一出口：回调始终切到主线程 */
    private fun emit(text: String, isFinal: Boolean) {
        if (destroyed) return
        mainHandler.post {
            if (!destroyed) callback(text, isFinal)
        }
    }

    // ============================================================
    // system 通道
    // ============================================================

    private fun startSystem() {
        mainHandler.post {
            if (destroyed) return@post
            try {
                // isRecognitionAvailable 是 API 31+ 的方法，低版本直接尝试创建
                if (Build.VERSION.SDK_INT >= 31 && !SpeechRecognizer.isRecognitionAvailable(context)) {
                    // 设备无识别服务，无法重试，直接放弃
                    return@post
                }
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(systemListener)
                }
                startListeningInternal()
            } catch (t: Throwable) {
                scheduleRestart()
            }
        }
    }

    private fun startListeningInternal() {
        if (destroyed) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, options.lang)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, options.lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            scheduleRestart()
        }
    }

    private val restartRunnable = Runnable {
        if (destroyed) return@Runnable
        try {
            recognizer?.cancel()
        } catch (t: Throwable) {
            // ignore
        }
        startListeningInternal()
    }

    /** baseDelayMs >= 0 时按固定延迟重启；否则按 500ms * 2^n 指数退避（封顶 5s） */
    private fun scheduleRestart(baseDelayMs: Long = -1L) {
        if (destroyed) return
        val delay: Long
        if (baseDelayMs >= 0) {
            delay = baseDelayMs
        } else {
            delay = (500L * (1L shl retryCount.coerceAtMost(4))).coerceAtMost(5000L)
            retryCount++
        }
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delay)
    }

    private fun appendCommitted(text: String) {
        if (committed.isNotEmpty() && separator().isNotEmpty()) committed.append(separator())
        committed.append(text)
    }

    private fun separator(): String = if (options.lang.startsWith("zh")) "" else " "

    private val systemListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            retryCount = 0
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (destroyed) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // 权限问题重试无意义
                }
                else -> scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            if (destroyed) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                appendCommitted(text)
                emit(committed.toString(), true)
            }
            // 持续识别：一句结束后立即重启
            scheduleRestart(150L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (destroyed) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrEmpty()) {
                val full = committed.toString() +
                    (if (committed.isNotEmpty()) separator() else "") +
                    partial
                emit(full, false)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun destroySystem() {
        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (t: Throwable) {
                // ignore
            }
            try {
                recognizer?.destroy()
            } catch (t: Throwable) {
                // ignore
            }
            recognizer = null
        }
    }

    // ============================================================
    // cloud 通道（契约第四节 WebSocket 协议）
    // ============================================================

    private fun startCloud() {
        val url = options.serverUrl ?: return
        val client = OkHttpClient()
        okHttpClient = client
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, cloudListener)
    }

    private val cloudListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (destroyed) return
            try {
                val config = JSONObject()
                config.put("type", "config")
                config.put("lang", options.lang)
                config.put("sampleRate", 16000)
                webSocket.send(config.toString())
            } catch (t: Throwable) {
                // ignore
            }
            startCapture()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (destroyed) return
            try {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "partial" -> emit(obj.optString("text"), false)
                    "final" -> emit(obj.optString("text"), true)
                    "error" -> {
                        // 服务端错误仅忽略，等待后续帧或上层停止
                    }
                }
            } catch (t: Throwable) {
                // 非 JSON 帧忽略
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            stopCapture()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            stopCapture()
        }
    }

    private fun startCapture() {
        if (capturing || destroyed) return
        capturing = true
        val thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 6400)
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                audioRecord = record
                record.startRecording()
                val buffer = ByteArray(3200) // 100ms @16kHz/16bit/mono
                while (capturing && !destroyed) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        val ws = webSocket
                        if (ws != null && capturing && !destroyed) {
                            ws.send(buffer.toByteString(0, n))
                        }
                    } else if (n < 0) {
                        // 读取出错，退出采集
                        break
                    }
                }
            } catch (t: Throwable) {
                // SecurityException（无录音权限）等：采集线程安静退出
            } finally {
                capturing = false
                try {
                    record?.stop()
                } catch (t: Throwable) {
                    // ignore
                }
                try {
                    record?.release()
                } catch (t: Throwable) {
                    // ignore
                }
                if (audioRecord === record) audioRecord = null
            }
        }
        thread.name = "teleprompter-pcm"
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    private fun stopCapture() {
        capturing = false
        val record = audioRecord
        if (record != null) {
            try {
                record.stop()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun destroyCloud() {
        capturing = false
        val ws = webSocket
        webSocket = null
        if (ws != null) {
            try {
                val end = JSONObject()
                end.put("type", "end")
                ws.send(end.toString())
            } catch (t: Throwable) {
                // ignore
            }
            try {
                ws.close(1000, null)
            } catch (t: Throwable) {
                // ignore
            }
        }
        stopCapture()
        captureThread = null
        okHttpClient = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
    }
}

package com.solo.teleprompter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Teleprompter Capacitor 插件（Android 端）。
 * 方法签名与事件名严格按《插件接口契约》实现。
 */
@CapacitorPlugin(
    name = "Teleprompter",
    permissions = [
        Permission(alias = "mic", strings = [Manifest.permission.RECORD_AUDIO])
    ]
)
class TeleprompterPlugin : Plugin() {

    /** JS 独立调用的识别通道（悬浮窗内部的识别通道在 OverlayService 里） */
    private var jsSpeech: SpeechEngine? = null

    override fun load() {
        // Service -> JS 事件桥（同进程静态回调）
        OverlayService.eventListener = { action, position ->
            val data = JSObject()
            data.put("action", action)
            if (position != null) data.put("position", position)
            notifyListeners("overlayEvent", data)
        }
    }

    override fun handleOnDestroy() {
        jsSpeech?.destroy()
        jsSpeech = null
        if (OverlayService.eventListener != null) OverlayService.eventListener = null
    }

    // ============================================================
    // 权限
    // ============================================================

    @PluginMethod
    fun hasPermissions(call: PluginCall) {
        call.resolve(currentPermissions())
    }

    @PluginMethod
    fun requestPermissions(call: PluginCall) {
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
            try {
                startActivityForResult(call, intent, "onOverlayPermissionResult")
            } catch (t: Throwable) {
                call.reject("无法打开悬浮窗授权页: " + t.message)
            }
        } else {
            requestMicPermission(call)
        }
    }

    @ActivityCallback
    private fun onOverlayPermissionResult(call: PluginCall, result: ActivityResult) {
        requestMicPermission(call)
    }

    private fun requestMicPermission(call: PluginCall) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            call.resolve(currentPermissions())
        } else {
            requestPermissionForAlias("mic", call, "onMicPermissionResult")
        }
    }

    @PermissionCallback
    private fun onMicPermissionResult(call: PluginCall) {
        call.resolve(currentPermissions())
    }

    private fun currentPermissions(): JSObject {
        val ret = JSObject()
        ret.put("overlay", Settings.canDrawOverlays(context))
        ret.put(
            "mic",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
        ret.put(
            "camera",
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
        return ret
    }

    // ============================================================
    // 悬浮窗
    // ============================================================

    @PluginMethod
    fun startOverlay(call: PluginCall) {
        if (!Settings.canDrawOverlays(context)) {
            call.reject("未授予悬浮窗权限")
            return
        }
        val text = call.getString("text")
        if (text == null) {
            call.reject("text 不能为空")
            return
        }
        val mode = call.getString("mode", "voice") ?: "voice"
        if (mode == "voice" && !micGranted()) {
            call.reject("未授予录音权限")
            return
        }
        val intent = Intent(context, OverlayService::class.java)
        intent.action = OverlayService.ACTION_START
        intent.putExtra("text", text)
        putStyleExtras(call, intent)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            call.reject("启动悬浮窗失败: " + t.message)
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun updateOverlay(call: PluginCall) {
        if (OverlayService.instance == null) {
            // 悬浮窗未运行：按约定视为无操作
            call.resolve()
            return
        }
        val intent = Intent(context, OverlayService::class.java)
        intent.action = OverlayService.ACTION_UPDATE
        putStyleExtras(call, intent)
        try {
            context.startService(intent)
        } catch (t: Throwable) {
            call.reject("更新悬浮窗失败: " + t.message)
            return
        }
        call.resolve()
    }

    @PluginMethod
    fun stopOverlay(call: PluginCall) {
        try {
            context.stopService(Intent(context, OverlayService::class.java))
        } catch (t: Throwable) {
            // 服务可能本就不在运行
        }
        call.resolve()
    }

    @PluginMethod
    fun getOverlayState(call: PluginCall) {
        val svc = OverlayService.instance
        val ret = JSObject()
        ret.put("running", svc != null && svc.isRunning)
        ret.put("position", svc?.currentPosition() ?: 0)
        call.resolve(ret)
    }

    private fun putStyleExtras(call: PluginCall, intent: Intent) {
        putFloatIfPresent(call, intent, "fontSize")
        putStringIfPresent(call, intent, "color")
        putFloatIfPresent(call, intent, "bgOpacity")
        putFloatIfPresent(call, intent, "lineSpacing")
        putFloatIfPresent(call, intent, "speed")
        putStringIfPresent(call, intent, "mode")
        // 以下为契约之外的可选扩展字段：指定悬浮窗 voice 模式的识别通道
        putStringIfPresent(call, intent, "speechSource")
        putStringIfPresent(call, intent, "serverUrl")
        putStringIfPresent(call, intent, "lang")
    }

    private fun putFloatIfPresent(call: PluginCall, intent: Intent, key: String) {
        val v = call.getDouble(key)
        if (v != null) intent.putExtra(key, v.toFloat())
    }

    private fun putStringIfPresent(call: PluginCall, intent: Intent, key: String) {
        val v = call.getString(key)
        if (v != null) intent.putExtra(key, v)
    }

    private fun micGranted(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ============================================================
    // 语音识别（JS 独立通道）
    // ============================================================

    @PluginMethod
    fun startSpeech(call: PluginCall) {
        val source = call.getString("source", "system") ?: "system"
        val serverUrl = call.getString("serverUrl")
        val lang = call.getString("lang", "zh-CN") ?: "zh-CN"
        if (source == "cloud" && serverUrl.isNullOrEmpty()) {
            call.reject("source=cloud 时 serverUrl 必填")
            return
        }
        if (!micGranted()) {
            call.reject("未授予录音权限")
            return
        }
        jsSpeech?.destroy()
        jsSpeech = SpeechEngine(
            context.applicationContext,
            SpeechEngine.Options(source, serverUrl, lang)
        ) { text, isFinal ->
            val ret = JSObject()
            ret.put("text", text)
            ret.put("isFinal", isFinal)
            notifyListeners("speech", ret)
        }
        jsSpeech?.start()
        call.resolve()
    }

    @PluginMethod
    fun stopSpeech(call: PluginCall) {
        jsSpeech?.destroy()
        jsSpeech = null
        call.resolve()
    }

    // ============================================================
    // iOS 专用方法：Android 空实现直接 resolve
    // ============================================================

    @PluginMethod
    fun startCamera(call: PluginCall) {
        call.resolve()
    }

    @PluginMethod
    fun stopCamera(call: PluginCall) {
        call.resolve()
    }

    @PluginMethod
    fun startRecording(call: PluginCall) {
        call.resolve()
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        val ret = JSObject()
        ret.put("saved", false)
        call.resolve(ret)
    }
}

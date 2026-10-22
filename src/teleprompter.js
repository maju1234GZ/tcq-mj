import { registerPlugin } from '@capacitor/core'

/**
 * Teleprompter 原生插件
 * Android: 真悬浮窗(WindowManager) + 系统SpeechRecognizer / 云端ASR(WebSocket)
 * iOS:     App内相机录制 + SFSpeechRecognizer / 云端ASR(WebSocket)
 * Web:     降级为浏览器 webkitSpeechRecognition（仅调试用，可无）
 */
const TeleprompterNative = registerPlugin('Teleprompter')

export default TeleprompterNative

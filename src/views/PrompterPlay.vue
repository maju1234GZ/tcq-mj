<template>
  <div class="play-page" :class="{ 'see-through': isIos }">
    <!-- 顶部控制条 -->
    <div class="control-bar">
      <div class="row">
        <button class="cbtn close-btn" @click="close" title="关闭">×</button>
        <button class="cbtn play-btn" @click="togglePlay">{{ playing ? '❚❚' : '▶' }}</button>
        <button class="cbtn text-btn" @click="toggleMode">{{ mode === 'voice' ? '跟读' : '定速' }}</button>
        <div class="stepper">
          <button class="cbtn" @click="speed = clamp(speed - 5, 5, 200)">－</button>
          <span class="stepper-val">{{ speed }}</span>
          <button class="cbtn" @click="speed = clamp(speed + 5, 5, 200)">＋</button>
        </div>
        <button class="cbtn text-btn" :class="{ on: mirror }" @click="mirror = !mirror">镜像</button>
      </div>
      <div class="row">
        <div class="stepper">
          <button class="cbtn" @click="fontSize = clamp(fontSize - 2, 16, 96)">A-</button>
          <span class="stepper-val">{{ fontSize }}</span>
          <button class="cbtn" @click="fontSize = clamp(fontSize + 2, 16, 96)">A+</button>
        </div>
        <div class="colors">
          <button
            v-for="c in presetColors"
            :key="c"
            class="dot"
            :class="{ active: color.toUpperCase() === c.toUpperCase() }"
            :style="{ background: c }"
            @click="color = c"
          ></button>
          <input v-model="color" type="color" class="color-input" title="自定义颜色" />
        </div>
      </div>
      <div class="row">
        <span class="row-label">背景</span>
        <input v-model.number="bgOpacity" type="range" min="0" max="1" step="0.05" class="slider" />
        <span class="row-val">{{ Math.round(bgOpacity * 100) }}%</span>
        <button
          v-if="isAndroid"
          class="cbtn wide-btn"
          :class="{ danger: overlayRunning }"
          @click="toggleOverlay"
        >{{ overlayRunning ? '停止悬浮窗' : '启动悬浮窗' }}</button>
        <button
          v-else
          class="cbtn wide-btn"
          :class="{ danger: recording }"
          @click="toggleRecord"
        >{{ recording ? '停止录制' : '开始录制' }}</button>
      </div>
      <div v-if="isAndroid && permTip" class="row perm-row">
        <span class="perm-text">{{ permTip }}</span>
        <button class="cbtn accent-btn" @click="grant">去授权</button>
      </div>
    </div>

    <!-- 中部大字提词区 -->
    <div class="stage">
      <div ref="areaRef" class="scroll-area">
        <div class="text-layer" :style="textStyle">
          <div class="pad"></div>
          <div
            v-for="(line, i) in lines"
            :key="i"
            class="line"
            :class="lineClass(i)"
          >{{ line || ' ' }}</div>
          <div class="pad"></div>
        </div>
      </div>
      <div class="highlight-line"></div>
      <div v-if="!hasContent" class="empty-tip">台本内容为空</div>
    </div>

    <!-- 底部语音源切换 -->
    <div class="bottom-bar">
      <div class="src-switch">
        <button :class="{ active: source === 'system' }" @click="setSource('system')">系统识别</button>
        <button :class="{ active: source === 'cloud' }" @click="setSource('cloud')">云端ASR</button>
      </div>
      <div v-if="source === 'cloud'" class="server-line" @click="goSettings">
        {{ serverUrl || '未配置服务器地址，点击前往设置' }}
      </div>
    </div>

    <div v-if="toastText" class="toast">{{ toastText }}</div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Capacitor } from '@capacitor/core'
import Teleprompter from '../teleprompter'
import { getScripts, getSettings, saveSettings } from '../utils/store'
import { ScrollMatcher } from '../utils/matcher'

const route = useRoute()
const router = useRouter()

const platform = Capacitor.getPlatform() // 'android' | 'ios' | 'web'
const isAndroid = platform === 'android'
const isIos = platform === 'ios'

const presetColors = ['#FFFFFF', '#FFE14D', '#3DFF88', '#FF5A5A', '#5AA9FF']

// ---------- 台本与设置 ----------
const script = ref(null)
const lines = computed(() => (script.value ? (script.value.content || '').split('\n') : []))
const hasContent = computed(() => !!(script.value && (script.value.content || '').trim()))

const lineRanges = computed(() => {
  const arr = []
  let pos = 0
  for (const l of lines.value) {
    arr.push([pos, pos + l.length])
    pos += l.length + 1 // 含换行符
  }
  return arr
})

let matcher = null

// 样式（默认值来自设置页）
const fontSize = ref(34)
const color = ref('#FFFFFF')
const bgOpacity = ref(0.35)
const speed = ref(40)
const lineSpacing = ref(1.4)
const mirror = ref(false)
const source = ref('system')
const serverUrl = ref('')

// 播放状态
const mode = ref('voice') // 'voice' 语音跟读 / 'timed' 定速滚动
const playing = ref(false)
const recording = ref(false)
const overlayRunning = ref(false)
const permTip = ref('')
const cur = ref(0) // 已滚到的原文字符索引
const toastText = ref('')
const areaRef = ref(null)

let toastTimer = null
let raf = 0
let cameraStarted = false
let appRootEl = null
let lastLine = -1
let speechHandle = null
let recordHandle = null
let overlayHandle = null
let overlayTimer = null

const textStyle = computed(() => ({
  fontSize: fontSize.value + 'px',
  color: color.value,
  lineHeight: lineSpacing.value,
  background: `rgba(0, 0, 0, ${bgOpacity.value})`,
  transform: mirror.value ? 'scaleX(-1)' : 'none'
}))

function clamp(v, min, max) {
  return Math.min(max, Math.max(min, v))
}

function showToast(text) {
  toastText.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastText.value = '' }, 2200)
}

function fullStyle() {
  return {
    text: script.value ? (script.value.content || '') : '',
    fontSize: fontSize.value,
    color: color.value,
    bgOpacity: bgOpacity.value,
    lineSpacing: lineSpacing.value,
    speed: speed.value,
    mode: mode.value,
    mirror: mirror.value,
    // 悬浮窗 voice 模式的识别通道（Android 原生读取 speechSource/serverUrl/lang）
    speechSource: source.value,
    serverUrl: serverUrl.value,
    lang: 'zh-CN'
  }
}

// ---------- 行高亮与滚动 ----------
function lineClass(i) {
  const range = lineRanges.value[i]
  if (!range) return ''
  const [start, end] = range
  if (cur.value > end) return 'read'
  if (cur.value >= start) return 'current'
  return ''
}

function lineIndexOf(rawIdx) {
  const ranges = lineRanges.value
  for (let i = 0; i < ranges.length; i++) {
    if (rawIdx <= ranges[i][1]) return i
  }
  return Math.max(0, ranges.length - 1)
}

function scrollToLine(i, smooth = true) {
  const area = areaRef.value
  if (!area) return
  const el = area.querySelectorAll('.line')[i]
  if (!el) return
  const top = el.offsetTop + el.offsetHeight / 2 - area.clientHeight / 2
  area.scrollTo({ top, behavior: smooth ? 'smooth' : 'auto' })
}

// ---------- 语音跟读 ----------
function onSpeech(e) {
  if (!playing.value || mode.value !== 'voice' || !matcher) return
  if (!e || typeof e.text !== 'string') return
  const idx = matcher.push(e.text)
  cur.value = idx
  const li = lineIndexOf(idx)
  if (li !== lastLine) {
    lastLine = li
    scrollToLine(li)
  }
}

async function startVoice() {
  try {
    await Teleprompter.startSpeech({
      source: source.value,
      serverUrl: serverUrl.value,
      lang: 'zh-CN'
    })
  } catch (err) {
    playing.value = false
    showToast('语音识别不可用：' + ((err && err.message) || '当前环境不支持'))
  }
}

async function stopVoice() {
  try {
    await Teleprompter.stopSpeech()
  } catch {
    // 静默
  }
}

// ---------- 定速滚动 ----------
function startTimed() {
  cancelAnimationFrame(raf)
  let lastTs = performance.now()
  const loop = (ts) => {
    if (!playing.value || mode.value !== 'timed') return
    const dt = Math.min(100, ts - lastTs)
    lastTs = ts
    const area = areaRef.value
    if (area) {
      area.scrollTop += (speed.value * dt) / 1000
      syncCurFromScroll()
    }
    raf = requestAnimationFrame(loop)
  }
  raf = requestAnimationFrame(loop)
}

function syncCurFromScroll() {
  const area = areaRef.value
  if (!area) return
  const y = area.scrollTop + area.clientHeight / 2
  const els = area.querySelectorAll('.line')
  let idx = 0
  for (let i = 0; i < els.length; i++) {
    if (els[i].offsetTop + els[i].offsetHeight / 2 <= y) idx = i
    else break
  }
  const range = lineRanges.value[idx]
  if (range) cur.value = range[0]
}

// ---------- 播放控制 ----------
async function togglePlay() {
  if (playing.value) {
    await pause()
  } else {
    await play()
  }
}

async function play() {
  playing.value = true
  if (mode.value === 'voice') {
    await startVoice()
  } else {
    startTimed()
  }
}

async function pause() {
  playing.value = false
  cancelAnimationFrame(raf)
  if (mode.value === 'voice') await stopVoice()
}

async function toggleMode() {
  const wasPlaying = playing.value
  if (wasPlaying) await pause()
  mode.value = mode.value === 'voice' ? 'timed' : 'voice'
  if (wasPlaying) await play()
}

function setSource(src) {
  source.value = src
  try {
    saveSettings({ ...getSettings(), source: src })
  } catch {
    // 静默
  }
  // 语音跟读进行中则切换识别通道
  if (playing.value && mode.value === 'voice') {
    stopVoice().then(() => startVoice())
  }
}

function goSettings() {
  router.push('/settings')
}

// ---------- Android 悬浮窗 ----------
async function startOverlay() {
  try {
    await Teleprompter.startOverlay(fullStyle())
    overlayRunning.value = true
    showToast('悬浮窗已启动，可切换到录制 App')
  } catch (err) {
    showToast('悬浮窗启动失败：' + ((err && err.message) || '未知错误'))
  }
}

async function stopOverlay() {
  try {
    await Teleprompter.stopOverlay()
  } catch {
    // 静默
  }
  overlayRunning.value = false
}

async function toggleOverlay() {
  if (overlayRunning.value) {
    await stopOverlay()
  } else {
    try {
      const p = await Teleprompter.hasPermissions()
      if (p && !p.overlay) {
        permTip.value = '缺少悬浮窗权限，请先授权'
        return
      }
    } catch {
      // 查询失败时直接尝试启动
    }
    await startOverlay()
  }
}

async function grant() {
  try {
    const p = await Teleprompter.requestPermissions()
    if (p && p.overlay && p.mic) {
      permTip.value = ''
      await startOverlay()
    } else {
      permTip.value = '仍未获得全部权限，请检查系统设置'
    }
  } catch {
    showToast('当前环境不支持授权操作')
  }
}

// 样式变化实时同步到悬浮窗（节流）
watch([fontSize, color, bgOpacity, lineSpacing, speed, mirror, mode, source, serverUrl], () => {
  if (!isAndroid || !overlayRunning.value) return
  clearTimeout(overlayTimer)
  overlayTimer = setTimeout(async () => {
    try {
      await Teleprompter.updateOverlay(fullStyle())
    } catch {
      // 悬浮窗不可用时静默
    }
  }, 120)
})

// ---------- iOS 相机录制 ----------
async function toggleRecord() {
  if (!recording.value) {
    try {
      await Teleprompter.startRecording()
      recording.value = true
    } catch (err) {
      showToast('无法开始录制：' + ((err && err.message) || '当前环境不支持'))
    }
  } else {
    try {
      await Teleprompter.stopRecording()
    } catch {
      // 结果通过 recordEvent 事件反馈
    }
    recording.value = false
  }
}

function onRecordEvent(e) {
  if (!e) return
  if (e.action === 'saved') {
    showToast('视频已保存到相册')
  } else if (e.action === 'failed') {
    showToast('录制失败：' + (e.message || '未知错误'))
  }
}

// ---------- 悬浮窗事件（原生按钮回传） ----------
function onOverlayEvent(e) {
  if (!e) return
  if (e.action === 'close') {
    overlayRunning.value = false
    showToast('悬浮窗已关闭')
  } else if (e.action === 'play') {
    playing.value = true
  } else if (e.action === 'pause') {
    playing.value = false
  } else if (e.action === 'seek' && typeof e.position === 'number') {
    cur.value = e.position
    scrollToLine(lineIndexOf(e.position))
  }
}

// ---------- 生命周期 ----------
async function cleanup() {
  playing.value = false
  cancelAnimationFrame(raf)
  clearTimeout(overlayTimer)
  try { await stopVoice() } catch { /* 静默 */ }
  if (overlayRunning.value) {
    try { await Teleprompter.stopOverlay() } catch { /* 静默 */ }
    overlayRunning.value = false
  }
  if (recording.value) {
    try { await Teleprompter.stopRecording() } catch { /* 静默 */ }
    recording.value = false
  }
  if (cameraStarted) {
    try { await Teleprompter.stopCamera() } catch { /* 静默 */ }
    cameraStarted = false
  }
}

async function close() {
  await cleanup()
  router.replace('/scripts')
}

onMounted(async () => {
  // 加载台本
  const found = getScripts().find(s => String(s.id) === String(route.params.id))
  if (!found) {
    showToast('台本不存在')
    router.replace('/scripts')
    return
  }
  script.value = found
  matcher = new ScrollMatcher(found.content || '')

  // 读取设置默认值
  const st = getSettings()
  fontSize.value = st.fontSize
  color.value = st.color
  bgOpacity.value = st.bgOpacity
  speed.value = st.speed
  lineSpacing.value = st.lineSpacing
  mirror.value = st.mirror
  source.value = st.source
  serverUrl.value = st.serverUrl

  // 事件监听（Web 环境下 registerPlugin 代理可能抛错，全部兜底）
  try { speechHandle = await Teleprompter.addListener('speech', onSpeech) } catch { /* 静默 */ }
  try { recordHandle = await Teleprompter.addListener('recordEvent', onRecordEvent) } catch { /* 静默 */ }
  try { overlayHandle = await Teleprompter.addListener('overlayEvent', onOverlayEvent) } catch { /* 静默 */ }

  if (isAndroid) {
    // 检查权限后启动悬浮窗
    try {
      const p = await Teleprompter.hasPermissions()
      if (p && p.overlay && p.mic) {
        await startOverlay()
      } else {
        permTip.value = '需要悬浮窗与麦克风权限才能在外部 App 上提词'
      }
    } catch {
      permTip.value = ''
    }
  } else if (isIos) {
    // App 内模式：透明背景透出相机预览
    document.documentElement.style.background = 'transparent'
    document.body.style.background = 'transparent'
    // #app 在 style.css 中默认是黑底，必须一并置透明，否则会挡住相机预览层
    appRootEl = document.getElementById('app')
    if (appRootEl) appRootEl.style.background = 'transparent'
    try { await Teleprompter.requestPermissions() } catch { /* 静默 */ }
    try {
      await Teleprompter.startCamera({ position: 'front' })
      cameraStarted = true
    } catch { /* 静默 */ }
  }

  await nextTick()
  scrollToLine(0, false)
})

onBeforeUnmount(() => {
  cleanup()
  for (const h of [speechHandle, recordHandle, overlayHandle]) {
    try { h && h.remove() } catch { /* 静默 */ }
  }
  if (isIos) {
    document.documentElement.style.background = ''
    document.body.style.background = ''
    if (appRootEl) {
      appRootEl.style.background = ''
      appRootEl = null
    }
  }
})
</script>

<style scoped>
.play-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0b0b0f;
}

.play-page.see-through {
  background: transparent;
}

/* ---------- 顶部控制条 ---------- */
.control-bar {
  padding: 10px 12px calc(10px + env(safe-area-inset-top));
  background: rgba(23, 23, 31, 0.96);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.see-through .control-bar {
  background: rgba(11, 11, 15, 0.55);
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cbtn {
  min-width: 44px;
  height: 44px;
  padding: 0 12px;
  border-radius: 12px;
  background: #262633;
  color: #f5f5f7;
  font-size: 16px;
}

.close-btn {
  font-size: 22px;
  line-height: 1;
}

.play-btn {
  min-width: 56px;
  background: #4f8cff;
  font-size: 18px;
  font-weight: 700;
}

.text-btn {
  font-size: 14px;
}

.text-btn.on {
  background: #4f8cff;
  color: #fff;
}

.stepper {
  display: flex;
  align-items: center;
  gap: 4px;
  background: #1c1c26;
  border-radius: 12px;
  padding: 2px;
}

.stepper .cbtn {
  min-width: 40px;
  height: 40px;
  font-size: 14px;
}

.stepper-val {
  min-width: 34px;
  text-align: center;
  font-size: 15px;
}

.colors {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  justify-content: flex-end;
}

.dot {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: 2px solid transparent;
  padding: 0;
}

.dot.active {
  border-color: #4f8cff;
  box-shadow: 0 0 0 2px #0b0b0f, 0 0 0 4px #4f8cff;
}

.color-input {
  width: 34px;
  height: 34px;
  border: none;
  background: none;
  padding: 0;
  border-radius: 8px;
}

.row-label {
  font-size: 14px;
  color: #c7c7cf;
  width: 34px;
  flex-shrink: 0;
}

.slider {
  flex: 1;
  accent-color: #4f8cff;
  height: 32px;
}

.row-val {
  width: 44px;
  text-align: right;
  font-size: 14px;
}

.wide-btn {
  font-size: 14px;
  font-weight: 600;
  background: #2b3a55;
  color: #9dc1ff;
}

.wide-btn.danger {
  background: #3a2126;
  color: #ff8a8a;
}

.perm-row {
  background: #2a2333;
  border-radius: 12px;
  padding: 10px 12px;
}

.perm-text {
  flex: 1;
  font-size: 13px;
  color: #ffcf8a;
}

.accent-btn {
  background: #4f8cff;
  font-size: 14px;
  font-weight: 600;
}

/* ---------- 提词区 ---------- */
.stage {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.scroll-area {
  position: relative;
  height: 100%;
  overflow-y: auto;
  scrollbar-width: none;
}

.scroll-area::-webkit-scrollbar {
  display: none;
}

.text-layer {
  transition: background 0.2s;
}

.pad {
  height: 42vh;
}

.line {
  padding: 4px 20px;
  opacity: 0.95;
  white-space: pre-wrap;
  word-break: break-all;
  transition: opacity 0.2s, background 0.2s;
}

.line.read {
  opacity: 0.3;
}

.line.current {
  opacity: 1;
  background: rgba(79, 140, 255, 0.14);
  box-shadow: inset 3px 0 0 #4f8cff;
}

.highlight-line {
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  height: 0;
  border-top: 2px solid rgba(79, 140, 255, 0.55);
  pointer-events: none;
}

.empty-tip {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8a8a93;
  font-size: 16px;
  pointer-events: none;
}

/* ---------- 底部语音源 ---------- */
.bottom-bar {
  padding: 10px 16px calc(12px + env(safe-area-inset-bottom));
  background: rgba(23, 23, 31, 0.96);
}

.see-through .bottom-bar {
  background: rgba(11, 11, 15, 0.55);
}

.src-switch {
  display: flex;
  background: #262633;
  border-radius: 12px;
  padding: 4px;
  gap: 4px;
}

.src-switch button {
  flex: 1;
  min-height: 42px;
  border-radius: 9px;
  font-size: 15px;
  background: transparent;
  color: #8a8a93;
}

.src-switch button.active {
  background: #4f8cff;
  color: #fff;
  font-weight: 600;
}

.server-line {
  margin-top: 8px;
  font-size: 12px;
  color: #8a8a93;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toast {
  position: fixed;
  left: 50%;
  bottom: 110px;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.85);
  color: #fff;
  font-size: 14px;
  padding: 10px 18px;
  border-radius: 20px;
  white-space: nowrap;
  z-index: 10;
}
</style>

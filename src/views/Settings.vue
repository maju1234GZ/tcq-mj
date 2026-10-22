<template>
  <div class="page">
    <header class="topbar">
      <button class="back-btn" @click="goBack">‹ 返回</button>
      <h1>设置</h1>
      <span class="spacer"></span>
    </header>

    <div class="body">
      <section class="sec">
        <h2>语音识别</h2>
        <div class="seg">
          <button :class="{ active: s.source === 'system' }" @click="s.source = 'system'">系统识别</button>
          <button :class="{ active: s.source === 'cloud' }" @click="s.source = 'cloud'">云端ASR</button>
        </div>
        <label class="field">
          <span class="field-label">云端服务器地址</span>
          <input
            v-model.trim="s.serverUrl"
            type="text"
            placeholder="ws://1.2.3.4:8765/ws"
            autocapitalize="off"
            autocorrect="off"
            spellcheck="false"
          />
        </label>
      </section>

      <section class="sec">
        <h2>默认提词样式</h2>

        <div class="field-row">
          <span class="field-label">字号</span>
          <input v-model.number="s.fontSize" type="range" min="16" max="96" step="1" class="slider" />
          <span class="field-val">{{ s.fontSize }}</span>
        </div>

        <div class="field-row">
          <span class="field-label">颜色</span>
          <div class="colors">
            <button
              v-for="c in presetColors"
              :key="c"
              class="dot"
              :class="{ active: s.color.toUpperCase() === c.toUpperCase() }"
              :style="{ background: c }"
              @click="s.color = c"
            ></button>
            <input v-model="s.color" type="color" class="color-input" title="自定义颜色" />
          </div>
        </div>

        <div class="field-row">
          <span class="field-label">背景</span>
          <input v-model.number="s.bgOpacity" type="range" min="0" max="1" step="0.05" class="slider" />
          <span class="field-val">{{ Math.round(s.bgOpacity * 100) }}%</span>
        </div>

        <div class="field-row">
          <span class="field-label">速度</span>
          <input v-model.number="s.speed" type="range" min="5" max="200" step="5" class="slider" />
          <span class="field-val">{{ s.speed }}</span>
        </div>

        <div class="field-row">
          <span class="field-label">行距</span>
          <input v-model.number="s.lineSpacing" type="range" min="1" max="2.5" step="0.05" class="slider" />
          <span class="field-val">{{ Number(s.lineSpacing).toFixed(2) }}</span>
        </div>

        <div class="field-row">
          <span class="field-label">镜像显示（预留）</span>
          <button class="switch" :class="{ on: s.mirror }" @click="s.mirror = !s.mirror">
            <span class="knob"></span>
          </button>
        </div>
      </section>

      <section class="sec">
        <h2>权限状态</h2>
        <div v-if="permUnsupported" class="perm-tip">当前环境不支持权限查询（仅安卓 / iOS）</div>
        <template v-else>
          <div class="perm-line" v-for="item in permItems" :key="item.key">
            <span>{{ item.label }}</span>
            <span class="perm-state" :class="{ ok: item.granted }">
              {{ perms ? (item.granted ? '已授权' : '未授权') : '查询中…' }}
            </span>
          </div>
          <button class="grant-btn" @click="grant">去授权</button>
        </template>
      </section>
    </div>

    <div v-if="toastText" class="toast">{{ toastText }}</div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Teleprompter from '../teleprompter'
import { getSettings, saveSettings } from '../utils/store'

const router = useRouter()

const presetColors = ['#FFFFFF', '#FFE14D', '#3DFF88', '#FF5A5A', '#5AA9FF']

const s = reactive(getSettings())

const perms = ref(null)
const permUnsupported = ref(false)
const toastText = ref('')
let toastTimer = null

const permItems = computed(() => [
  { key: 'overlay', label: '悬浮窗（安卓）', granted: !!(perms.value && perms.value.overlay) },
  { key: 'mic', label: '麦克风', granted: !!(perms.value && perms.value.mic) },
  { key: 'camera', label: '相机', granted: !!(perms.value && perms.value.camera) }
])

// 修改即自动保存（含 tp_server_url 独立 key 同步）
watch(s, () => {
  saveSettings({ ...s })
  flashToast('已保存')
}, { deep: true })

onMounted(async () => {
  try {
    perms.value = await Teleprompter.hasPermissions()
  } catch {
    permUnsupported.value = true
  }
})

async function grant() {
  try {
    perms.value = await Teleprompter.requestPermissions()
    flashToast('授权流程已完成')
  } catch {
    flashToast('当前环境不支持授权操作')
  }
}

function flashToast(text) {
  toastText.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastText.value = '' }, 1500)
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.replace('/scripts')
}
</script>

<style scoped>
.page {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px 12px;
}

.topbar h1 {
  font-size: 18px;
  font-weight: 700;
}

.back-btn {
  background: transparent;
  color: #f5f5f7;
  font-size: 16px;
  padding: 8px 4px;
  min-height: 44px;
}

.spacer { width: 56px; }

.body {
  padding: 4px 16px calc(24px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sec {
  background: #17171f;
  border-radius: 14px;
  padding: 16px;
}

.sec h2 {
  font-size: 14px;
  font-weight: 600;
  color: #8a8a93;
  margin-bottom: 14px;
}

.seg {
  display: flex;
  background: #262633;
  border-radius: 12px;
  padding: 4px;
  gap: 4px;
  margin-bottom: 14px;
}

.seg button {
  flex: 1;
  min-height: 44px;
  border-radius: 9px;
  font-size: 15px;
  background: transparent;
  color: #8a8a93;
}

.seg button.active {
  background: #4f8cff;
  color: #fff;
  font-weight: 600;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field input[type="text"] {
  padding: 12px 14px;
  font-size: 15px;
  color: #f5f5f7;
  background: #262633;
  border: 1px solid #33333f;
  border-radius: 10px;
  outline: none;
}

.field input[type="text"]:focus { border-color: #4f8cff; }

.field-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
}

.field-label {
  width: 64px;
  flex-shrink: 0;
  font-size: 15px;
  color: #c7c7cf;
}

.slider {
  flex: 1;
  accent-color: #4f8cff;
  height: 32px;
}

.field-val {
  width: 52px;
  text-align: right;
  font-size: 15px;
  color: #f5f5f7;
}

.colors {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.dot {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 2px solid transparent;
  padding: 0;
}

.dot.active {
  border-color: #4f8cff;
  box-shadow: 0 0 0 2px #0b0b0f, 0 0 0 4px #4f8cff;
}

.color-input {
  width: 36px;
  height: 36px;
  border: none;
  background: none;
  padding: 0;
  border-radius: 8px;
}

.switch {
  width: 56px;
  height: 32px;
  border-radius: 16px;
  background: #262633;
  position: relative;
  transition: background 0.2s;
}

.switch.on { background: #4f8cff; }

.switch .knob {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: #fff;
  transition: left 0.2s;
}

.switch.on .knob { left: 27px; }

.perm-tip {
  font-size: 14px;
  color: #8a8a93;
}

.perm-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 40px;
  font-size: 15px;
}

.perm-state { color: #ffb84d; }
.perm-state.ok { color: #3dff88; }

.grant-btn {
  width: 100%;
  margin-top: 10px;
  min-height: 48px;
  border-radius: 12px;
  background: #4f8cff;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.toast {
  position: fixed;
  left: 50%;
  bottom: 90px;
  transform: translateX(-50%);
  background: rgba(0, 0, 0, 0.85);
  color: #fff;
  font-size: 14px;
  padding: 10px 18px;
  border-radius: 20px;
  white-space: nowrap;
}
</style>

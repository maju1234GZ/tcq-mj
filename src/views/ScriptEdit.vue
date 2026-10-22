<template>
  <div class="page">
    <header class="topbar">
      <button class="back-btn" @click="goBack">‹ 返回</button>
      <h1>{{ isNew ? '新建台本' : '编辑台本' }}</h1>
      <button class="save-btn" @click="save">保存</button>
    </header>

    <input
      v-model="title"
      class="title-input"
      type="text"
      placeholder="台本标题"
      maxlength="50"
    />

    <textarea
      v-model="content"
      class="content-input"
      placeholder="在此输入或粘贴台本正文…"
    ></textarea>

    <div class="footer">
      <span class="count">{{ content.length }} 字</span>
    </div>

    <div v-if="toastText" class="toast">{{ toastText }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getScripts, saveScript } from '../utils/store'

const route = useRoute()
const router = useRouter()

const id = ref(null)
const title = ref('')
const content = ref('')
const toastText = ref('')
let toastTimer = null

const isNew = computed(() => !id.value)

onMounted(() => {
  const paramId = route.params.id
  if (paramId) {
    const found = getScripts().find(s => String(s.id) === String(paramId))
    if (found) {
      id.value = found.id
      title.value = found.title || ''
      content.value = found.content || ''
    } else {
      showToast('台本不存在，已切换为新建')
    }
  }
})

function showToast(text) {
  toastText.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastText.value = '' }, 1800)
}

function save() {
  saveScript({
    id: id.value,
    title: title.value.trim() || '未命名台本',
    content: content.value
  })
  router.replace('/scripts')
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.replace('/scripts')
}
</script>

<style scoped>
.page {
  height: 100%;
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

.save-btn {
  background: #4f8cff;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  padding: 10px 20px;
  border-radius: 12px;
  min-height: 44px;
}

.title-input {
  margin: 4px 16px 12px;
  padding: 14px;
  font-size: 17px;
  font-weight: 600;
  color: #f5f5f7;
  background: #17171f;
  border: 1px solid #262633;
  border-radius: 12px;
  outline: none;
}

.title-input:focus { border-color: #4f8cff; }

.content-input {
  flex: 1;
  margin: 0 16px;
  padding: 14px;
  font-size: 16px;
  line-height: 1.7;
  color: #f5f5f7;
  background: #17171f;
  border: 1px solid #262633;
  border-radius: 12px;
  outline: none;
  resize: none;
}

.content-input:focus { border-color: #4f8cff; }

.footer {
  padding: 12px 20px calc(16px + env(safe-area-inset-bottom));
  display: flex;
  justify-content: flex-end;
}

.count {
  font-size: 14px;
  color: #8a8a93;
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

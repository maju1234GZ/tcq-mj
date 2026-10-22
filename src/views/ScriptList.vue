<template>
  <div class="page">
    <header class="topbar">
      <h1>我的台本</h1>
      <button class="gear-btn" @click="$router.push('/settings')">设置</button>
    </header>

    <div v-if="!scripts.length" class="empty">
      <p class="empty-title">还没有台本</p>
      <p class="empty-sub">点击下方按钮，新建你的第一个台本</p>
    </div>

    <ul v-else class="list">
      <li v-for="s in scripts" :key="s.id" class="card">
        <div class="info" @click="editScript(s)">
          <div class="title">{{ s.title || '未命名台本' }}</div>
          <div class="meta">{{ (s.content || '').length }} 字 · {{ fmtTime(s.updatedAt) }}</div>
        </div>
        <div class="actions">
          <button class="btn primary" @click="play(s)">开拍</button>
          <button class="btn" @click="editScript(s)">编辑</button>
          <button class="btn danger" @click="removeScript(s)">删除</button>
        </div>
      </li>
    </ul>

    <button class="fab" @click="$router.push('/edit')">＋ 新建台本</button>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getScripts, deleteScript } from '../utils/store'

const router = useRouter()
const scripts = ref([])

onMounted(() => {
  scripts.value = getScripts()
})

function fmtTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function play(s) {
  router.push(`/play/${s.id}`)
}

function editScript(s) {
  router.push(`/edit/${s.id}`)
}

function removeScript(s) {
  if (window.confirm(`删除「${s.title || '未命名台本'}」？此操作不可恢复。`)) {
    deleteScript(s.id)
    scripts.value = getScripts()
  }
}
</script>

<style scoped>
.page {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  padding-bottom: calc(96px + env(safe-area-inset-bottom));
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px 12px;
}

.topbar h1 {
  font-size: 22px;
  font-weight: 700;
}

.gear-btn {
  background: #1c1c26;
  color: #f5f5f7;
  font-size: 15px;
  padding: 10px 18px;
  border-radius: 12px;
  min-height: 44px;
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #8a8a93;
}

.empty-title { font-size: 18px; }
.empty-sub { font-size: 14px; }

.list {
  list-style: none;
  padding: 4px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card {
  background: #17171f;
  border-radius: 14px;
  padding: 14px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.info {
  flex: 1;
  min-width: 0;
}

.title {
  font-size: 17px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  margin-top: 6px;
  font-size: 13px;
  color: #8a8a93;
}

.actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.btn {
  min-height: 40px;
  padding: 8px 16px;
  border-radius: 10px;
  font-size: 15px;
  background: #262633;
  color: #f5f5f7;
}

.btn.primary {
  background: #4f8cff;
  font-weight: 600;
}

.btn.danger {
  color: #ff6b6b;
  background: #2a1d22;
}

.fab {
  position: fixed;
  left: 16px;
  right: 16px;
  bottom: calc(20px + env(safe-area-inset-bottom));
  height: 56px;
  border-radius: 16px;
  background: #4f8cff;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  box-shadow: 0 6px 20px rgba(79, 140, 255, 0.35);
}
</style>

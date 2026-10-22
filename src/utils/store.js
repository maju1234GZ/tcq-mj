const SCRIPTS_KEY = 'tp_scripts'
const SETTINGS_KEY = 'tp_settings'
const SERVER_URL_KEY = 'tp_server_url'

export const DEFAULT_SETTINGS = {
  source: 'system',
  serverUrl: '',
  fontSize: 34,
  color: '#FFFFFF',
  bgOpacity: 0.35,
  speed: 40,
  lineSpacing: 1.4,
  mirror: false
}

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // 存储失败（如隐私模式）静默降级
  }
}

export function getScripts() {
  const list = read(SCRIPTS_KEY, [])
  return Array.isArray(list) ? list : []
}

export function saveScript(script) {
  const list = getScripts()
  const now = Date.now()
  const item = { ...script, updatedAt: now }
  if (!item.id) item.id = now.toString(36) + Math.random().toString(36).slice(2, 8)
  const idx = list.findIndex(s => String(s.id) === String(item.id))
  if (idx >= 0) list[idx] = item
  else list.unshift(item)
  write(SCRIPTS_KEY, list)
  return item
}

export function deleteScript(id) {
  write(SCRIPTS_KEY, getScripts().filter(s => String(s.id) !== String(id)))
}

export function getSettings() {
  const saved = read(SETTINGS_KEY, {})
  const settings = { ...DEFAULT_SETTINGS, ...(saved || {}) }
  // serverUrl 同时以 tp_server_url 独立 key 存储，读取时以独立 key 为准
  try {
    const raw = localStorage.getItem(SERVER_URL_KEY)
    if (raw !== null) settings.serverUrl = JSON.parse(raw)
  } catch {
    // 忽略，回退到 tp_settings 中的值
  }
  return settings
}

export function saveSettings(settings) {
  write(SETTINGS_KEY, { ...DEFAULT_SETTINGS, ...settings })
  try {
    localStorage.setItem(SERVER_URL_KEY, JSON.stringify(settings.serverUrl || ''))
  } catch {
    // 静默降级
  }
}

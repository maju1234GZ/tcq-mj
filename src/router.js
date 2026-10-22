import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/scripts' },
  { path: '/scripts', name: 'scripts', component: () => import('./views/ScriptList.vue') },
  { path: '/edit/:id?', name: 'edit', component: () => import('./views/ScriptEdit.vue') },
  { path: '/play/:id', name: 'play', component: () => import('./views/PrompterPlay.vue') },
  { path: '/settings', name: 'settings', component: () => import('./views/Settings.vue') }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})

import 'element-plus/dist/index.css'
import './styles/main.css'

import ElementPlus from 'element-plus'
import { createApp } from 'vue'

import App from './App.vue'
import { configureApiHooks } from './api/client'
import { router } from './router'
import { adminSession } from './services/adminSession'

configureApiHooks({
  csrfToken: () => adminSession.csrfToken.value,
  onCSRFToken: (token) => adminSession.rotateCSRF(token),
  onUnauthorized: async (path) => {
    if (path === '/api/v1/admin/login') return
    adminSession.expire()
    if (router.currentRoute.value.name !== 'login') {
      await router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  },
})

async function bootstrap(): Promise<void> {
  try {
    await adminSession.restore()
  } catch {
    // A transient restore failure leaves the app on login; the user can retry explicitly.
  }

  const app = createApp(App)
  app.use(ElementPlus).use(router).mount('#app')
}

void bootstrap()

import 'element-plus/dist/index.css'
import './styles/main.css'

import { ElAlert, ElButton, ElContainer, ElEmpty, ElHeader, ElIcon, ElMain } from 'element-plus'
import { createApp } from 'vue'

import App from './App.vue'
import { configureApiHooks } from './api/client'
import { router } from './router'

configureApiHooks({
  onUnauthorized: async () => {
    if (router.currentRoute.value.name !== 'login') {
      await router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  },
})

const app = createApp(App)
for (const component of [ElAlert, ElButton, ElContainer, ElEmpty, ElHeader, ElIcon, ElMain]) {
  app.component(component.name ?? '', component)
}
app.use(router).mount('#app')

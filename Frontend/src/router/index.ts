import { readonly, ref } from 'vue'
import { createRouter, createWebHistory, type Router } from 'vue-router'

import LoginView from '@/views/LoginView.vue'
import ProtectedShell from '@/views/ProtectedShell.vue'

const authenticated = ref(false)

export const sessionState = {
  authenticated: readonly(authenticated),
  // Stage 01 session restoration is the only code allowed to set this true.
  setAuthenticated(value: boolean): void {
    authenticated.value = value
  },
}

export function installAuthenticationGuard(router: Router, isAuthenticated: () => boolean): void {
  router.beforeEach((to) => {
    if (to.meta.requiresAuth && !isAuthenticated()) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (to.name === 'login' && isAuthenticated()) return { name: 'home' }
    return true
  })
}

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      name: 'home',
      component: ProtectedShell,
      meta: { requiresAuth: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

installAuthenticationGuard(router, () => sessionState.authenticated.value)

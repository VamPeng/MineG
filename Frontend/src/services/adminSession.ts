import { computed, readonly, ref } from 'vue'

import { ApiProblem, apiClient } from '@/api/client'

const authenticated = ref(false)
const csrfToken = ref<string>()
const username = ref('')
const expiredMessage = ref('')

export const adminSession = {
  authenticated: readonly(authenticated),
  username: readonly(username),
  expiredMessage: readonly(expiredMessage),
  csrfToken: computed(() => csrfToken.value),

  async restore(): Promise<void> {
    try {
      const session = await apiClient.restoreAdminSession()
      authenticated.value = true
      username.value = session.username
      csrfToken.value = session.csrf_token
      expiredMessage.value = ''
    } catch (error) {
      authenticated.value = false
      username.value = ''
      csrfToken.value = undefined
      if (!(error instanceof ApiProblem && [401, 404].includes(error.status))) throw error
    }
  },

  async signIn(nextUsername: string, password: string): Promise<void> {
    const session = await apiClient.adminSignIn(nextUsername, password)
    authenticated.value = true
    username.value = session.username
    csrfToken.value = session.csrf_token
    expiredMessage.value = ''
  },

  async signOut(): Promise<void> {
    await apiClient.adminSignOut()
    authenticated.value = false
    username.value = ''
    csrfToken.value = undefined
  },

  rotateCSRF(token: string): void {
    csrfToken.value = token
  },

  expire(message = '登录会话已过期，请重新登录。'): void {
    const wasAuthenticated = authenticated.value
    authenticated.value = false
    username.value = ''
    csrfToken.value = undefined
    if (wasAuthenticated) expiredMessage.value = message
  },
}

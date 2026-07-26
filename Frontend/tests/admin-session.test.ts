import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { adminSession } from '@/services/adminSession'

describe('administrator session state', () => {
  afterEach(() => {
    adminSession.expire('')
    vi.restoreAllMocks()
  })

  it('holds only the server session state and CSRF token in memory', async () => {
    vi.spyOn(apiClient, 'adminSignIn').mockResolvedValue({ username: 'reviewer', csrf_token: 'csrf-login' })

    await adminSession.signIn('reviewer', 'not-persisted')

    expect(adminSession.authenticated.value).toBe(true)
    expect(adminSession.username.value).toBe('reviewer')
    expect(adminSession.csrfToken.value).toBe('csrf-login')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('keeps the session visible when logout cannot be confirmed by the service', async () => {
    vi.spyOn(apiClient, 'adminSignIn').mockResolvedValue({ username: 'reviewer', csrf_token: 'csrf-login' })
    vi.spyOn(apiClient, 'adminSignOut').mockRejectedValue(new Error('offline'))
    await adminSession.signIn('reviewer', 'not-persisted')

    await expect(adminSession.signOut()).rejects.toThrow('offline')

    expect(adminSession.authenticated.value).toBe(true)
    expect(adminSession.csrfToken.value).toBe('csrf-login')
  })
})

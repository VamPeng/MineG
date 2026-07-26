import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { installAuthenticationGuard } from '@/router'

describe('authentication guard', () => {
  it('redirects unauthenticated protected navigation into the login shell', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/protected', name: 'home', component: { template: '<div />' }, meta: { requiresAuth: true } },
      ],
    })
    installAuthenticationGuard(router, () => false)

    await router.push('/protected')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/protected')
  })
})

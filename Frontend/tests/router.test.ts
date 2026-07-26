import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { installAuthenticationGuard } from '@/router'
import { router as applicationRouter } from '@/router'

describe('authentication guard', () => {
  it('does not add mobile profile, avatar, key, media, or member routes', () => {
    const paths = applicationRouter.getRoutes().map((route) => route.path).join(' ')
    expect(paths).not.toMatch(/profile|avatar|key|grant|media|member/i)
  })

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

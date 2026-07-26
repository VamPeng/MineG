import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem, apiClient, configureApiHooks } from '@/api/client'

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    configureApiHooks({ csrfToken: () => undefined, onUnauthorized: () => undefined })
  })

  it('sends cookie and CSRF configuration without Web Storage tokens', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: 'ok', api_version: 'v1', server_time: '2026-07-26T00:00:00Z' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    configureApiHooks({ csrfToken: () => 'csrf-probe' })

    await apiClient.runPlatformProbe()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.credentials).toBe('include')
    expect(new Headers(init.headers).get('X-CSRF-Token')).toBe('csrf-probe')
  })

  it('keeps the request id and hides internal detail behind a stable title', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: 'https://errors.mineg.example/problems/DATABASE_NOT_READY',
            title: 'Service not ready',
            status: 503,
            detail: 'postgres password=must-never-render',
            code: 'DATABASE_NOT_READY',
            request_id: 'request-1234',
            retryable: true,
          }),
          { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await apiClient.runPlatformProbe().catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(ApiProblem)
    expect(error).toMatchObject({ code: 'DATABASE_NOT_READY', requestId: 'request-1234', retryable: true })
    expect(String(error)).not.toContain('password')
  })

  it('invokes the unauthorized hook on 401', async () => {
    const onUnauthorized = vi.fn()
    configureApiHooks({ onUnauthorized })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'Unauthenticated',
            status: 401,
            code: 'AUTH_REQUIRED',
            request_id: 'request-4010',
            retryable: false,
          }),
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    await apiClient.runPlatformProbe().catch(() => undefined)
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })
})

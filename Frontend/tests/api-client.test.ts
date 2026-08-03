import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiProblem, apiClient, configureApiHooks } from '@/api/client'

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    configureApiHooks({ csrfToken: () => undefined, onUnauthorized: () => undefined })
  })

  it('keeps the frozen admin surface free of mobile profile, avatar, and key operations', () => {
    expect(Object.keys(apiClient).sort()).toEqual([
      'adminSignIn',
      'adminSignOut',
      'approveApplication',
      'getApproval',
      'listApprovals',
      'restoreAdminSession',
      'runPlatformProbe',
    ])
    expect(Object.keys(apiClient).join(' ')).not.toMatch(/avatar|bundle|grant|profile|media|member|family|trash|feedback/i)
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

  it('sends an approval idempotency key and accepts the rotated CSRF token', async () => {
    const onCSRFToken = vi.fn()
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          approval: {
            id: '7e44418a-38db-4db9-87fd-f67b1db70d30',
            masked_phone: '138****8000',
            status: 'PROCESSED',
            created_at: '2026-07-26T00:00:00Z',
          },
          outcome: 'APPROVED',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': 'csrf-rotated' } },
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    configureApiHooks({ csrfToken: () => 'csrf-current', onCSRFToken })

    await apiClient.approveApplication('7e44418a-38db-4db9-87fd-f67b1db70d30', 'approve-request-0001')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(url).toContain('/api/v1/admin/approvals/7e44418a-38db-4db9-87fd-f67b1db70d30/approve')
    expect(headers.get('Idempotency-Key')).toBe('approve-request-0001')
    expect(headers.get('X-CSRF-Token')).toBe('csrf-current')
    expect(onCSRFToken).toHaveBeenCalledWith('csrf-rotated')
  })
})

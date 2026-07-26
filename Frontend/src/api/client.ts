import type { components } from './schema'

export type PlatformProbe = components['schemas']['PlatformProbe']
export type ProblemPayload = components['schemas']['Problem']

export class ApiProblem extends Error {
  readonly code: string
  readonly status: number
  readonly requestId: string
  readonly retryable: boolean
  readonly title: string

  constructor(payload: ProblemPayload, fallbackRequestId = '') {
    super(payload.title)
    this.name = 'ApiProblem'
    this.code = payload.code
    this.status = payload.status
    this.requestId = payload.request_id || fallbackRequestId
    this.retryable = payload.retryable
    this.title = payload.title
  }
}

export class NetworkProblem extends Error {
  readonly code = 'NETWORK_UNAVAILABLE'
  readonly status = 0
  readonly requestId = ''
  readonly retryable = true
  readonly title = '网络暂时不可用'

  constructor(cause?: unknown) {
    super('网络暂时不可用', { cause })
    this.name = 'NetworkProblem'
  }
}

type ApiHooks = {
  csrfToken?: () => string | undefined
  onUnauthorized?: () => void | Promise<void>
}

let hooks: ApiHooks = {}

export function configureApiHooks(next: ApiHooks): void {
  hooks = { ...hooks, ...next }
}

type RequestOptions = Omit<RequestInit, 'signal'> & {
  signal?: AbortSignal
  timeoutMs?: number
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''

function isProblem(value: unknown): value is ProblemPayload {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<ProblemPayload>
  return (
    typeof candidate.title === 'string' &&
    typeof candidate.status === 'number' &&
    typeof candidate.code === 'string' &&
    typeof candidate.request_id === 'string' &&
    typeof candidate.retryable === 'boolean'
  )
}

function mergeSignals(parent: AbortSignal | undefined, timeoutMs: number): {
  signal: AbortSignal
  dispose: () => void
} {
  const controller = new AbortController()
  const abortFromParent = () => controller.abort(parent?.reason)
  if (parent?.aborted) abortFromParent()
  else parent?.addEventListener('abort', abortFromParent, { once: true })
  const timeout = window.setTimeout(() => controller.abort(new DOMException('Request timed out', 'TimeoutError')), timeoutMs)
  return {
    signal: controller.signal,
    dispose: () => {
      window.clearTimeout(timeout)
      parent?.removeEventListener('abort', abortFromParent)
    },
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { signal, dispose } = mergeSignals(options.signal, options.timeoutMs ?? 15_000)
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json, application/problem+json')
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const csrfToken = hooks.csrfToken?.()
  if (csrfToken) headers.set('X-CSRF-Token', csrfToken)

  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      credentials: 'include',
      headers,
      signal,
    })
    const requestId = response.headers.get('X-Request-ID') ?? ''
    if (response.status === 401) await hooks.onUnauthorized?.()
    if (!response.ok) {
      const contentType = response.headers.get('Content-Type') ?? ''
      if (contentType.includes('application/problem+json')) {
        const payload: unknown = await response.json()
        if (isProblem(payload)) throw new ApiProblem(payload, requestId)
      }
      throw new ApiProblem(
        {
          type: 'about:blank',
          title: '请求未完成',
          status: response.status,
          code: 'UNEXPECTED_RESPONSE',
          request_id: requestId,
          retryable: response.status >= 500,
        },
        requestId,
      )
    }
    return (await response.json()) as T
  } catch (error) {
    if (error instanceof ApiProblem || (error instanceof DOMException && error.name === 'AbortError')) throw error
    throw new NetworkProblem(error)
  } finally {
    dispose()
  }
}

export const apiClient = {
  runPlatformProbe: (signal?: AbortSignal) => request<PlatformProbe>('/api/v1/platform/probe', { signal }),
}

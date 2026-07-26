import { render, screen } from '@testing-library/vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'

import ErrorRetry from '@/components/ErrorRetry.vue'

describe('ErrorRetry', () => {
  it('renders stable information and request id without internal detail', () => {
    render(ErrorRetry, {
      global: { plugins: [ElementPlus] },
      props: {
        problem: {
          title: '服务暂不可用',
          code: 'SERVICE_UNAVAILABLE',
          requestId: 'request-safe-1',
          retryable: true,
        },
      },
    })
    expect(screen.getByRole('alert').textContent).toContain('request-safe-1')
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy()
  })
})

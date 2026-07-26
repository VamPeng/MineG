import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import ApprovalsView from '@/views/ApprovalsView.vue'

describe('approval list', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders only masked application data from the cursor page', async () => {
    vi.spyOn(apiClient, 'listApprovals').mockResolvedValue({
      items: [
        {
          id: '7e44418a-38db-4db9-87fd-f67b1db70d30',
          masked_phone: '138****8000',
          status: 'PENDING',
          created_at: '2026-07-26T00:00:00Z',
        },
      ],
      next_cursor: null,
    })

    const wrapper = mount(ApprovalsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('138****8000')
    expect(wrapper.text()).not.toContain('+8613800138000')
    expect(wrapper.text()).toContain('查看详情')
  })

  it('renders the real empty state for an empty page', async () => {
    vi.spyOn(apiClient, 'listApprovals').mockResolvedValue({ items: [], next_cursor: null })

    const wrapper = mount(ApprovalsView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('暂无待审核申请')
  })
})

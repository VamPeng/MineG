<template>
  <section aria-labelledby="approvals-heading">
    <header class="page-header">
      <div>
        <p class="eyebrow">
          成员准入
        </p>
        <h1 id="approvals-heading">
          待审核申请
        </h1>
        <p>仅显示脱敏手机号和申请时间。通过后仍需成员设备完成密钥授权。</p>
      </div>
      <el-button
        :loading="refreshing"
        :disabled="loading"
        @click="refresh"
      >
        刷新列表
      </el-button>
    </header>

    <div class="content-card">
      <LoadingState
        v-if="loading"
        label="正在读取待审核申请"
      />
      <ErrorRetry
        v-else-if="problem"
        :problem="problem"
        @retry="refresh"
      />
      <EmptyState
        v-else-if="items.length === 0"
        title="暂无待审核申请"
        description="新成员提交注册后会出现在这里。"
      />
      <template v-else>
        <div
          class="list-summary"
          aria-live="polite"
        >
          当前已加载 {{ items.length }} 条申请
        </div>
        <ul
          class="approval-list"
          aria-label="待审核申请列表"
        >
          <li
            v-for="item in items"
            :key="item.id"
          >
            <button
              class="approval-row"
              type="button"
              @click="openDetail(item.id)"
            >
              <span class="phone">{{ item.masked_phone }}</span>
              <span class="submitted">{{ formatDate(item.created_at) }}</span>
              <el-tag
                type="warning"
                effect="light"
              >
                待审核
              </el-tag>
              <span class="open-label">查看详情</span>
            </button>
          </li>
        </ul>
        <div
          v-if="nextCursor"
          class="load-more"
        >
          <el-button
            :loading="loadingMore"
            @click="loadMore"
          >
            加载更多
          </el-button>
        </div>
      </template>
    </div>

    <el-dialog
      v-model="detailVisible"
      title="申请详情"
      width="min(92vw, 520px)"
      :close-on-click-modal="!approving"
      :close-on-press-escape="!approving"
      @closed="resetDetail"
    >
      <LoadingState
        v-if="detailLoading"
        label="正在读取申请详情"
      />
      <ErrorRetry
        v-else-if="detailProblem"
        :problem="detailProblem"
        @retry="retryDetail"
      />
      <div
        v-else-if="selected"
        class="detail-content"
      >
        <dl>
          <div><dt>手机号</dt><dd>{{ selected.masked_phone }}</dd></div>
          <div><dt>注册时间</dt><dd>{{ formatDate(selected.created_at) }}</dd></div>
          <div><dt>当前状态</dt><dd>{{ selected.status === 'PENDING' ? '待审核' : '已处理' }}</dd></div>
        </dl>
        <el-alert
          title="隐私与密钥边界"
          description="通过只会创建密钥授权待办；管理端无法读取私钥、加密包或家庭媒体。"
          type="info"
          :closable="false"
          show-icon
        />
      </div>
      <template #footer>
        <el-button
          :disabled="approving"
          @click="detailVisible = false"
        >
          关闭
        </el-button>
        <ConfirmActionButton
          v-if="selected?.status === 'PENDING'"
          title="确认通过申请？"
          :message="`将通过 ${selected.masked_phone} 的注册申请。操作不可撤销，成员仍需等待密钥授权完成。`"
          confirm-label="确认通过"
          :loading="approving"
          :disabled="approving"
          @confirm="approveSelected"
        >
          通过申请
        </ConfirmActionButton>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { ApiProblem, NetworkProblem, apiClient, type Approval } from '@/api/client'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorRetry from '@/components/ErrorRetry.vue'
import LoadingState from '@/components/LoadingState.vue'
import { notify } from '@/services/notify'

type SafeProblem = { title: string; code: string; requestId: string; retryable: boolean }

const items = ref<Approval[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const loadingMore = ref(false)
const problem = ref<SafeProblem>()
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailProblem = ref<SafeProblem>()
const selected = ref<Approval>()
const selectedId = ref('')
const approving = ref(false)
const approvalIdempotencyKey = ref('')
let listController: ReturnType<typeof createAbortController> | undefined
let detailController: ReturnType<typeof createAbortController> | undefined

onMounted(() => loadPage())
onBeforeUnmount(() => { listController?.abort(); detailController?.abort() })

async function loadPage(cursor?: string, append = false): Promise<void> {
  listController?.abort()
  listController = createAbortController()
  if (!append) loading.value = items.value.length === 0
  problem.value = undefined
  try {
    const page = await apiClient.listApprovals(cursor, 20, listController.signal)
    items.value = append ? [...items.value, ...page.items] : page.items
    nextCursor.value = page.next_cursor
  } catch (error) {
    if (error instanceof globalThis.DOMException && error.name === 'AbortError') return
    problem.value = safeProblem(error, '列表加载失败')
  } finally {
    loading.value = false
  }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  try { await loadPage() } finally { refreshing.value = false }
}

async function loadMore(): Promise<void> {
  if (!nextCursor.value || loadingMore.value) return
  loadingMore.value = true
  try { await loadPage(nextCursor.value, true) } finally { loadingMore.value = false }
}

async function openDetail(id: string): Promise<void> {
  selectedId.value = id
  detailVisible.value = true
  await loadDetail(id)
}

async function loadDetail(id: string): Promise<void> {
  detailController?.abort()
  detailController = createAbortController()
  detailLoading.value = true
  detailProblem.value = undefined
  try {
    selected.value = await apiClient.getApproval(id, detailController.signal)
  } catch (error) {
    if (error instanceof globalThis.DOMException && error.name === 'AbortError') return
    detailProblem.value = safeProblem(error, '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function retryDetail(): void { if (selectedId.value) void loadDetail(selectedId.value) }

async function approveSelected(): Promise<void> {
  if (!selected.value || approving.value) return
  approving.value = true
  if (!approvalIdempotencyKey.value) approvalIdempotencyKey.value = globalThis.crypto.randomUUID()
  try {
    const result = await apiClient.approveApplication(selected.value.id, approvalIdempotencyKey.value)
    items.value = items.value.filter((item) => item.id !== selected.value?.id)
    selected.value = result.approval
    notify.success(
      result.outcome === 'APPROVED' ? '申请已通过' : '申请已由其他操作处理',
      '成员将在密钥授权完成后进入 App。',
    )
    detailVisible.value = false
  } catch (error) {
    const safe = safeProblem(error, '审核操作失败')
    notify.error(safe.title, safe.requestId ? `错误码 ${safe.code}，请求编号 ${safe.requestId}` : `错误码 ${safe.code}`)
  } finally {
    approving.value = false
  }
}

function resetDetail(): void {
  detailController?.abort()
  selected.value = undefined
  selectedId.value = ''
  detailProblem.value = undefined
  approvalIdempotencyKey.value = ''
}

function safeProblem(error: unknown, fallback: string): SafeProblem {
  if (error instanceof ApiProblem || error instanceof NetworkProblem) {
    return { title: error.title, code: error.code, requestId: error.requestId, retryable: error.retryable }
  }
  return { title: fallback, code: 'UNEXPECTED_ERROR', requestId: '', retryable: true }
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function createAbortController(): InstanceType<typeof globalThis.AbortController> {
  return new globalThis.AbortController()
}
</script>

<style scoped>
.page-header { align-items: flex-end; display: flex; gap: 24px; justify-content: space-between; margin-bottom: 24px; }
.eyebrow { color: #2a63db; font-size: 0.8rem; font-weight: 700; letter-spacing: 0.12em; margin: 0 0 8px; text-transform: uppercase; }
h1 { color: #182237; font-size: clamp(1.7rem, 3vw, 2.2rem); margin: 0 0 10px; }
.page-header p:last-child { color: #667187; margin: 0; }
.content-card { background: #fff; border: 1px solid #e1e6ee; border-radius: 16px; box-shadow: 0 10px 35px rgb(34 52 88 / 6%); min-height: 330px; overflow: hidden; padding: 10px; }
.list-summary { color: #7c8799; font-size: 0.82rem; padding: 12px 14px 8px; }
.approval-list { list-style: none; margin: 0; padding: 0; }
.approval-list li + li { border-top: 1px solid #edf0f5; }
.approval-row { align-items: center; background: transparent; border: 0; color: inherit; cursor: pointer; display: grid; gap: 18px; grid-template-columns: minmax(150px, 1fr) minmax(180px, 1fr) auto auto; padding: 18px 16px; text-align: left; width: 100%; }
.approval-row:hover { background: #f7f9fd; }
.approval-row:focus-visible { border-radius: 10px; }
.phone { color: #1c2b47; font-size: 1rem; font-weight: 680; letter-spacing: 0.03em; }
.submitted { color: #69758a; }
.open-label { color: #2b63d6; font-size: 0.88rem; font-weight: 650; }
.load-more { border-top: 1px solid #edf0f5; padding: 16px; text-align: center; }
.detail-content dl { margin: 0 0 22px; }
.detail-content dl div { border-bottom: 1px solid #edf0f5; display: grid; grid-template-columns: 110px 1fr; padding: 14px 0; }
.detail-content dt { color: #788398; }
.detail-content dd { color: #24324d; font-weight: 600; margin: 0; }
@media (max-width: 700px) { .page-header { align-items: stretch; flex-direction: column; } .approval-row { gap: 7px 12px; grid-template-columns: 1fr auto; } .submitted { grid-column: 1; } .approval-row :deep(.el-tag) { grid-column: 2; grid-row: 1; } .open-label { display: none; } }
</style>

<template>
  <section
    class="error-state"
    role="alert"
    aria-live="assertive"
  >
    <h2>{{ problem.title }}</h2>
    <p>错误码：{{ problem.code }}</p>
    <p
      v-if="problem.requestId"
      class="request-id"
    >
      请求编号：{{ problem.requestId }}
    </p>
    <el-button
      v-if="problem.retryable"
      type="primary"
      @click="$emit('retry')"
    >
      重试
    </el-button>
  </section>
</template>

<script setup lang="ts">
type SafeProblem = {
  title: string
  code: string
  requestId: string
  retryable: boolean
}

defineProps<{ problem: SafeProblem }>()
defineEmits<{ retry: [] }>()
</script>

<style scoped>
.error-state {
  border: 1px solid #f2c9c9;
  border-radius: 12px;
  color: #7a2525;
  padding: 24px;
}

.request-id {
  color: #697386;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.875rem;
}
</style>

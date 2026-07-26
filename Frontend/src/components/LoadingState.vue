<template>
  <section
    class="state"
    role="status"
    aria-live="polite"
  >
    <el-icon
      class="is-loading"
      :size="28"
    >
      <Loading />
    </el-icon>
    <span>{{ label }}</span>
  </section>
</template>

<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue'

withDefaults(defineProps<{ label?: string }>(), { label: '正在加载' })
</script>

<style scoped>
.state {
  align-items: center;
  color: #566176;
  display: flex;
  gap: 10px;
  justify-content: center;
  min-height: 180px;
}
</style>

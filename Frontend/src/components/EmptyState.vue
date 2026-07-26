<template>
  <el-empty :description="description">
    <template #image>
      <span
        class="empty-mark"
        aria-hidden="true"
      >—</span>
    </template>
    <strong>{{ title }}</strong>
  </el-empty>
</template>

<script setup lang="ts">
defineProps<{ title: string; description: string }>()
</script>

<style scoped>
.empty-mark {
  color: #8b96a9;
  font-size: 3rem;
}
</style>

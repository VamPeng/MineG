<template>
  <el-container class="protected-layout">
    <el-header class="header">
      <strong>MineG 管理端</strong>
    </el-header>
    <el-main id="main-content">
      <EmptyState
        title="暂无管理功能"
        description="账号审核功能将在阶段 01 交付。"
      />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
</script>

<style scoped>
.protected-layout {
  min-height: 100vh;
}

.header {
  align-items: center;
  border-bottom: 1px solid #e5e9f0;
  display: flex;
}
</style>

<template>
  <el-container class="protected-layout">
    <el-header class="header">
      <div class="brand-lockup">
        <span
          class="brand-mark"
          aria-hidden="true"
        >M</span>
        <div>
          <strong>MineG 审核管理端</strong>
          <span>账号准入</span>
        </div>
      </div>
      <div class="session-actions">
        <span class="username">{{ adminSession.username.value }}</span>
        <el-button
          :loading="signingOut"
          @click="signOut"
        >
          退出登录
        </el-button>
      </div>
    </el-header>
    <el-main
      id="main-content"
      class="main-content"
    >
      <ApprovalsView />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { adminSession } from '@/services/adminSession'
import { notify } from '@/services/notify'
import ApprovalsView from '@/views/ApprovalsView.vue'

const signingOut = ref(false)
const router = useRouter()

async function signOut(): Promise<void> {
  if (signingOut.value) return
  signingOut.value = true
  try {
    await adminSession.signOut()
    await router.replace({ name: 'login' })
  } catch {
    if (!adminSession.authenticated.value) {
      await router.replace({ name: 'login' })
    } else {
      notify.error('退出失败', '当前会话尚未确认撤销，请检查网络后重试。')
    }
  } finally {
    signingOut.value = false
  }
}
</script>

<style scoped>
.protected-layout { background: #f5f7fb; min-height: 100vh; }
.header { align-items: center; background: #fff; border-bottom: 1px solid #e2e7ef; display: flex; height: 72px; justify-content: space-between; padding: 0 max(24px, calc((100vw - 1180px) / 2)); }
.brand-lockup, .session-actions { align-items: center; display: flex; gap: 12px; }
.brand-lockup > div { display: flex; flex-direction: column; gap: 2px; }
.brand-lockup span:not(.brand-mark) { color: #778196; font-size: 0.75rem; }
.brand-mark { align-items: center; background: linear-gradient(135deg, #2368ed, #7655dc); border-radius: 9px; color: #fff; display: flex; font-weight: 800; height: 34px; justify-content: center; width: 34px; }
.username { color: #5b667a; font-size: 0.9rem; }
.main-content { margin: 0 auto; max-width: 1180px; padding: 34px 24px 60px; width: 100%; }
@media (max-width: 620px) { .header { height: 64px; padding: 0 16px; } .username, .brand-lockup span:not(.brand-mark) { display: none; } .main-content { padding: 24px 14px; } }
</style>

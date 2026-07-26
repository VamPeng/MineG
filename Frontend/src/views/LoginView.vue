<template>
  <main
    id="main-content"
    class="login-layout"
    aria-labelledby="login-heading"
  >
    <section class="login-panel">
      <div class="brand-row">
        <span
          class="brand-mark"
          aria-hidden="true"
        >M</span>
        <p
          class="brand"
          aria-label="MineG"
        >
          MineG
        </p>
      </div>
      <h1 id="login-heading">
        审核管理端
      </h1>
      <p class="supporting-copy">
        仅用于处理新成员准入申请，不提供媒体或密钥访问能力。
      </p>
      <el-alert
        v-if="adminSession.expiredMessage.value"
        :title="adminSession.expiredMessage.value"
        type="warning"
        :closable="false"
        show-icon
        class="session-alert"
      />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item
          label="管理员账号"
          prop="username"
        >
          <el-input
            v-model.trim="form.username"
            autocomplete="username"
            autofocus
            data-testid="admin-login-username"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item
          label="密码"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            data-testid="admin-login-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="form-error"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="submitting"
          class="submit-button"
          data-testid="admin-login-submit"
        >
          登录
        </el-button>
      </el-form>
      <p class="security-note">
        会话在 30 分钟无操作后失效，最长保持 8 小时。
      </p>
    </section>
  </main>
</template>

<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiProblem, NetworkProblem } from '@/api/client'
import { adminSession } from '@/services/adminSession'

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')
const router = useRouter()
const route = useRoute()
const rules: FormRules = {
  username: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit(): Promise<void> {
  if (submitting.value || !(await formRef.value?.validate().catch(() => false))) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await adminSession.signIn(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect
      : '/'
    await router.replace(redirect)
  } catch (error) {
    if (error instanceof ApiProblem && error.code === 'CREDENTIALS_INVALID') {
      errorMessage.value = '账号或密码错误。'
    } else if (error instanceof NetworkProblem) {
      errorMessage.value = '网络暂时不可用，请检查连接后重试。'
    } else if (error instanceof ApiProblem) {
      errorMessage.value = `登录失败（${error.code}，请求编号 ${error.requestId || '未知'}）。`
    } else {
      errorMessage.value = '登录失败，请稍后重试。'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-layout {
  align-items: center;
  background:
    radial-gradient(circle at 14% 10%, rgb(72 123 244 / 20%), transparent 32%),
    radial-gradient(circle at 88% 86%, rgb(111 85 224 / 15%), transparent 30%),
    #f4f7fc;
  display: flex;
  min-height: 100vh;
  padding: 24px;
}

.login-panel {
  background: rgb(255 255 255 / 96%);
  border: 1px solid #dce3ef;
  border-radius: 20px;
  box-shadow: 0 24px 70px rgb(29 49 91 / 14%);
  margin: auto;
  max-width: 440px;
  padding: 40px;
  width: 100%;
}

.brand-row { align-items: center; display: flex; gap: 10px; margin-bottom: 28px; }
.brand-mark { align-items: center; background: linear-gradient(135deg, #2368ed, #7655dc); border-radius: 10px; color: #fff; display: flex; font-weight: 800; height: 36px; justify-content: center; width: 36px; }
.brand { color: #203253; font-size: 1.18rem; font-weight: 760; letter-spacing: 0.04em; margin: 0; }
h1 { color: #172033; font-size: 1.8rem; margin: 0 0 10px; }
.supporting-copy { color: #5d687d; line-height: 1.65; margin: 0 0 24px; }
.session-alert, .form-error { margin-bottom: 20px; }
.submit-button { margin-top: 4px; width: 100%; }
.security-note { color: #7b8495; font-size: 0.82rem; margin: 22px 0 0; text-align: center; }

@media (max-width: 520px) {
  .login-layout { padding: 12px; }
  .login-panel { border-radius: 16px; padding: 28px 22px; }
}
</style>

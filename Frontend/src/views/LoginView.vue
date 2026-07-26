<template>
  <main
    id="main-content"
    class="login-layout"
    aria-labelledby="login-heading"
  >
    <section class="login-panel">
      <p
        class="brand"
        aria-label="MineG"
      >
        MineG
      </p>
      <h1 id="login-heading">
        管理端登录
      </h1>
      <p class="supporting-copy">
        管理员会话登录将在阶段 01 接入。目前没有模拟账号，也不能绕过会话进入管理区。
      </p>
      <el-alert
        title="工程基座已就绪"
        description="Cookie 会话、CSRF 请求头、401 跳转与统一错误处理已建立。"
        type="info"
        :closable="false"
        show-icon
      />
    </section>
  </main>
</template>

<style scoped>
.login-layout {
  align-items: center;
  background: radial-gradient(circle at top left, #e7f0ff 0, #f7f9fc 48%, #eef2f7 100%);
  display: flex;
  min-height: 100vh;
  padding: 24px;
}

.login-panel {
  background: #fff;
  border: 1px solid #dfe5ed;
  border-radius: 18px;
  box-shadow: 0 18px 50px rgb(28 45 76 / 10%);
  margin: auto;
  max-width: 440px;
  padding: 40px;
  width: 100%;
}

.brand {
  color: #2357d9;
  font-size: 1.25rem;
  font-weight: 750;
  letter-spacing: 0.04em;
  margin: 0 0 24px;
}

h1 {
  color: #172033;
  font-size: 1.75rem;
  margin: 0 0 12px;
}

.supporting-copy {
  color: #566176;
  line-height: 1.65;
  margin: 0 0 24px;
}
</style>

import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const tls = env.MINEG_DEV_TLS_KEY && env.MINEG_DEV_TLS_CERT
    ? { key: readFileSync(env.MINEG_DEV_TLS_KEY), cert: readFileSync(env.MINEG_DEV_TLS_CERT) }
    : undefined
  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      https: tls,
      proxy: {
        '/api': { target: env.MINEG_DEV_API_TARGET || 'http://127.0.0.1:8080' },
      },
    },
    test: {
      environment: 'happy-dom',
      setupFiles: ['./tests/setup.ts'],
      restoreMocks: true,
    },
  }
})

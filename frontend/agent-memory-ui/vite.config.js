import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// agent-memory-ui 独立前端：dev 阶段 /api 代理到 agent-memory 独立后端（默认 9900）
// 若后端用 AGENT_MEMORY_PORT 改了端口，前端用同名环境变量保持一致：
//   后端 ./run.sh              AGENT_MEMORY_PORT=9901 ./run.sh
//   前端 npm run dev           AGENT_MEMORY_PORT=9901 npm run dev
const backendPort = process.env.AGENT_MEMORY_PORT || '9900'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 6060,
    proxy: {
      '/api': {
        target: `http://localhost:${backendPort}`,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1024
  }
})

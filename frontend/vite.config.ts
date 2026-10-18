import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    // 代理到 Spring Boot 后端，避免开发期跨域
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          // 3D 与图表体积较大，单独分包，避免首屏加载过慢
          three: ['three'],
          echarts: ['echarts'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// The Spring Boot jar serves everything under /static, so the build lands there and
// the app is shipped as a single artifact.
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/v1': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080'
    }
  }
})
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  base: '/',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      react: path.resolve(__dirname, 'node_modules/react'),
      'react-dom': path.resolve(__dirname, 'node_modules/react-dom'),
    },
  },
  server: {
    port: 3000,
    host: true,
    proxy: {
      '/api/yahoo': {
        target: 'https://query1.finance.yahoo.com',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/yahoo/, ''),
      },
      '/api/feedback': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/monitor': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/market': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/kr': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/analysis': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 300000,
      },
      '/api/main': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/conditions': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/health': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/auth': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/subscription': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/research': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 조건검색 엔진. 운영은 Spring Boot /api/engine 이 인증 후 엔진으로 넘기고,
      // dev 는 로컬 엔진(8090)으로 바로 보낸다.
      '/api/engine': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
        timeout: 120000,
        rewrite: (path) => path.replace(/^\/api\/engine/, '/engine'),
      },
    },
  },
})

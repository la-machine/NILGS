// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Same origin in development as in deployment (ADR-010): the dev server
// forwards /api to the backend started with `./mvnw spring-boot:run`.
// No default ports (README "Ports").
export default defineConfig({
  plugins: [react()],
  server: {
    port: 47173,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:47080',
    },
  },
  preview: {
    port: 47174,
    strictPort: true,
  },
})

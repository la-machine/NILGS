// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// Component and unit tests (Baseline E-07), in jsdom: no browser, no network, no WebGL.
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
})

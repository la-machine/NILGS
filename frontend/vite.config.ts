// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { readFileSync } from 'node:fs'
import { Agent } from 'node:https'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

const repoRoot = fileURLToPath(new URL('..', import.meta.url))
const secret = (name: string) => readFileSync(`${repoRoot}infra/secrets/${name}`)

// Development server (ADR-010, ADR-017): the same rules as the deployed proxy.
//   browser → dev server  HTTPS (TLS 1.3) with the development certificate
//   dev server → backend  mutual TLS 1.3 with the proxy's client identity
// Values come from the repository's .env (ADR-014); certificates from
// infra/secrets (bash infra/scripts/dev-setup.sh). Production builds need neither.
export default defineConfig(({ command, mode }) => {
  if (command !== 'serve') {
    // MapLibre (about 1 MB) is its own chunk, loaded only when a geographic or satellite view
    // is opened; every other chunk stays well under this limit.
    return { plugins: [react()], build: { chunkSizeWarningLimit: 1100 } }
  }
  const env = loadEnv(mode, repoRoot, 'SNIGF_')
  const required = (key: string) => {
    const value = env[key]
    if (!value) throw new Error(`${key} must be set in .env`)
    return value
  }
  const tls = {
    cert: secret('web_tls_crt'),
    key: secret('web_tls_key'),
    minVersion: 'TLSv1.3' as const,
  }
  const toBackend = new Agent({
    ca: secret('pki_ca_chain_crt'),
    cert: secret('web_client_crt'),
    key: secret('web_client_key'),
    minVersion: 'TLSv1.3',
    rejectUnauthorized: true,
  })
  return {
    plugins: [react()],
    server: {
      port: Number(required('SNIGF_VITE_PORT')),
      strictPort: true,
      https: tls,
      proxy: {
        '/api': {
          target: `https://localhost:${required('SNIGF_APP_PORT')}`,
          secure: true,
          agent: toBackend,
          // Like Caddy's header_up: the browser's address, overwriting any client value.
          configure: (proxy) => {
            const header = required('SNIGF_PROXY_CLIENT_ADDRESS_HEADER')
            proxy.on('proxyReq', (proxyReq, req) => {
              proxyReq.setHeader(header, req.socket.remoteAddress ?? '')
            })
          },
        },
      },
    },
    preview: {
      port: Number(required('SNIGF_VITE_PREVIEW_PORT')),
      strictPort: true,
      https: tls,
    },
  }
})

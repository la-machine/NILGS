// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
//
// DEMO payment simulator (ADR-037). NO REAL MONEY MOVES: it holds no account, calls no payment
// network and reaches nothing outside the stack. It plays a payment provider:
//
//   POST /v1/charges   mutual TLS 1.3, only from the application's provider-client certificate
//                      (SNIGF_PAYMENT_APP_CLIENT_CN); answers a transaction id, then sends the
//                      scenario's callbacks after SNIGF_PAYMENT_SIM_CALLBACK_DELAY_MS
//   callbacks          to the proxy's provider listener (SNIGF_PAYMENT_SIM_CALLBACK_URL), mutual
//                      TLS 1.3 with its own certificate; signed with HMAC-SHA-256 over
//                      "<timestamp>.<body>" computed INSIDE OpenBao with the payment callback
//                      key. The simulator logs in to OpenBao with its certificate; its policy
//                      allows that HMAC and nothing else, and it never holds the key.
//
// Scenarios: SUCCEED, FAIL, NO_RESPONSE, DUPLICATE (the success twice), WRONG_AMOUNT (another
// amount), UNKNOWN_REFERENCE (a reference nobody issued). A failed delivery is retried three
// times; the platform treats duplicates as such. No dependency beyond Node itself.
import https from 'node:https'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'

const required = (name) => {
  const value = process.env[name]
  if (!value) {
    console.error(`payment-sim: ${name} is required`)
    process.exit(1)
  }
  return value
}

const PORT = Number(required('SNIGF_PAYMENT_SIM_PORT'))
const ALLOWED_CLIENT = required('SNIGF_PAYMENT_APP_CLIENT_CN')
const CALLBACK_URL = new URL(required('SNIGF_PAYMENT_SIM_CALLBACK_URL'))
const DELAY_MS = Number(required('SNIGF_PAYMENT_SIM_CALLBACK_DELAY_MS'))
const BAO_URL = new URL(required('SNIGF_BAO_URL'))
const BAO_AUTH_MOUNT = required('SNIGF_BAO_AUTH_MOUNT')
const BAO_ROLE = required('SNIGF_BAO_PAYMENT_SIM_ROLE')
const TRANSIT = required('SNIGF_BAO_TRANSIT_MOUNT')
const KEY = required('SNIGF_KEY_PAYMENT_CALLBACK')

const SCENARIOS = new Set(['SUCCEED', 'FAIL', 'NO_RESPONSE', 'DUPLICATE', 'WRONG_AMOUNT', 'UNKNOWN_REFERENCE'])
const REFERENCE = /^DEMO-PAY-[0-9]{4}-[0-9]{8}-[0-9A-Z]$/
const UNKNOWN_REFERENCE = 'DEMO-PAY-0000-00000000-0'
const MAX_BODY = 4096
const TIMEOUT_MS = 5000

const secret = (name) => readFileSync(`/run/secrets/${name}`)
const CA = secret('pki_ca_chain_crt')
const CLIENT_TLS = {
  cert: secret('payment_sim_client_crt'),
  key: secret('payment_sim_client_key'),
  ca: CA,
  minVersion: 'TLSv1.3',
  maxVersion: 'TLSv1.3',
}

const log = (...parts) => console.log('payment-sim:', ...parts)
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** One HTTPS call with the simulator's client certificate; resolves { status, body }. */
function call(url, method, headers, payload) {
  return new Promise((resolve, reject) => {
    const request = https.request(url, { method, headers, ...CLIENT_TLS, servername: url.hostname, timeout: TIMEOUT_MS },
      (response) => {
        const chunks = []
        response.on('data', (chunk) => chunks.push(chunk))
        response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString('utf8') }))
      })
    request.on('timeout', () => request.destroy(new Error('timeout')))
    request.on('error', reject)
    if (payload !== undefined) request.write(payload)
    request.end()
  })
}

// --- OpenBao: certificate login, then HMAC with the callback key --------------------------------

let token = null
let renewAfter = 0

async function login() {
  const url = new URL(`/v1/auth/${BAO_AUTH_MOUNT}/login`, BAO_URL)
  const answer = await call(url, 'POST', { 'Content-Type': 'application/json' }, JSON.stringify({ name: BAO_ROLE }))
  if (answer.status !== 200) throw new Error(`OpenBao refused the certificate login (HTTP ${answer.status})`)
  const auth = JSON.parse(answer.body).auth
  token = auth.client_token
  renewAfter = Date.now() + (auth.lease_duration * 1000 * 2) / 3
}

/** The signature header value, v<key version>=<base64>, for the message. */
async function sign(message) {
  for (let attempt = 0; attempt < 2; attempt++) {
    if (!token || Date.now() >= renewAfter) await login()
    const url = new URL(`/v1/${TRANSIT}/hmac/${KEY}/sha2-256`, BAO_URL)
    const answer = await call(url, 'POST', { 'Content-Type': 'application/json', 'X-Vault-Token': token },
      JSON.stringify({ input: message.toString('base64') }))
    if (answer.status === 403) {
      token = null
      continue
    }
    if (answer.status !== 200) throw new Error(`OpenBao refused the HMAC (HTTP ${answer.status})`)
    const match = /^vault:v([0-9]+):(.+)$/.exec(JSON.parse(answer.body).data.hmac)
    if (!match) throw new Error('OpenBao answered an unexpected HMAC format')
    return `v${match[1]}=${match[2]}`
  }
  throw new Error('OpenBao refused the HMAC twice')
}

// --- Callbacks ---------------------------------------------------------------------------------

async function deliver(report) {
  const body = JSON.stringify({
    provider: 'DEMO_SIMULATOR',
    transactionId: report.transactionId,
    reference: report.reference,
    amount: report.amount,
    currency: report.currency,
    status: report.status,
    occurredAt: new Date().toISOString(),
    demo: true,
  })
  for (let attempt = 1; attempt <= 4; attempt++) {
    try {
      const timestamp = String(Math.floor(Date.now() / 1000))
      const signature = await sign(Buffer.concat([Buffer.from(`${timestamp}.`, 'ascii'), Buffer.from(body, 'utf8')]))
      const answer = await call(CALLBACK_URL, 'POST', {
        'Content-Type': 'application/json',
        'X-Snigf-Timestamp': timestamp,
        'X-Snigf-Signature': signature,
      }, body)
      log(`callback ${report.transactionId} ${report.status} -> HTTP ${answer.status}`)
      if (answer.status < 500) return
    } catch (error) {
      log(`callback ${report.transactionId} attempt ${attempt} failed: ${error.message}`)
    }
    if (attempt < 4) await sleep(1000 * 2 ** (attempt - 1))
  }
  log(`callback ${report.transactionId} given up`)
}

function play(scenario, charge) {
  const success = { ...charge, status: 'SUCCEEDED' }
  switch (scenario) {
    case 'SUCCEED':
      return [success]
    case 'FAIL':
      return [{ ...charge, status: 'FAILED' }]
    case 'NO_RESPONSE':
      return []
    case 'DUPLICATE':
      return [success, success]
    case 'WRONG_AMOUNT':
      return [{ ...success, amount: charge.amount > 1000 ? charge.amount - 1000 : charge.amount + 1000 }]
    case 'UNKNOWN_REFERENCE':
      return [{ ...success, transactionId: `SIMTX-${randomUUID()}`, reference: UNKNOWN_REFERENCE }]
  }
  return []
}

async function run(scenario, charge) {
  await sleep(DELAY_MS)
  for (const report of play(scenario, charge)) {
    await deliver(report)
    await sleep(500)
  }
}

// --- The charge endpoint -----------------------------------------------------------------------

function answer(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(payload))
}

function valid(order) {
  return order && typeof order === 'object'
    && typeof order.reference === 'string' && REFERENCE.test(order.reference)
    && Number.isSafeInteger(order.amount) && order.amount > 0
    && typeof order.currency === 'string' && /^[A-Z]{3}$/.test(order.currency)
    && SCENARIOS.has(order.scenario) && order.demo === true
}

const server = https.createServer({
  cert: secret('payment_sim_tls_crt'),
  key: secret('payment_sim_tls_key'),
  ca: CA,
  requestCert: true,
  rejectUnauthorized: true,
  minVersion: 'TLSv1.3',
  maxVersion: 'TLSv1.3',
}, (request, response) => {
  const peer = request.socket.getPeerCertificate()
  if (!peer || !peer.subject || peer.subject.CN !== ALLOWED_CLIENT) {
    log(`refused a caller: certificate CN=${peer && peer.subject ? peer.subject.CN : '<none>'}`)
    return answer(response, 403, { code: 'CLIENT_REJECTED' })
  }
  if (request.method !== 'POST' || request.url !== '/v1/charges') return answer(response, 404, { code: 'NOT_FOUND' })
  const chunks = []
  let size = 0
  request.on('data', (chunk) => {
    size += chunk.length
    if (size > MAX_BODY) request.destroy()
    else chunks.push(chunk)
  })
  request.on('end', () => {
    let order
    try {
      order = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    } catch {
      return answer(response, 400, { code: 'MALFORMED' })
    }
    if (!valid(order)) return answer(response, 400, { code: 'MALFORMED' })
    const transactionId = `SIMTX-${randomUUID()}`
    log(`charge ${transactionId} ${order.reference} ${order.amount} ${order.currency} scenario ${order.scenario}`)
    answer(response, 202, { transactionId, demo: true })
    run(order.scenario, { transactionId, reference: order.reference, amount: order.amount, currency: order.currency })
      .catch((error) => log(`scenario ${transactionId} failed: ${error.message}`))
  })
})

server.listen(PORT, '0.0.0.0', () => log(`listening on ${PORT} (DEMO: no real money moves)`))
process.on('SIGTERM', () => server.close(() => process.exit(0)))

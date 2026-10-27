// @vitest-environment node
/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { once } from 'node:events'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import os from 'node:os'
import process from 'node:process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, test } from 'vitest'
import { setTimeout as delay } from 'node:timers/promises'
import { createServer, preview } from 'vite'

const configFile = fileURLToPath(new URL('../vite.config.js', import.meta.url))
const deadline = async (predicate, description) => {
  const until = Date.now() + 2000
  while (!predicate() && Date.now() < until) await delay(10)
  assert.ok(predicate(), description)
}
const reservePort = async () => {
  const server = net.createServer()
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const port = server.address().port
  await new Promise(resolve => server.close(resolve))
  return port
}

describe.sequential.each(['dev', 'preview'])('%s: actual API proxy owns only the departed exchange', mode => {
  let root, exchanges, sockets, backend, prior, vite
  const clients = new Set()
  beforeAll(async () => {
    root = await mkdtemp(path.join(os.tmpdir(), 'justsearch-proxy-lifetime-'))
    await writeFile(path.join(root, 'index.html'), '<!doctype html><title>owned proxy fixture</title>')
    exchanges = new Map()
    sockets = new Set()
    backend = http.createServer((req, res) => {
      const state = { req, res, closed: false, ended: false, received: '' }
      exchanges.set(req.url, state)
      res.on('close', () => { state.closed = true })
      req.on('data', chunk => { state.received += chunk })
      req.on('end', () => { state.ended = true })
      if (req.url.includes('held') || req.url.includes('upload')) return
      if (req.url.includes('sse')) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', Connection: 'keep-alive', 'X-Owner': 'backend' })
        res.write('data: first\n\n')
        return
      }
      if (req.url.includes('late-error')) {
        res.writeHead(200, { 'Content-Type': 'text/plain' })
        res.write('partial')
        return
      }
      if (req.url.includes('early-error')) { req.socket.destroy(); return }
      req.on('end', () => {
        res.writeHead(200, { 'Content-Type': 'application/json', 'X-Backend-Host': req.headers.host, 'X-End-To-End': 'kept',
          'Keep-Alive': 'timeout=999', 'Proxy-Authenticate': 'fixture-hop-header', Connection: 'close' })
        res.end(JSON.stringify({ path: req.url, method: req.method, body: state.received }))
      })
  })
  backend.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)) })
  backend.listen(0, '127.0.0.1')
  await once(backend, 'listening')
  prior = process.env.VITE_JUSTSEARCH_API_PORT
  process.env.VITE_JUSTSEARCH_API_PORT = String(backend.address().port)
  const port = await reservePort()
  const options = { configFile, root, logLevel: 'error', build: { outDir: root },
    server: { host: '127.0.0.1', port, strictPort: true, watch: null },
    preview: { host: '127.0.0.1', port, strictPort: true } }
  vite = mode === 'dev' ? await createServer(options) : await preview(options)
  if (mode === 'dev') await vite.listen()
  })
  const request = (route, options = {}) => {
    const req = http.request({ hostname: '127.0.0.1', port: vite.httpServer.address().port,
      path: route, agent: false, ...options })
    req.on('error', () => {})
    req.setTimeout(5000, () => req.destroy(new Error('owned proxy request timed out')))
    clients.add(req)
    req.on('close', () => clients.delete(req))
    return req
  }
  const finite = async (route, options = {}, body = '') => {
    const req = request(route, options)
    const response = once(req, 'response')
    req.end(body)
    const [res] = await response
    let text = ''
    for await (const chunk of res) text += chunk
    return { res, text }
  }
  test('complete GET departure before response headers cancels its exact upstream; later request works', async () => {
    const req = request('/api/held')
    req.end()
    await deadline(() => exchanges.get('/api/held')?.ended, 'backend received the complete GET')
    await delay(30)
    assert.equal(exchanges.get('/api/held').closed, false, 'completed GET body keeps a healthy response alive')
    req.destroy()
    await deadline(() => exchanges.get('/api/held').closed, 'downstream departure closes held upstream before headers')
    assert.equal((await finite('/api/recovered')).res.statusCode, 200)
  })
  test('incremental SSE remains alive after completed GET; one observer closure leaves another live', async () => {
    const open = async id => {
      const req = request(`/api/sse-${id}`)
      const response = once(req, 'response')
      req.end()
      const [res] = await response
      let text = ''
      res.on('data', chunk => { text += chunk })
      res.on('error', () => {})
      await deadline(() => text.includes('data: first\n\n'), 'first SSE frame arrives before EOF')
      return { req, res, text: () => text }
    }
    const a = await open('a'), b = await open('b')
    await delay(30)
    assert.equal(exchanges.get('/api/sse-a').closed, false)
    a.res.destroy()
    await deadline(() => exchanges.get('/api/sse-a').closed, 'only departed SSE upstream closes')
    assert.equal(exchanges.get('/api/sse-b').closed, false)
    exchanges.get('/api/sse-b').res.write('data: second\n\n')
    await deadline(() => b.text().includes('data: second\n\n'), 'other observer continues incrementally')
    b.res.destroy()
    await deadline(() => exchanges.get('/api/sse-b').closed, 'second observer owns its own close')
  })
  test('incomplete upload departure closes upstream without completing its body', async () => {
    const req = request('/api/upload', { method: 'POST', headers: { 'Content-Length': '100000' } })
    req.write('prefix')
    await deadline(() => exchanges.get('/api/upload')?.received === 'prefix', 'upload prefix reaches backend')
    req.destroy()
    await deadline(() => exchanges.get('/api/upload').closed, 'incomplete upload closes upstream')
    assert.equal(exchanges.get('/api/upload').ended, false)
  })
  test('upstream failure before headers is502; late failure terminates started response', async () => {
    assert.equal((await finite('/api/early-error')).res.statusCode, 502)
    const req = request('/api/late-error')
    const response = once(req, 'response')
    req.end()
    const [res] = await response
    let text = '', closed = false
    res.on('data', chunk => { text += chunk })
    res.on('error', () => {})
    res.on('close', () => { closed = true })
    await deadline(() => text === 'partial', 'partial response reaches client')
    assert.equal(res.statusCode, 200)
    exchanges.get('/api/late-error').res.destroy(new Error('controlled upstream failure'))
    await deadline(() => closed, 'late failure closes downstream after headers')
    assert.equal(res.complete, false)
    assert.equal((await finite('/api/after-error')).res.statusCode, 200)
  })
  test('finite JSON/POST, Host rewrite, well-known forwarding and Host refusal stay exact', async () => {
    for (const route of ['/api/finite', '/.well-known/justsearch/finite']) {
      const result = await finite(route, { method: 'POST', headers: { 'Content-Type': 'application/json' } }, '{"ok":true}')
      assert.equal(result.res.statusCode, 200)
      assert.deepEqual(JSON.parse(result.text), { path: route, method: 'POST', body: '{"ok":true}' })
      assert.equal(result.res.headers['x-backend-host'], `127.0.0.1:${backend.address().port}`)
      assert.equal(result.res.headers['keep-alive'], undefined)
      assert.equal(result.res.headers['proxy-authenticate'], undefined)
      assert.equal(result.res.headers['x-end-to-end'], 'kept')
    }
    const count = exchanges.size
    // Vite permits IP authorities; the non-local IP therefore exercises the
    // proxy's own Host gate rather than relying only on Vite's DNS allowlist.
    for (const host of ['remote.example', '192.0.2.1']) {
      const result = await finite('/api/refused', { headers: { Host: host } })
      assert.equal(result.res.statusCode, 403)
      assert.equal(exchanges.size, count)
    }
  })
  afterAll(async () => {
    for (const req of clients) req.destroy()
    for (const socket of sockets) socket.destroy()
    if (vite) vite.httpServer.closeAllConnections()
    if (vite) await (mode === 'dev' ? vite.close() : new Promise(resolve => vite.httpServer.close(resolve)))
    await new Promise(resolve => backend.close(resolve))
    if (prior === undefined) delete process.env.VITE_JUSTSEARCH_API_PORT
    else process.env.VITE_JUSTSEARCH_API_PORT = prior
    await rm(root, { recursive: true, force: true })
  })
})

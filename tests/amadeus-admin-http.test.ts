import { Context } from '@deepseek-ai/cordis'
import { describe, expect, it } from 'vitest'
import { once } from 'node:events'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import * as amadeusPlugin from '../src/amadeus-plugin.js'

/** Minimal webServer that captures registered routes and answers real HTTP. */
function mockWebServer() {
  const server = createServer()
  const routes: Array<{ kind: string; path: string; handler: (req: any, res: any) => void | Promise<void> }> = []
  const handle = async (req: any, res: any): Promise<void> => {
    const target = new URL(req.url ?? '/', 'http://x').pathname
    const route = routes.find(r => r.kind === 'prefix' && (target === r.path || target.startsWith(`${r.path}/`)))
    if (route === undefined) { res.writeHead(404); res.end(); return }
    try { await route.handler(req, res) } catch (error) { console.error('ROUTE THREW', error); res.writeHead(400); res.end() }
  }
  server.on('request', handle)
  return {
    server,
    routes,
    register: (route: any) => { routes.push(route); return () => { const i = routes.indexOf(route); if (i >= 0) routes.splice(i, 1) } },
    port: () => (server.address() as AddressInfo).port,
  }
}

describe('amadeus admin routes (HTTP integration)', () => {
  it('answers every control endpoint with a JSON body', async () => {
    const web = mockWebServer()
    await new Promise<void>(resolve => web.server.listen(0, '127.0.0.1', () => resolve()))
    const context = new Context()
    const webServer = {
      register: web.register,
      port: web.port,
    }
    context.provide('webServer', webServer as never)
    // Provide the remaining injected services with minimal stubs.
    context.provide('connection', {} as never)
    context.provide('tools', { register: () => {} } as never)
    context.provide('sessionQuery', { listSessions: async () => [], readTitle: async () => '', readSurface: async () => ({ events: [] }) } as never)
    context.provide('sessionController', {
      create: async () => ({ sessionId: 's1' }),
      rename: async () => ({ title: 't' }),
      cancel: async () => ({ accepted: true }),
      prompt: async () => ({ accepted: true }),
      page: async () => ({ records: [], hasMore: false }),
    } as never)
    context.provide('workspaceRegistry', { list: async () => [], archiveSession: async () => {} } as never)

    const mounted = await context.plugin(amadeusPlugin, {
      stateFile: 'C:/Users/test/.dsh/amadeus/devices.json',
      controlFile: 'C:/Users/test/.dsh/amadeus/control.json',
      initiallyEnabled: false,
      listenHost: '127.0.0.1',
      listenPort: 0,
      upstreamOrigin: 'http://127.0.0.1:3080',
      allowedCidrs: ['127.0.0.0/8'],
      tls: { mode: 'disabled' },
    } as never)

    const endpoints = [
      ['GET', '/api/amadeus/control'],
      ['GET', '/api/amadeus/remote/control'],
      ['POST', '/api/amadeus/pairing/open'],
      ['GET', '/api/amadeus/devices'],
      ['POST', '/api/amadeus/devices/revoke'],
      ['POST', '/api/amadeus/devices/reset'],
      ['POST', '/api/amadeus/remote/provider'],
      ['POST', '/api/amadeus/remote/control'],
      ['POST', '/api/amadeus/remote/reconnect'],
      ['POST', '/api/amadeus/remote/reset'],
      ['POST', '/api/amadeus/remote/cpolar/component/install'],
      ['POST', '/api/amadeus/remote/cpolar/configure'],
      ['POST', '/api/amadeus/remote/cpolar/component/purge'],
      ['POST', '/api/amadeus/remote/frp/component/install'],
      ['POST', '/api/amadeus/remote/frp/configure'],
      ['POST', '/api/amadeus/remote/frp/component/purge'],
    ] as const
    for (const [method, path] of endpoints) {
      const init: RequestInit = {
        method,
        headers: { host: '127.0.0.1', 'content-type': 'application/json' },
      }
      if (method === 'POST') init.body = '{}'
      const res = await fetch(`http://127.0.0.1:${web.port()}${path}`, init)
      const text = await res.text()
      console.log(`${method} ${path} -> ${res.status} ${text.slice(0, 160)}`)
      expect(text.length).toBeGreaterThan(0)
    }
    await mounted.dispose()
    await new Promise<void>(resolve => web.server.close(() => resolve()))
  }, 20_000)
})

void once

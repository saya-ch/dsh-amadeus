import { describe, expect, it } from 'vitest'
import { createAmadeusExtension } from '../src/amadeus-extension.js'

type StatusHandler = (request: { readonly method: string; readonly url: string }) => Promise<{ readonly status: number; readonly body: string }>

function makeHandler(business: Record<string, unknown>): StatusHandler {
  const extension = createAmadeusExtension(business as never)
  const route = extension.routes?.find(candidate => candidate.method === 'GET' && candidate.path === '/status')
  if (route === undefined) throw new Error('Missing GET /status route')
  return async request => {
    const response = await route.handle({
      method: request.method,
      pathname: new URL(request.url, 'http://fixture.local').pathname,
      query: new URLSearchParams(),
      headers: {},
      body: new Uint8Array(),
      signal: new AbortController().signal,
      deviceId: 'fixture-device',
    })
    if (typeof response.body !== 'string') throw new Error('Expected a JSON string response')
    return { status: response.status ?? 200, body: response.body }
  }
}

describe('amadeus status', () => {
  it('reports false capabilities when adapters absent', async () => {
    const handler = makeHandler({})
    const response = await handler({ method: 'GET', url: '/status' })
    expect(response.status).toBe(200)
    expect(JSON.parse(response.body) as unknown).toEqual({ capabilities: { sessions: false, reports: false, choices: false } })
  })
  it('reports true when adapters wired', async () => {
    const handler = makeHandler({
      sessions: { list: async () => [], create: async () => ({}), get: async () => null },
      reports: { list: async () => [], save: async () => {}, get: async () => null },
      choices: { create: async () => {}, resolve: async () => {}, get: async () => null },
    })
    const response = await handler({ method: 'GET', url: '/status' })
    expect(response.status).toBe(200)
    expect(JSON.parse(response.body) as unknown).toEqual({ capabilities: { sessions: true, reports: true, choices: true } })
  })
})
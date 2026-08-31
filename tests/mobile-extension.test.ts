import { Context } from '@deepseek-ai/cordis'
import { MobileAccessService } from 'dsh-mobile'
import type { MobileExtensionDefinition, MobileRouteRequest, MobileRouteResponse } from 'dsh-mobile'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAmadeusExtension } from '../src/amadeus-extension.js'
import type { AmadeusGatewayOptions, AmadeusReport, AmadeusSessionSummary } from '../src/amadeus-extension.js'
import * as amadeusPlugin from '../src/amadeus-plugin.js'

type SessionAdapter = NonNullable<AmadeusGatewayOptions['sessions']>
type ReportAdapter = NonNullable<AmadeusGatewayOptions['reports']>
type ChoiceAdapter = NonNullable<AmadeusGatewayOptions['choices']>

const contexts: Context[] = []
const session: AmadeusSessionSummary = {
  id: 'session_fixture',
  title: 'Saved session',
  mode: 'amadeus',
  updatedAt: 1234,
}
const report: AmadeusReport = {
  id: 'report_fixture',
  title: 'Saved report',
  markdown: '# Fixture',
  createdAt: 1234,
}

afterEach(async () => {
  await Promise.all(contexts.splice(0).map(context => context.fiber.dispose()))
  vi.restoreAllMocks()
})

function sessionAdapter() {
  return {
    list: vi.fn<SessionAdapter['list']>().mockResolvedValue([session]),
    create: vi.fn<SessionAdapter['create']>().mockResolvedValue(session),
    get: vi.fn<SessionAdapter['get']>().mockResolvedValue(session),
  } satisfies SessionAdapter
}

function reportAdapter() {
  return {
    list: vi.fn<ReportAdapter['list']>().mockResolvedValue([report]),
    save: vi.fn<ReportAdapter['save']>().mockResolvedValue(undefined),
    get: vi.fn<ReportAdapter['get']>().mockResolvedValue(report),
  } satisfies ReportAdapter
}

function choiceAdapter() {
  return {
    create: vi.fn<ChoiceAdapter['create']>().mockResolvedValue(undefined),
    resolve: vi.fn<ChoiceAdapter['resolve']>().mockResolvedValue(undefined),
    cancel: vi.fn<ChoiceAdapter['cancel']>().mockResolvedValue(undefined),
    get: vi.fn<ChoiceAdapter['get']>().mockResolvedValue({ question: 'Continue?', options: ['Yes', 'No'] }),
  } satisfies ChoiceAdapter
}

function encoded(value: unknown): Uint8Array {
  return new TextEncoder().encode(JSON.stringify(value))
}

function request(method: string, pathname: string, overrides: Partial<MobileRouteRequest> = {}): MobileRouteRequest {
  return {
    method,
    pathname,
    query: new URLSearchParams(),
    headers: {},
    body: new Uint8Array(),
    signal: new AbortController().signal,
    deviceId: 'fixture-device',
    ...overrides,
  }
}

async function call(
  extension: MobileExtensionDefinition,
  method: string,
  path: string,
  overrides: Partial<MobileRouteRequest> = {},
): Promise<MobileRouteResponse> {
  const route = extension.routes?.find(candidate => candidate.method === method && candidate.path === path)
  if (route === undefined) throw new Error(`Missing fixture route: ${method} ${path}`)
  return route.handle(request(method, path, overrides))
}

function decoded(response: MobileRouteResponse): unknown {
  expect(response.contentType).toBe('application/json; charset=utf-8')
  if (typeof response.body !== 'string') throw new Error('Expected a JSON string response')
  return JSON.parse(response.body) as unknown
}

function expectJson(response: MobileRouteResponse, status: number, value: unknown): void {
  expect(response.status).toBe(status)
  expect(decoded(response)).toEqual(value)
}

function registry(): MobileAccessService {
  const context = new Context()
  contexts.push(context)
  return new MobileAccessService(context)
}

describe('Amadeus mobile extension routes', () => {
  it('reports only the installed capabilities', async () => {
    const extension = createAmadeusExtension()
    expect(extension).toMatchObject({ schemaVersion: 1, id: 'amadeus', name: 'Amadeus: Whale', version: '0.1.0' })
    expectJson(await call(extension, 'GET', '/status'), 200, {
      capabilities: { sessions: false, reports: false, choices: false },
    })
    const configured = createAmadeusExtension({ sessions: sessionAdapter(), reports: reportAdapter(), choices: choiceAdapter() })
    expect(decoded(await call(configured, 'GET', '/status'))).toEqual({
      capabilities: { sessions: true, reports: true, choices: true },
    })
  })

  it('adds a display tag to untagged text without invoking a backend', async () => {
    const text = 'Hello there'
    const tag = { mood: 'idle', sprite: 'smile', voice: 'soft', sfx: 'none', bgm: 'none' }
    expectJson(await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body: encoded({ text }) }), 200, {
      segments: [{ clean: text, tag: null, raw: text }],
      ensured: `${text}\n[[AMW:${JSON.stringify(tag)}]]`,
      clean: text,
      tag,
    })
  })

  it('preserves an existing tag and exposes separately parsed dialogue segments', async () => {
    const firstTag = { mood: 'happy', sprite: 'wag', voice: 'soft', sfx: 'bell', bgm: 'none' }
    const secondTag = { mood: 'shy', sprite: 'shy', voice: 'whisper', sfx: 'wave', bgm: 'rain' }
    const first = `First line[[AMW:${JSON.stringify(firstTag)}]]`
    const second = `Second line[[AMW:${JSON.stringify(secondTag)}]]`
    const text = `${first}\n${second}`
    const response = await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body: encoded({ text }) })
    expect(response.status).toBe(200)
    expect(decoded(response)).toMatchObject({
      ensured: text,
      segments: [
        { clean: 'First line', tag: firstTag, raw: first },
        { clean: 'Second line', tag: secondTag, raw: second },
      ],
    })
  })

  it.each([
    { method: 'GET', path: '/sessions', body: {}, error: 'amadeus_sessions_unavailable' },
    { method: 'POST', path: '/sessions', body: {}, error: 'amadeus_sessions_unavailable' },
    { method: 'GET', path: '/reports', body: {}, error: 'amadeus_reports_unavailable' },
    { method: 'POST', path: '/reports', body: { id: 'report_fixture', markdown: '# Report' }, error: 'amadeus_reports_unavailable' },
    { method: 'POST', path: '/choice', body: { choiceId: 'choice_fixture', selected: 'Yes' }, error: 'amadeus_choices_unavailable' },
  ])('returns an explicit unavailable error for $method $path without an adapter', async ({ method, path, body, error }) => {
    expectJson(await call(createAmadeusExtension(), method, path, { body: encoded(body) }), 503, { error })
  })

  it('refuses another mode before listing or creating sessions', async () => {
    const sessions = sessionAdapter()
    const extension = createAmadeusExtension({ sessions })
    expectJson(await call(extension, 'GET', '/sessions', { query: new URLSearchParams({ mode: 'standard' }) }), 400, { error: 'bad_request' })
    expectJson(await call(extension, 'POST', '/sessions', { body: encoded({ mode: 'standard' }) }), 400, { error: 'bad_request' })
    expect(sessions.list).not.toHaveBeenCalled()
    expect(sessions.create).not.toHaveBeenCalled()
  })

  it('lists and creates only through the supplied session adapter', async () => {
    const sessions = sessionAdapter()
    const extension = createAmadeusExtension({ sessions })
    expectJson(await call(extension, 'GET', '/sessions'), 200, { sessions: [session] })
    expect(sessions.list).toHaveBeenCalledExactlyOnceWith('amadeus')
    expectJson(await call(extension, 'POST', '/sessions', { body: encoded({ mode: 'amadeus', title: 'My saved session' }) }), 201, { session })
    expect(sessions.create).toHaveBeenCalledExactlyOnceWith('amadeus', 'My saved session', undefined)
    expect(sessions.get).not.toHaveBeenCalled()
  })

  it('passes an omitted title as undefined without inventing a saved session', async () => {
    const sessions = sessionAdapter()
    expectJson(await call(createAmadeusExtension({ sessions }), 'POST', '/sessions', { body: encoded({}) }), 201, { session })
    expect(sessions.create).toHaveBeenCalledExactlyOnceWith('amadeus', undefined, undefined)
  })

  it.each([42, null, 'x'.repeat(201)])('rejects invalid session titles before creation: %j', async title => {
    const sessions = sessionAdapter()
    expectJson(await call(createAmadeusExtension({ sessions }), 'POST', '/sessions', { body: encoded({ title }) }), 400, { error: 'bad_request' })
    expect(sessions.create).not.toHaveBeenCalled()
  })

  it.each(['{', '[]', '[{}]', 'null', 'true', '42', '"text"'])('rejects non-object or malformed JSON: %s', async body => {
    expectJson(await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body: new TextEncoder().encode(body) }), 400, { error: 'bad_request' })
  })

  it('rejects invalid UTF-8 before attempting to parse a request', async () => {
    expectJson(await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body: Uint8Array.from([0xff]) }), 400, { error: 'bad_request' })
  })

  it.each([{}, { text: null }, { text: 3 }])('requires string tag input: %j', async body => {
    expectJson(await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body: encoded(body) }), 400, { error: 'bad_request' })
  })

  it('accepts a body of exactly 64 KiB and rejects one byte more', async () => {
    const text = 'a'.repeat(64 * 1024 - encoded({ text: '' }).byteLength)
    const body = encoded({ text })
    expect(body.byteLength).toBe(64 * 1024)
    expect((await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body })).status).toBe(200)
    expectJson(await call(createAmadeusExtension(), 'POST', '/tag/ensure', { body: encoded({ text: `${text}a` }) }), 413, { error: 'payload_too_large' })
  })

  it('enforces the byte limit for multibyte text before invoking report storage', async () => {
    const reports = reportAdapter()
    const body = encoded({ id: 'report_fixture', markdown: '鲸'.repeat(32 * 1024) })
    expect(body.byteLength).toBeGreaterThan(64 * 1024)
    expectJson(await call(createAmadeusExtension({ reports }), 'POST', '/reports', { body }), 413, { error: 'payload_too_large' })
    expect(reports.save).not.toHaveBeenCalled()
  })

  it('returns a stable 500 without leaking adapter messages or private details', async () => {
    const sessions = sessionAdapter()
    sessions.list.mockRejectedValue(new Error('fixture-secret-token at C:\\private\\credentials.json'))
    const response = await call(createAmadeusExtension({ sessions }), 'GET', '/sessions')
    expectJson(response, 500, { error: 'amadeus_internal_error' })
    expect(String(response.body)).not.toContain('fixture-secret-token')
    expect(String(response.body)).not.toContain('credentials')
  })

  it('sanitizes synchronous adapter failures as well as rejected promises', async () => {
    const reports = reportAdapter()
    reports.list.mockImplementation(() => { throw new Error('private report-store failure') })
    expectJson(await call(createAmadeusExtension({ reports }), 'GET', '/reports'), 500, { error: 'amadeus_internal_error' })
  })

  it('does not resolve a choice which is no longer pending', async () => {
    const choices = choiceAdapter()
    choices.get.mockResolvedValue(null)
    expectJson(await call(createAmadeusExtension({ choices }), 'POST', '/choice', { body: encoded({ choiceId: 'choice_fixture', selected: 'Yes' }) }), 404, { error: 'not_found' })
    expect(choices.get).toHaveBeenCalledExactlyOnceWith('choice_fixture')
    expect(choices.resolve).not.toHaveBeenCalled()
  })

  it('refuses a selection outside the pending options', async () => {
    const choices = choiceAdapter()
    expectJson(await call(createAmadeusExtension({ choices }), 'POST', '/choice', { body: encoded({ choiceId: 'choice_fixture', selected: 'Unlisted' }) }), 400, { error: 'bad_request' })
    expect(choices.resolve).not.toHaveBeenCalled()
  })

  it('resolves an existing choice with one of its pending options', async () => {
    const choices = choiceAdapter()
    expectJson(await call(createAmadeusExtension({ choices }), 'POST', '/choice', { body: encoded({ choiceId: 'choice_fixture', selected: 'Yes' }) }), 200, { ok: true })
    expect(choices.get).toHaveBeenCalledExactlyOnceWith('choice_fixture')
    expect(choices.resolve).toHaveBeenCalledExactlyOnceWith('choice_fixture', 'Yes')
    expect(choices.create).not.toHaveBeenCalled()
  })

  it.each([
    { choiceId: '../choice', selected: 'Yes' },
    { choiceId: '', selected: 'Yes' },
    { choiceId: 'choice_fixture', selected: 42 },
    { choiceId: 'choice_fixture', selected: 'x'.repeat(4097) },
  ])('rejects malformed choice input before reading pending state: %j', async body => {
    const choices = choiceAdapter()
    expectJson(await call(createAmadeusExtension({ choices }), 'POST', '/choice', { body: encoded(body) }), 400, { error: 'bad_request' })
    expect(choices.get).not.toHaveBeenCalled()
    expect(choices.resolve).not.toHaveBeenCalled()
  })

  it('rejects an already-cancelled request before invoking its adapter', async () => {
    const sessions = sessionAdapter()
    const controller = new AbortController()
    const reason = new Error('fixture request cancelled')
    controller.abort(reason)
    await expect(call(createAmadeusExtension({ sessions }), 'GET', '/sessions', { signal: controller.signal })).rejects.toBe(reason)
    expect(sessions.list).not.toHaveBeenCalled()
  })

  it('preserves cancellation instead of translating a late adapter failure into 500', async () => {
    const sessions = sessionAdapter()
    const pending = Promise.withResolvers<AmadeusSessionSummary[]>()
    sessions.list.mockReturnValue(pending.promise)
    const controller = new AbortController()
    const response = call(createAmadeusExtension({ sessions }), 'GET', '/sessions', { signal: controller.signal })
    expect(sessions.list).toHaveBeenCalledOnce()
    const reason = new Error('fixture request cancelled while waiting')
    controller.abort(reason)
    pending.reject(new Error('late private adapter failure'))
    await expect(response).rejects.toBe(reason)
  })

  it('does not return a successful adapter result after the request is cancelled', async () => {
    const sessions = sessionAdapter()
    const pending = Promise.withResolvers<AmadeusSessionSummary[]>()
    sessions.list.mockReturnValue(pending.promise)
    const controller = new AbortController()
    const response = call(createAmadeusExtension({ sessions }), 'GET', '/sessions', { signal: controller.signal })
    const reason = new Error('fixture request cancelled before completion')
    controller.abort(reason)
    pending.resolve([session])
    await expect(response).rejects.toBe(reason)
  })
})

describe('Amadeus in the real DSH Mobile registry', () => {
  it('dispatches report descendants through the registered prefix route', async () => {
    const service = registry()
    const reports = reportAdapter()
    service.registerExtension(createAmadeusExtension({ reports }))
    const response = await service.route('amadeus', 'GET', '/reports/report_fixture', request('GET', '/reports/report_fixture'))
    expectJson(response, 200, report)
    expect(reports.get).toHaveBeenCalledExactlyOnceWith('report_fixture')
  })

  it('reports an unavailable report adapter through the registered prefix route', async () => {
    const service = registry()
    service.registerExtension(createAmadeusExtension())
    expectJson(await service.route('amadeus', 'GET', '/reports/report_fixture', request('GET', '/reports/report_fixture')), 503, { error: 'amadeus_reports_unavailable' })
  })

  it('rejects a nested report id before invoking the report adapter', async () => {
    const service = registry()
    const reports = reportAdapter()
    service.registerExtension(createAmadeusExtension({ reports }))
    expectJson(await service.route('amadeus', 'GET', '/reports/nested/report', request('GET', '/reports/nested/report')), 400, { error: 'bad_request' })
    expect(reports.get).not.toHaveBeenCalled()
  })

  it('registers once, unloads only Amadeus, and mounts again on the existing service', async () => {
    const context = new Context()
    contexts.push(context)
    const service = new MobileAccessService(context)
    const ping = vi.fn(() => ({ alive: true }))
    service.registerExtension({
      schemaVersion: 1,
      id: 'other-extension',
      name: 'Other extension',
      version: '1.0.0',
      actions: { ping: { run: ping } },
    })
    const register = vi.spyOn(service, 'registerExtension')
    const stopLocal = vi.spyOn(service, 'stopLocal')
    expect(amadeusPlugin.inject).toEqual(['mobileAccess'])

    const mounted = await context.plugin(amadeusPlugin)
    expect(register).toHaveBeenCalledOnce()
    expect(service.manifest().map(entry => entry.id)).toEqual(['amadeus', 'other-extension'])
    expectJson(await context.mobileAccess.route('amadeus', 'GET', '/sessions', request('GET', '/sessions')), 503, { error: 'amadeus_sessions_unavailable' })

    await mounted.dispose()
    expect(service.extension('amadeus')).toBeUndefined()
    expect(context.mobileAccess.manifest().map(entry => entry.id)).toEqual(['other-extension'])
    expect(stopLocal).not.toHaveBeenCalled()
    await expect(context.mobileAccess.invoke('other-extension', 'ping', {}, { deviceId: 'fixture-device', signal: new AbortController().signal })).resolves.toEqual({ alive: true })
    expect(ping).toHaveBeenCalledOnce()

    const remounted = await context.plugin(amadeusPlugin)
    expect(register).toHaveBeenCalledTimes(2)
    expect(service.manifest().map(entry => entry.id)).toEqual(['amadeus', 'other-extension'])
    const response = await context.mobileAccess.route('amadeus', 'GET', '/status', request('GET', '/status'))
    expect(response.status).toBe(200)
    expect(decoded(response)).toEqual({ capabilities: { sessions: false, reports: false, choices: false } })
    await remounted.dispose()
    expect(service.manifest().map(entry => entry.id)).toEqual(['other-extension'])
    expect(stopLocal).not.toHaveBeenCalled()
  })
})

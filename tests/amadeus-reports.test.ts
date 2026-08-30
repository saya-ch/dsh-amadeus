import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { AmadeusReportsAdapter, AmadeusPreviewStore } from '../src/amadeus-reports.js'

let dir: string
beforeEach(async () => { dir = await mkdtemp(join(tmpdir(), 'amw-')) })
afterEach(async () => { await rm(dir, { recursive: true, force: true }) })

describe('reports adapter', () => {
  it('saves and gets a report', async () => {
    const r = new AmadeusReportsAdapter({} as any, dir)
    await r.save({ id: 'rpt_1', title: '报告', markdown: '# hi', createdAt: 1 })
    const got = await r.get('rpt_1')
    expect(got?.title).toBe('报告')
    expect(got?.markdown).toBe('# hi')
  })
  it('lists saved reports', async () => {
    const r = new AmadeusReportsAdapter({} as any, dir)
    await r.save({ id: 'rpt_1', title: 'A', markdown: 'x', createdAt: 1 })
    const list = await r.list()
    expect(list.some(x => x.id === 'rpt_1')).toBe(true)
  })
  it('throws on corrupt reports json instead of silently returning an empty list', async () => {
    const r = new AmadeusReportsAdapter({} as any, dir)
    await r.save({ id: 'rpt_1', title: 'A', markdown: 'x', createdAt: 1 })
    await writeFile(join(dir, 'reports.json'), '{corrupt')
    await expect(r.list()).rejects.toThrow()
  })
  it('writes reports atomically leaving no temp files behind', async () => {
    const r = new AmadeusReportsAdapter({} as any, dir)
    await r.save({ id: 'rpt_1', title: 'A', markdown: 'x', createdAt: 1 })
    await r.save({ id: 'rpt_2', title: 'B', markdown: 'y', createdAt: 2 })
    const leftovers = (await readdir(dir)).filter(name => name.includes('.tmp'))
    expect(leftovers).toEqual([])
  })
  it('previews persist separately', async () => {
    const p = new AmadeusPreviewStore(dir)
    await p.save({ id: 'pv_1', type: 'image', content: 'amadeus/bg-1.webp', title: '图', createdAt: 1 })
    const got = await p.get('pv_1')
    expect(got?.type).toBe('image')
  })
  it('previews throw on corrupt json instead of returning a fallback', async () => {
    const p = new AmadeusPreviewStore(dir)
    await p.save({ id: 'pv_1', type: 'image', content: 'x', title: '图', createdAt: 1 })
    await writeFile(join(dir, 'previews.json'), '{corrupt')
    await expect(p.get('pv_1')).rejects.toThrow()
  })
})
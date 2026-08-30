import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import type { AmadeusGatewayOptions } from './amadeus-extension.js'

/** Persisted report owned by the Amadeus business adapter (mirrors AmadeusReport). */
export interface AmadeusReportRecord {
  id: string
  title: string
  markdown: string
  createdAt: number
}

/** Persisted preview window payload (web/image/code/table). */
export interface AmadeusPreviewRecord {
  id: string
  type: string
  content: string
  title: string
  createdAt: number
}

async function readJson<T>(file: string, fallback: T): Promise<T> {
  try {
    return JSON.parse(await readFile(file, 'utf8')) as T
  } catch {
    return fallback
  }
}

async function writeJson(file: string, value: unknown): Promise<void> {
  await mkdir(dirname(file), { recursive: true })
  await writeFile(file, JSON.stringify(value, null, 2))
}

/** Reports adapter for the Amadeus gateway; JSON persistence under a state dir. */
export class AmadeusReportsAdapter implements NonNullable<AmadeusGatewayOptions['reports']> {
  constructor(private readonly _ctx: unknown, private readonly dir: string) {}

  private file(): string {
    return join(this.dir, 'reports.json')
  }

  async get(id: string): Promise<AmadeusReportRecord | null> {
    const all = await readJson<AmadeusReportRecord[]>(this.file(), [])
    return all.find(r => r.id === id) ?? null
  }

  async save(report: AmadeusReportRecord): Promise<void> {
    const all = await readJson<AmadeusReportRecord[]>(this.file(), [])
    await writeJson(this.file(), [...all.filter(r => r.id !== report.id), report])
  }

  async list(): Promise<AmadeusReportRecord[]> {
    return readJson<AmadeusReportRecord[]>(this.file(), [])
  }
}

/** Preview store for the Amadeus gateway; separate JSON persistence. */
export class AmadeusPreviewStore {
  constructor(private readonly dir: string) {}

  private file(): string {
    return join(this.dir, 'previews.json')
  }

  async get(id: string): Promise<AmadeusPreviewRecord | null> {
    const all = await readJson<AmadeusPreviewRecord[]>(this.file(), [])
    return all.find(p => p.id === id) ?? null
  }

  async save(p: AmadeusPreviewRecord): Promise<void> {
    const all = await readJson<AmadeusPreviewRecord[]>(this.file(), [])
    await writeJson(this.file(), [...all.filter(x => x.id !== p.id), p])
  }
}
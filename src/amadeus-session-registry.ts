import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

/**
 * Amadeus 会话注册表：把"哪些 dsh 会话是鲸鱼娘（Amadeus）会话"的判定结果
 * 落盘缓存，避免每次读档 list 都全量读 surface 判定（慢）。\n\n
 * 判定依据（与 AmadeusSessionsAdapter.list 一致）：\n
 *  - header.agentPreset === 'amadeus'（网关创建，快）\n
 *  - surface 含 [[AMW:{…}]] 演出标签 或 agent-preset/selected 切到 amadeus（早期/web 会话）\n\n
 * 同步策略：\n
 *  - 网关自己 create/archive 的会话 → 直接登记/移除（精确）\n
 *  - dsh 侧新建/切换的会话 → list 时增量对账（只判未见过的 id，结果缓存）\n
 *  - 归档由 workspaceRegistry.archivedSessionIds 实时过滤（不依赖本表）\n
 * 本表只回答"是不是鲸鱼娘"，不存 title/updatedAt（那两样读档时要新鲜值，实时读 dsh）。
 */

export interface AmadeusSessionRegistryEntry {
  /** 判定时间（ms）。 */
  checkedAt: number
}

export interface AmadeusSessionRegistryFile {
  version: 1
  /** id → 判定为 Amadeus 会话（值仅占位，存在即命中）。 */
  sessions: Record<string, AmadeusSessionRegistryEntry>
  /** 判定过但不是 Amadeus 的 id（避免每次 list 重复读 surface）。 */
  negatives: Record<string, AmadeusSessionRegistryEntry>
}

const empty = (): AmadeusSessionRegistryFile => ({ version: 1, sessions: {}, negatives: {} })

/** 注册表的最小结构面（adapter/commands 只依赖这三个方法；真类与测试 fake 都可传入）。 */
export interface AmadeusSessionRegistryLike {
  isChecked(sessionId: string): Promise<'amadeus' | 'not-amadeus' | 'unknown'>
  record(entries: Array<{ id: string; amadeus: boolean }>): Promise<void>
  unmark(sessionIds: string[]): Promise<void>
}

/** 会话表存储：~/.dsh/amadeus/session-registry.json（读写都原子落盘）。 */
export class AmadeusSessionRegistry implements AmadeusSessionRegistryLike {
  constructor(private readonly file: string) {}

  private async load(): Promise<AmadeusSessionRegistryFile> {
    try {
      const raw = await readFile(this.file, 'utf8')
      const parsed = JSON.parse(raw) as AmadeusSessionRegistryFile
      if (parsed?.version === 1 && parsed.sessions !== undefined) return parsed
      return empty()
    } catch {
      return empty() // 首次/损坏 → 空表
    }
  }

  /** 会话是否已判定过（无论是否 Amadeus）——命中则不必再读 surface。
   *  negative 判定超过一天即视为过期（dsh 会话可能后来切成 amadeus 人设），返回 unknown 让 list 重查。 */
  async isChecked(sessionId: string): Promise<'amadeus' | 'not-amadeus' | 'unknown'> {
    const file = await this.load()
    if (file.sessions[sessionId] !== undefined) return 'amadeus'
    const negative = file.negatives[sessionId]
    if (negative !== undefined) {
      return Date.now() - negative.checkedAt < 86_400_000 ? 'not-amadeus' : 'unknown'
    }
    return 'unknown'
  }

  /** 会话被移除（删除/不再可见）时清理登记；不存在则跳过。 */
  async unmark(sessionIds: string[]): Promise<void> {
    if (sessionIds.length === 0) return
    const file = await this.load()
    let changed = false
    for (const id of sessionIds) {
      if (file.sessions[id] !== undefined) {
        delete file.sessions[id]
        changed = true
      }
      if (file.negatives[id] !== undefined) {
        delete file.negatives[id]
        changed = true
      }
    }
    if (changed) await this.save(file)
  }

  /** 批量登记判定结果：amadeus 进 sessions，非 amadeus 进 negatives。 */
  async record(entries: Array<{ id: string; amadeus: boolean }>): Promise<void> {
    const file = await this.load()
    const now = Date.now()
    let changed = false
    for (const entry of entries) {
      const target = entry.amadeus ? file.sessions : file.negatives
      const other = entry.amadeus ? file.negatives : file.sessions
      if (target[entry.id] === undefined) { target[entry.id] = { checkedAt: now }; changed = true }
      if (other[entry.id] !== undefined) { delete other[entry.id]; changed = true }
    }
    if (changed) await this.save(file)
  }

  private async save(file: AmadeusSessionRegistryFile): Promise<void> {
    await mkdir(dirname(this.file), { recursive: true })
    await writeFile(this.file, JSON.stringify(file, null, 2), 'utf8')
  }
}

/** 默认注册表路径（~/.dsh/amadeus/session-registry.json）。 */
export function amadeusSessionRegistryFile(stateDir: string): string {
  return join(stateDir, 'session-registry.json')
}

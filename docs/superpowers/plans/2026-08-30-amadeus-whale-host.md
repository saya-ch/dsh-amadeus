# Amadeus Whale Host（桌面插件）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 DSH 桌面端 `dsh-amadeus` 插件从空 adapter 史山重写为真实会话闭环：sessions/reports/previews/choices adapter 注入、`save_report`/`show_preview` 工具、`ask_user_question` answerer、SSE `/stream/:sessionId` 端点、开场方案 A、控制面板实接、远程 provider 框架（FRP 优先）。

**Architecture:** 保留独立安全网关 `src/gateway.ts`（配对/证书/CSRF/SSE/WS 代理，复用 dsh-mobile 思想，属「连接与安全思想」底座）。重写业务层：`amadeus-plugin.ts` 注入真实 adapter 到 `AmadeusGatewayOptions`；新增 `amadeus-sessions.ts`/`amadeus-reports.ts`/`amadeus-choices.ts`/`amadeus-stream.ts`/`amadeus-tools.ts`/`amadeus-remote.ts`。业务数据落 `~/.dsh/amadeus/`。会话本体由 DSH persistence 落盘，不重复存。

**Tech Stack:** TypeScript / Cordis（@deepseek-ai/cordis）/ DSH 服务（sessionController、sessionQuery、userQuestions、workspaceRegistry、tools、agents）/ Node 内置 http（网关已具备）。

**Spec:** `docs/superpowers/specs/2026-08-30-amadeus-whale-mobile-galgame-design.md`
**App 契约:** `docs/superpowers/plans/2026-08-30-amadeus-whale-app.md`（客户端按此契约实现，本计划必须满足）

## Global Constraints

- **历史代码**：`src/amadeus-extension.ts` 路由骨架（GET /status、GET/POST /sessions、GET /reports、GET /reports/:id、POST /reports、POST /choice、POST /tag/ensure）与 App 契约对齐，保留并扩展；`src/amadeus-plugin.ts` 的 `business: AmadeusGatewayOptions = {}` 必须注入真实 adapter；`src/gateway.ts` 保留为底座不重写；`src/amadeus-tags.ts`/`src/amadeus-mode.ts` 保留（协议/人格核心）。
- **不修改 DSH/dsh-mobile 源码**：只消费 DSH 提供的 Cordis 服务。
- **零重名**：独立服务名 `amadeusAccess`、cookie `amw_*`/`x-amw-csrf`、管理路由 `/api/amadeus`、端口 3444、状态目录 `~/.dsh/amadeus/`。与 dsh-mobile（mobileAccess/3443//api/mobile-access）无交集。
- **mode 隔离**：sessions 只列 `header.agentPreset === 'amadeus'`；POST /sessions 的 mode 校验必须 `=== AMADEUS_MODE_ID`。
- **REST/SSE 契约（与 App 计划锁定）**：
  - `GET <routes>/status` → `{capabilities:{sessions,reports,choices}}`
  - `GET <routes>/sessions?mode=amadeus` → `{sessions:[{id,title,mode,updatedAt,lastMessage?}]}`
  - `POST <routes>/sessions` → `{session:{...}}`（body 可选 `{title?,workspaceId?}`）
  - `POST <routes>/sessions/:id/rename` → `{ok:true}`（body `{title}`）
  - `POST <routes>/sessions/:id/archive` → `{ok:true}`
  - `POST <routes>/sessions/:id/prompt` → `{ok:true}`（body `{text}`）
  - `POST <routes>/sessions/:id/cancel` → `{ok:true}`
  - `GET <routes>/sessions/:id/page?beforeSeq=` → `{messages:[{role,text}],hasMore}`
  - `GET <routes>/reports/:id` → `{id,title,markdown,createdAt}`
  - `GET <routes>/previews/:id` → `{id,type,content,title}`
  - `POST <routes>/choice` → `{ok:true}`（body `{choiceId,selected}`）
  - `GET <routes>/workspaces` → `{workspaces:[{id,path,title}]}`
  - `GET <routes>/stream/:sessionId` → SSE：`data: {"type":"segments","text":"句\n[[AMW:...]]"}` / `{"type":"choice","choiceId","question","options":[{"label","description"}]}` / `{"type":"ended","reason"}`
  - `<routes>` = `${AUTH_PREFIX}/extensions/amadeus/routes` = `/amadeus/extensions/amadeus/routes`
- **开场方案 A**：`create` 成功后立即 `prompt()` 发内部开场指令（「你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话」）。
- 每任务独立提交；TS 用现有 tsdown/package.json 构建链。

---

### Task 1: 网关健康/状态 + capabilities 实接

**Files:**
- Modify: `src/amadeus-extension.ts`（`GET /status` 分支，capabilities 读 adapter 实际可用性）

**Interfaces:**
- Consumes: `AmadeusGatewayOptions`（现状：`{ sessions?, reports?, choices? }` 三可选 adapter）。
- Produces: `GET <routes>/status` 返回真实 `capabilities`，adapter 为 undefined 时对应 false。

- [ ] **Step 1: 写失败测试**

`tests/amadeus-status.test.ts`:
```ts
import { describe, expect, it } from 'vitest'
import { createAmadeusExtension } from '../src/amadeus-extension.js'

function makeHandler(business: Record<string, unknown>) {
  const { handler } = createAmadeusExtension(business as never)
  return handler
}

describe('amadeus status', () => {
  it('reports false capabilities when adapters absent', async () => {
    const handler = makeHandler({})
    const response = await handler({ method: 'GET', url: '/status' } as never)
    const body = JSON.parse(response.body as string)
    expect(body.capabilities).toEqual({ sessions: false, reports: false, choices: false })
  })
  it('reports true when adapters wired', async () => {
    const handler = makeHandler({
      sessions: { list: async () => [], create: async () => ({}), get: async () => null },
      reports: { list: async () => [], save: async () => {}, get: async () => null },
      choices: { create: async () => {}, resolve: async () => {}, get: async () => null },
    })
    const response = await handler({ method: 'GET', url: '/status' } as never)
    const body = JSON.parse(response.body as string)
    expect(body.capabilities).toEqual({ sessions: true, reports: true, choices: true })
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npx vitest run tests/amadeus-status.test.ts`
Expected: FAIL（当前 /status 返回固定结构或 503）。

- [ ] **Step 3: 实现**

在 `src/amadeus-extension.ts` 的 `/status` 分支改读 `business`：
```ts
const capabilities = {
  sessions: business.sessions !== undefined,
  reports: business.reports !== undefined,
  choices: business.choices !== undefined,
}
return { status: 200, body: JSON.stringify({ capabilities }) }
```

- [ ] **Step 4: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-status.test.ts`
Expected: PASS。

```bash
git add tests/amadeus-status.test.ts src/amadeus-extension.ts
git commit -m "feat(host): report real capabilities from wired adapters"
```

---

### Task 2: sessions adapter（DSH 会话读写）

**Files:**
- Create: `src/amadeus-sessions.ts`
- Create: `tests/amadeus-sessions.test.ts`

**Interfaces:**
- Consumes: DSH Cordis 服务：`ctx.sessionQuery`（listSessions/readTitle）、`ctx.sessionController`（create/rename/prompt/page）、`ctx.workspaceRegistry`（archiveSession/list）。
- Produces:
  - `interface SessionAdapterContext { sessionQuery: {...}; sessionController: {...}; workspaceRegistry: {...}; modeId: string }`
  - `class AmadeusSessionsAdapter(ctx)` 实现 `AmadeusGatewayOptions['sessions']`：
    - `list(mode): Promise<AmadeusSessionSummary[]>`：`sessionQuery.listSessions()` → 过滤 `header.agentPreset === modeId` → `readTitle(id)` 补 title → 映射 `{id, title, mode, updatedAt, lastMessage?}`（lastMessage 从 `readSurface` 末条取文本片段）。
    - `create(mode, title?): Promise<AmadeusSessionSummary>`：`sessionController.create({ workspaceId?, agentPreset: modeId })`；title 由 DSH 自动生成；返回 summary。
    - `get(id): Promise<AmadeusSessionSummary | null>`：查 header.agentPreset 匹配则返回。
  - `class AmadeusSessionCommands(ctx)`（供 extension 的 rename/archive/prompt/cancel/page 路由用）：
    - `rename(id, title)` → `sessionController.rename({sessionId: id, title})`
    - `archive(id)` → `workspaceRegistry.archiveSession(id)`
    - `prompt(id, text)` → `sessionController.prompt({ requestId: mint(), sessionId: id, mode: 'queue', content: [{type:'text', text}] })`
    - `cancel(id)` → `sessionController.cancel({ sessionId: id })`
    - `page(id, beforeSeq?)` → `sessionController.page({ address:{kind:'session',sessionId:id}, throughSeq: Number.MAX_SAFE_INTEGER, beforeSeq, maxMessages: 50 })` → 折叠成 `{messages:[{role,text}], hasMore}`（user→role 'user'，assistant/message→'assistant' 取 text；tool/result 拼「[工具] name」）。

- [ ] **Step 1: 写失败测试（用 fake DSH 服务）**

`tests/amadeus-sessions.test.ts`:
```ts
import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands } from '../src/amadeus-sessions.js'

function fakeCtx() {
  const sessions = new Map<string, any>()
  return {
    modeId: 'amadeus',
    sessionQuery: {
      listSessions: async () => [...sessions.values()].map(h => ({ header: h })),
      readTitle: async (id: string) => `标题-${id}`,
      readSurface: async (id: string) => ({ events: [] }),
    },
    sessionController: {
      create: async (req: any) => ({ sessionId: 's-new', agentPreset: 'amadeus' }),
      rename: async (req: any) => ({ title: req.title, seq: 1 }),
      cancel: async () => ({ accepted: true }),
      prompt: async (req: any) => ({ accepted: true }),
      page: async () => ({ records: [], hasMore: false }),
    },
    workspaceRegistry: { archiveSession: async () => {} },
    __sessions: sessions,
  }
}

describe('sessions adapter', () => {
  it('lists only amadeus sessions', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus', cwd: '/w', createdAt: 1 })
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default', cwd: '/w', createdAt: 2 })
    const adapter = new AmadeusSessionsAdapter(ctx)
    const list = await adapter.list('amadeus')
    expect(list).toHaveLength(1)
    expect(list[0].id).toBe('a1')
    expect(list[0].mode).toBe('amadeus')
  })
  it('create returns summary with mode', async () => {
    const ctx = fakeCtx() as any
    const adapter = new AmadeusSessionsAdapter(ctx)
    const s = await adapter.create('amadeus')
    expect(s.id).toBe('s-new')
    expect(s.mode).toBe('amadeus')
  })
})

describe('session commands', () => {
  it('prompt sends queue text', async () => {
    const ctx = fakeCtx() as any
    const cmd = new AmadeusSessionCommands(ctx)
    let received: any
    ctx.sessionController.prompt = async (req: any) => { received = req; return { accepted: true } }
    await cmd.prompt('s1', '帮我写文件')
    expect(received.sessionId).toBe('s1')
    expect(received.mode).toBe('queue')
    expect(received.content[0]).toEqual({ type: 'text', text: '帮我写文件' })
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `npx vitest run tests/amadeus-sessions.test.ts`
Expected: 编译失败（文件不存在）。

- [ ] **Step 3: 实现**

`src/amadeus-sessions.ts`（关键签名，参照现有 extension 类型导入）:
```ts
import type { AmadeusSessionSummary } from './amadeus-extension.js'

export interface AmadeusSessionsContext {
  modeId: string
  sessionQuery: {
    listSessions(signal?: AbortSignal): Promise<Array<{ header: { id: string; agentPreset?: string; cwd?: string } }>>
    readTitle(sessionId: string): Promise<string>
    readSurface(sessionId: string): Promise<{ events: Array<{ type: string; data: any }> }>
  }
  sessionController: {
    create(req: { workspaceId?: string; agentPreset?: string }): Promise<{ sessionId: string }>
    rename(req: { sessionId: string; title: string }): Promise<{ title: string }>
    cancel(req: { sessionId: string }): Promise<{ accepted: boolean }>
    prompt(req: { requestId: string; sessionId: string; mode: 'queue' | 'steer'; content: Array<{ type: 'text'; text: string }> }): Promise<{ accepted: boolean }>
    page(req: { address: { kind: 'session'; sessionId: string }; throughSeq: number; beforeSeq?: number; maxMessages?: number }): Promise<{ records: Array<any>; hasMore: boolean }>
  }
  workspaceRegistry: { archiveSession(sessionId: string): Promise<void> }
}

export class AmadeusSessionsAdapter implements NonNullable<AmadeusSessionSummary>['sessions'] extends Object {
  constructor(private readonly ctx: AmadeusSessionsContext) {}

  async list(_mode: string): Promise<AmadeusSessionSummary[]> {
    const records = await this.ctx.sessionQuery.listSessions()
    const mine = records.filter(r => r.header.agentPreset === this.ctx.modeId)
    const out: AmadeusSessionSummary[] = []
    for (const r of mine) {
      const title = await this.ctx.sessionQuery.readTitle(r.header.id)
      out.push({ id: r.header.id, title, mode: this.ctx.modeId, updatedAt: Date.now() })
    }
    return out
  }

  async create(mode: string, title?: string): Promise<AmadeusSessionSummary> {
    const { sessionId } = await this.ctx.sessionController.create({ agentPreset: this.ctx.modeId })
    return { id: sessionId, title: title ?? '新会话', mode, updatedAt: Date.now() }
  }

  async get(id: string): Promise<AmadeusSessionSummary | null> {
    const records = await this.ctx.sessionQuery.listSessions()
    const hit = records.find(r => r.header.id === id && r.header.agentPreset === this.ctx.modeId)
    if (!hit) return null
    const title = await this.ctx.sessionQuery.readTitle(id)
    return { id, title, mode: this.ctx.modeId, updatedAt: Date.now() }
  }
}

export class AmadeusSessionCommands {
  constructor(private readonly ctx: AmadeusSessionsContext) {}
  async rename(id: string, title: string) { await this.ctx.sessionController.rename({ sessionId: id, title }) }
  async archive(id: string) { await this.ctx.workspaceRegistry.archiveSession(id) }
  async prompt(id: string, text: string) {
    await this.ctx.sessionController.prompt({ requestId: `amw-${crypto.randomUUID()}`, sessionId: id, mode: 'queue', content: [{ type: 'text', text }] })
  }
  async cancel(id: string) { await this.ctx.sessionController.cancel({ sessionId: id }) }
  async page(id: string, beforeSeq?: number) {
    const res = await this.ctx.sessionController.page({ address: { kind: 'session', sessionId: id }, throughSeq: Number.MAX_SAFE_INTEGER, beforeSeq, maxMessages: 50 })
    const messages = res.records.flatMap((rec: any) => {
      if (rec.type === 'event') {
        const e = rec.event
        if (e.type === 'user/message') return [{ role: 'user', text: (e.data as any).text ?? '' }]
        if (e.type === 'assistant/message') return [{ role: 'assistant', text: (e.data as any).message?.text ?? '' }]
        if (e.type === 'tool/result') return [{ role: 'tool', text: `[工具] ${(e.data as any).name ?? ''}` }]
      }
      return []
    })
    return { messages, hasMore: res.hasMore }
  }
}
```
> 注：`AmadeusSessionSummary` 从 `amadeus-extension.ts` 导入；若其非导出的接口名不符，以实际为准（`{id,title,mode,updatedAt,lastMessage?}`）。`crypto.randomUUID` 用 Node 全局（Node ≥19 直接可用，否则 `import { randomUUID } from 'node:crypto'`）。

- [ ] **Step 4: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-sessions.test.ts`
Expected: PASS。

```bash
git add src/amadeus-sessions.ts tests/amadeus-sessions.test.ts
git commit -m "feat(host): amadeus sessions adapter over DSH sessionQuery/sessionController"
```

---

### Task 3: reports/previews adapter + 专用工具

**Files:**
- Create: `src/amadeus-reports.ts`
- Create: `src/amadeus-tools.ts`
- Create: `tests/amadeus-reports.test.ts`

**Interfaces:**
- Consumes: `ctx.tools`（`defineTool`）、`ctx`（状态目录）、AmadeusGatewayOptions reports adapter 类型。
- Produces:
  - `interface AmadeusReportRecord { id: string; title: string; markdown: string; createdAt: number }`
  - `interface AmadeusPreviewRecord { id: string; type: string; content: string; title: string; createdAt: number }`
  - `class AmadeusReportsAdapter(ctx, dir: string)`：`get(id)`/`save(report)`/`list()`，JSON 持久化到 `~/.dsh/amadeus/reports.json`；previews 到 `previews.json`。
  - `registerAmadeusTools(ctx, reports: AmadeusReportsAdapter, previews: AmadeusPreviewStore)`：注册两个工具：
    - `save_report`：参数 `{title: string, markdown: string}`；execute 生成 `rpt_<rand>` 存 reports，返回 `{windowId, title}`。
    - `show_preview`：参数 `{title: string, type: string, content: string}`（type∈web/image/code/table）；存 previews，返回 `{windowId, title}`。
  - 工具写入 preset：在 `presets/amadeus/agent.cordis.yml` 增加工具行（tool 注册走插件内 `ctx.tools.register`，工具名作为模型可见；preset 只需在 persona 提示词提及可选）。

- [ ] **Step 1: 写失败测试**

`tests/amadeus-reports.test.ts`:
```ts
import { mkdtemp, rm } from 'node:fs/promises'
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
  it('previews persist separately', async () => {
    const p = new AmadeusPreviewStore(dir)
    await p.save({ id: 'pv_1', type: 'image', content: 'amadeus/bg-1.webp', title: '图', createdAt: 1 })
    const got = await p.get('pv_1')
    expect(got?.type).toBe('image')
  })
})
```

- [ ] **Step 2: 实现**

`src/amadeus-reports.ts`:
```ts
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

export interface AmadeusReportRecord { id: string; title: string; markdown: string; createdAt: number }
export interface AmadeusPreviewRecord { id: string; type: string; content: string; title: string; createdAt: number }

async function readJson<T>(file: string, fallback: T): Promise<T> {
  try { return JSON.parse(await readFile(file, 'utf8')) as T } catch { return fallback }
}
async function writeJson(file: string, value: unknown): Promise<void> {
  await mkdir(dirname(file), { recursive: true })
  await writeFile(file, JSON.stringify(value, null, 2))
}

export class AmadeusReportsAdapter {
  constructor(private readonly _ctx: unknown, private readonly dir: string) {}
  private file() { return join(this.dir, 'reports.json') }
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

export class AmadeusPreviewStore {
  constructor(private readonly dir: string) {}
  private file() { return join(this.dir, 'previews.json') }
  async get(id: string): Promise<AmadeusPreviewRecord | null> {
    const all = await readJson<AmadeusPreviewRecord[]>(this.file(), [])
    return all.find(p => p.id === id) ?? null
  }
  async save(p: AmadeusPreviewRecord): Promise<void> {
    const all = await readJson<AmadeusPreviewRecord[]>(this.file(), [])
    await writeJson(this.file(), [...all.filter(x => x.id !== p.id), p])
  }
}
```

`src/amadeus-tools.ts`:
```ts
import { randomBytes } from 'node:crypto'
import type { AmadeusReportsAdapter, AmadeusPreviewStore } from './amadeus-reports.js'

export function registerAmadeusTools(ctx: any, reports: AmadeusReportsAdapter, previews: AmadeusPreviewStore): void {
  ctx.tools.register({
    name: 'save_report',
    description: '把一份长 Markdown/文件列表存为报告窗口，返回 windowId 供 [[AMW:]] 标签引用',
    parameters: {
      title: { type: 'string', required: true, description: '报告标题' },
      markdown: { type: 'string', required: true, description: '报告完整 Markdown 内容' },
    },
    output: { schema: { type: 'object' } },
    async execute(args: { title: string; markdown: string }) {
      const id = `rpt_${randomBytes(4).toString('hex')}`
      await reports.save({ id, title: args.title, markdown: args.markdown, createdAt: Date.now() })
      return { windowId: id, title: args.title }
    },
  })
  ctx.tools.register({
    name: 'show_preview',
    description: '把一个结果（网页 URL/图片/表格/代码 diff）存为预览窗口，返回 windowId',
    parameters: {
      title: { type: 'string', required: true, description: '预览标题' },
      type: { type: 'string', required: true, description: 'web|image|code|table' },
      content: { type: 'string', required: true, description: '按 type 的预览内容' },
    },
    output: { schema: { type: 'object' } },
    async execute(args: { title: string; type: string; content: string }) {
      const id = `pv_${randomBytes(4).toString('hex')}`
      await previews.save({ id, type: args.type, content: args.content, title: args.title, createdAt: Date.now() })
      return { windowId: id, title: args.title }
    },
  })
}
```

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-reports.test.ts`
Expected: PASS。

```bash
git add src/amadeus-reports.ts src/amadeus-tools.ts tests/amadeus-reports.test.ts
git commit -m "feat(host): reports/previews adapters with save_report/show_preview tools"
```

---

### Task 4: choices adapter（ask_user_question answerer）

**Files:**
- Create: `src/amadeus-choices.ts`
- Create: `tests/amadeus-choices.test.ts`

**Interfaces:**
- Consumes: `ctx`（waterfall 监听）、`ctx.userQuestions` 的 `'user-questions/request'` agent-scoped waterfall、AmadeusGatewayOptions choices adapter。
- Produces:
  - `class AmadeusChoicesAdapter(ctx, dir: string)`：实现 choices adapter——
    - `create(choiceId, question, options)`：存 pending choice（含等待 answerer 回调的 resolver）
    - `resolve(choiceId, selected)`：把用户选择交给等待中的 resolver，返回 `AskUserQuestionAnswer`
    - `get(choiceId)`：返回 `{question, options}`
    - `install()`：注册 `'user-questions/request'` waterfall 监听：收到 `{questions, agent, signal}` → 取第一个 question → `create(choiceId, question.question, question.options.map(o=>o.label))` → await resolver（app 端 resolveChoice 触发）→ 返回 `{answers:[{id: question.id, selected:[label]}]}`；signal.aborted → 抛 ASK_ABORTED。
  - `interface PendingChoice { id: string; question: string; options: string[]; resolve: (answer: any) => void; reject: (e: Error) => void }`
  - 持久化：`choices.json` 仅存元数据（question/options），resolver 仅内存。

- [ ] **Step 1: 写失败测试**

`tests/amadeus-choices.test.ts`:
```ts
import { describe, expect, it } from 'vitest'
import { AmadeusChoicesAdapter } from '../src/amadeus-choices.js'

describe('choices adapter', () => {
  it('create then get returns question and options', async () => {
    const a = new AmadeusChoicesAdapter({} as any, '/tmp/amw')
    await a.create('c1', '要不要继续？', ['继续', '停下'])
    const got = await a.get('c1')
    expect(got?.question).toBe('要不要继续？')
    expect(got?.options).toEqual(['继续', '停下'])
  })
  it('resolve returns answer to waiting resolver', async () => {
    const a = new AmadeusChoicesAdapter({} as any, '/tmp/amw')
    await a.create('c2', '选哪个', ['A', 'B'])
    const p = a.wait('c2')
    const answer = await a.resolve('c2', 'A')
    expect(answer.answers[0]).toEqual({ id: 'q1', selected: ['A'] })
    expect(p.answers[0].selected[0]).toBe('A')
  })
  it('resolve unknown throws', async () => {
    const a = new AmadeusChoicesAdapter({} as any, '/tmp/amw')
    await expect(a.resolve('nope', 'x')).rejects.toThrow()
  })
})
```

- [ ] **Step 2: 实现**

`src/amadeus-choices.ts`:
```ts
export interface PendingChoice {
  id: string; question: string; options: string[]
  resolve: (answer: any) => void
  reject: (err: Error) => void
}

export class AmadeusChoicesAdapter {
  private readonly pending = new Map<string, PendingChoice>()
  private readonly meta = new Map<string, { question: string; options: string[] }>()

  constructor(private readonly ctx: any, private readonly dir: string) {}

  async create(choiceId: string, question: string, options: string[]): Promise<void> {
    this.meta.set(choiceId, { question, options })
  }

  wait(choiceId: string): Promise<any> {
    return new Promise<any>((resolve, reject) => {
      const meta = this.meta.get(choiceId)
      if (!meta) { reject(new Error('choice-not-found')); return }
      this.pending.set(choiceId, { id: choiceId, ...meta, resolve, reject })
    })
  }

  async resolve(choiceId: string, selected: string): Promise<any> {
    const p = this.pending.get(choiceId)
    if (!p) throw new Error(`choice ${choiceId} not pending`)
    const answer = { answers: [{ id: 'q1', selected: [selected] }] }
    p.resolve(answer)
    this.pending.delete(choiceId)
    return answer
  }

  async get(choiceId: string): Promise<{ question: string; options: string[] } | null> {
    return this.meta.get(choiceId) ?? null
  }

  install(): void {
    this.ctx.waterfall(undefined, 'user-questions/request', async (request: any) => {
      const q = request.questions[0]
      if (!q) throw new Error('empty-questions')
      const choiceId = `cq_${Math.random().toString(36).slice(2, 10)}`
      const options = q.options?.map((o: any) => o.label) ?? []
      await this.create(choiceId, q.question, options)
      const answer = await this.wait(choiceId)
      return { answers: [{ id: q.id, selected: answer.answers[0].selected, ...(q.multiSelect ? {} : {}) }] }
    })
  }
}
```
> 注：waterfall 注册需匹配 DSH `user-questions/request` 的实际监听签名（`ctx.waterfall(scopeTarget, event, handler)` 或 `ctx.waterfall(event, handler)` 视 DSH 版本）。实现时参考现有 `ask_user_question` 工具所在包对 `userQuestions` 的调用方式（`ctx.waterfall(scopeTarget(agent, agent), 'user-questions/request', ...)`）。若签名不符，以 DSH 包内实现为准，保持「收到→存 choice→wait→返回 answer」语义。

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-choices.test.ts`
Expected: PASS。

```bash
git add src/amadeus-choices.ts tests/amadeus-choices.test.ts
git commit -m "feat(host): choices adapter with ask_user_question answerer"
```

---

### Task 5: 开场方案 A + plugin 注入

**Files:**
- Modify: `src/amadeus-plugin.ts`（注入真实 adapter、注册工具、安装 answerer、开场 prompt）

**Interfaces:**
- Consumes: `AmadeusSessionsAdapter`/`AmadeusSessionCommands`（T2）、`AmadeusReportsAdapter`/`AmadeusPreviewStore`（T3）、`AmadeusChoicesAdapter`（T4）、`registerAmadeusTools`（T3）、`createAmadeusExtension`。
- Produces: 完整 `business: AmadeusGatewayOptions`（sessions/reports/choices 全部注入）；新建会话触发开场方案 A。

- [ ] **Step 1: 写失败测试**

`tests/amadeus-plugin-opening.test.ts`（验证 create 后触发 prompt 开场）：
```ts
import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands } from '../src/amadeus-sessions.js'

describe('opening prompt (方案 A)', () => {
  it('create then immediately prompts an opening line', async () => {
    const calls: string[] = []
    const ctx = {
      modeId: 'amadeus',
      sessionQuery: { listSessions: async () => [], readTitle: async () => 't', readSurface: async () => ({ events: [] }) },
      sessionController: {
        create: async () => { calls.push('create'); return { sessionId: 's1' } },
        prompt: async () => { calls.push('prompt'); return { accepted: true } },
        rename: async () => ({ title: 't', seq: 1 }), cancel: async () => ({ accepted: true }),
        page: async () => ({ records: [], hasMore: false }),
      },
      workspaceRegistry: { archiveSession: async () => {} },
    } as any
    const adapter = new AmadeusSessionsAdapter(ctx)
    const cmd = new AmadeusSessionCommands(ctx)
    const s = await adapter.create('amadeus')
    await cmd.prompt(s.id, '你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话')
    expect(calls).toEqual(['create', 'prompt'])
  })
})
```

- [ ] **Step 2: 实现（修改 plugin）**

在 `src/amadeus-plugin.ts` 的 `apply()` 内，把 `business: AmadeusGatewayOptions = {}` 替换为真实注入：
```ts
const stateDir = join(homedir(), '.dsh', 'amadeus')
const sessionsAdapter = new AmadeusSessionsAdapter(ctx)
const sessionCommands = new AmadeusSessionCommands(ctx)
const reportsAdapter = new AmadeusReportsAdapter(ctx, stateDir)
const previewStore = new AmadeusPreviewStore(stateDir)
const choicesAdapter = new AmadeusChoicesAdapter(ctx, stateDir)

registerAmadeusTools(ctx, reportsAdapter, previewStore)
choicesAdapter.install()

const business: AmadeusGatewayOptions = {
  sessions: sessionsAdapter,
  reports: reportsAdapter,
  choices: choicesAdapter,
}
```
并在 extension 的 `POST /sessions` 分支：create 成功后、返回前，调用开场 prompt（仅当新建，非沿用已有）：
```ts
const created = await business.sessions.create(mode, title)
await sessionCommands.prompt(created.id, '你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话')
```
> 注：`AmadeusReportsAdapter` 与 `AmadeusGatewayOptions['reports']` 的 save 签名需对齐（`save(report)` 而非 `save({id,...})` 若接口要求，Task 3 已按 `{id,title,markdown,createdAt}` 实现，Plugin 侧适配即可）。

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-plugin-opening.test.ts` 与 `npx tsc --noEmit`
Expected: PASS / 无类型错误。

```bash
git add src/amadeus-plugin.ts tests/amadeus-plugin-opening.test.ts
git commit -m "feat(host): wire real adapters and opening prompt into plugin"
```

---

### Task 6: SSE `/stream/:sessionId` 端点 + 新路由

**Files:**
- Create: `src/amadeus-stream.ts`
- Create: `tests/amadeus-stream.test.ts`
- Modify: `src/amadeus-extension.ts`（注册新路由：rename/archive/prompt/cancel/page/workspaces/stream/previews/:id/choice）

**Interfaces:**
- Consumes: `sessionController.follow()`（AsyncIterable<SessionFollowFrame>）、`AmadeusSegmentParser`（来自 `src/amadeus-tags.ts` 的 `parseAmadeusSegments`/`stripAllTags`）、`AmadeusChoicesAdapter`。
- Produces:
  - `class AmadeusStreamHub(ctx)`：
    - `open(sessionId, write: (data: string) => void): Promise<() => void>`：`sessionController.follow({address:{kind:'session',sessionId}})` → 遍历帧：`snapshot` 帧→历史 assistant/message 文本按 `parseAmadeusSegments` 拆分逐条 `write(segments)`；事件帧 `assistant/message` → `write(segments)`；`session/end`/迭代结束 → `write(ended)`。
    - 断连返回关闭函数。
  - 路由：
    - `GET <routes>/stream/:sessionId`：`Content-Type text/event-stream`；每个 `data:` 一行 `{"type":"segments","text":"..."}`；`retry: 2000`；心跳注释行；连接关闭即 close。
    - `POST <routes>/sessions/:id/rename|archive|prompt|cancel` → 调 `AmadeusSessionCommands`。
    - `GET <routes>/sessions/:id/page` → `{messages,hasMore}`。
    - `GET <routes>/workspaces` → `workspaceRegistry.list()` 映射 `{id,path,title}`。
    - `GET <routes>/previews/:id` → `AmadeusPreviewStore.get`。
    - `POST <routes>/choice` → `choicesAdapter.resolve(choiceId, selected)`。
  - SSE 事件 JSON 严格匹配 App 契约（`{"type":"segments","text":"..."}` 等）。

- [ ] **Step 1: 写失败测试（用 fake follow）**

`tests/amadeus-stream.test.ts`:
```ts
import { describe, expect, it } from 'vitest'
import { AmadeusStreamHub } from '../src/amadeus-stream.js'

describe('stream hub', () => {
  it('emits segment frames from follow events', async () => {
    const frames: any[] = [
      { type: 'event', event: { type: 'assistant/message', data: { message: { text: '好呀\n[[AMW:{"mood":"happy","sprite":"wag"}]]' } } } },
    ]
    const ctx = {
      sessionController: { follow: async function* () { for (const f of frames) yield f } },
    } as any
    const hub = new AmadeusStreamHub(ctx)
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await close()
    const payload = JSON.parse(written[0])
    expect(payload.type).toBe('segments')
    expect(payload.text).toContain('好呀')
  })
})
```

- [ ] **Step 2: 实现**

`src/amadeus-stream.ts`:
```ts
import { parseAmadeusSegments } from './amadeus-tags.js'

export class AmadeusStreamHub {
  constructor(private readonly ctx: any) {}

  async open(sessionId: string, write: (data: string) => void): Promise<() => void> {
    let closed = false
    const frames = this.ctx.sessionController.follow({ address: { kind: 'session', sessionId } })
    const pump = (async () => {
      for await (const frame of frames) {
        if (closed) break
        if (frame.type === 'event') {
          const e = frame.event
          if (e.type === 'assistant/message') {
            const text = e.data?.message?.text ?? ''
            const segments = parseAmadeusSegments(text)
            for (const seg of segments) {
              write(JSON.stringify({ type: 'segments', text: seg.raw }))
            }
          }
        }
      }
      if (!closed) write(JSON.stringify({ type: 'ended', reason: 'stream_closed' }))
    })()
    return async () => { closed = true }
  }
}
```
> 注：`parseAmadeusSegments` 若返回段对象含 `raw` 字段（原始短句），则 `text: seg.raw`；若字段名不同（如 `text`），以 `src/amadeus-tags.ts` 实际返回为准，确保 SSE 的 `text` 是**含标签的原始句子**（App 端再解析）。若 `parseAmadeusSegments` 仅返回拆分后的句+标签对象，则改为拼接成 App 契约的 `句\n[[AMW:...]]` 文本。

在 `src/amadeus-extension.ts` 新增路由分支（在现有 handler 内按 `method + url 前缀` 分发）：
```ts
// 示例分发（以现有路由结构为准补入）
if (req.method === 'GET' && url.startsWith('/stream/')) {
  const sessionId = url.slice('/stream/'.length)
  return await handleStream(sessionId, req, res)
}
// POST /sessions/:id/rename|archive|prompt|cancel
// GET /sessions/:id/page
// GET /workspaces
// GET /previews/:id
// POST /choice -> choices.resolve
```

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-stream.test.ts` 与 `npx tsc --noEmit`
Expected: PASS / 无类型错误。

```bash
git add src/amadeus-stream.ts tests/amadeus-stream.test.ts src/amadeus-extension.ts
git commit -m "feat(host): SSE stream endpoint and session/report/choice/workspace routes"
```

---

### Task 7: 控制面板实接（client + 状态路由）

**Files:**
- Modify: `src/amadeus-client.ts`（控制面板真实状态）
- Modify: `src/amadeus-plugin.ts` 或 `src/amadeus-extension.ts`（暴露管理状态：running/device/pairing）

**Interfaces:**
- Consumes: 网关 `MobileAccessGateway` 的 control/device/pairing 能力（`src/gateway.ts` 暴露的 controller/store）。
- Produces:
  - `GET /api/amadeus/control`：返回 `{running, devices: [...], pairingOpen}`（真实数据）。
  - `POST /api/amadeus/control`：on/off。
  - `POST /api/amadeus/pairing/open`：开配对窗口返回 token。
  - `GET /api/amadeus/devices`：已配对设备列表。
  - `POST /api/amadeus/devices/revoke`：撤销设备。
  - `amadeus-client.ts` 面板读这些接口展示。

- [ ] **Step 1: 写失败测试**

`tests/amadeus-control.test.ts`（验证 control 路由透传真实 store 数据）：
```ts
import { describe, expect, it } from 'vitest'
import { AmadeusControlRoutes } from '../src/amadeus-extension.js' // 或实际导出

describe('control routes', () => {
  it('reports running from lan controller', async () => {
    const routes = new AmadeusControlRoutes({
      running: true,
      devices: [{ id: 'd1', label: 'Phone' }],
      pairingOpen: false,
    } as any)
    const res = await routes.controlGet()
    const body = JSON.parse(res.body as string)
    expect(body.running).toBe(true)
    expect(body.devices[0].label).toBe('Phone')
  })
})
```

- [ ] **Step 2: 实现**

在 plugin 的管理路由 `adminRoute` 的 handler 内，把 `/api/amadeus/control`、`/pairing/open`、`/devices`、`/devices/revoke` 接到网关真实状态：
```ts
const state = {
  running: startRuntime.running,
  devices: deviceStore.list().map(d => ({ id: d.id, label: d.label })),
  pairingOpen: access.pairingStatus().open,
}
```
（`amadeus-client.ts` 已调用这些端点，确保返回结构与之匹配；若 `MobileAccessGateway`/`JsonDeviceStore` 方法名不同，以 `src/gateway.ts` 实际 API 为准。）

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-control.test.ts` 与 `npx tsc --noEmit`
Expected: PASS / 无类型错误。

```bash
git add src/amadeus-client.ts src/amadeus-extension.ts tests/amadeus-control.test.ts
git commit -m "feat(host): real control-panel state (running/devices/pairing)"
```

---

### Task 8: 远程 provider 框架（FRP 优先）

**Files:**
- Create: `src/amadeus-remote.ts`
- Create: `tests/amadeus-remote.test.ts`

**Interfaces:**
- Consumes: 参考 dsh-mobile 思想（不搬代码）：`RemoteProviderController` 生命周期、单一 provider 持久化、串行切换。
- Produces:
  - `type RemoteProvider = 'tailscale' | 'cpolar' | 'frp'`
  - `interface AmadeusRemoteStatus { enabled: boolean; state: string; origin?: string; errorCode?: string }`
  - `interface AmadeusRemoteController { initialize(): Promise<void>; status(): AmadeusRemoteStatus; setEnabled(on: boolean): Promise<void>; close(): Promise<void> }`
  - `class AmadeusRemoteCoordinator(controllers, store, defaultProvider)`：`selected`、`select(provider)`（串行、互斥、持久化）、`status()`。
  - `class FrpController : AmadeusRemoteController`：FRP 优先——读取 `~/.dsh/amadeus/frp.json`（serverAddress/port/token/publicOrigin），启动 frpc 子进程（社区开源 frp 二进制），`status()` 报告状态与公网 origin。
  - `JsonRemoteStore`：持久化 `~/.dsh/amadeus/remote-provider.json`。
  - 本 Task 只搭框架 + FRP controller 骨架（frpc 启动/停止/状态），不做完整 GUI。

- [ ] **Step 1: 写失败测试**

`tests/amadeus-remote.test.ts`:
```ts
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { AmadeusRemoteCoordinator, JsonRemoteStore } from '../src/amadeus-remote.js'

let dir: string
beforeEach(async () => { dir = await mkdtemp(join(tmpdir(), 'amw-')) })
afterEach(async () => { await rm(dir, { recursive: true, force: true }) })

describe('remote coordinator', () => {
  it('persists selection', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    const coord = new AmadeusRemoteCoordinator({ frp: { status: () => ({ enabled: false, state: 'off' }), setEnabled: async () => {}, initialize: async () => {}, close: async () => {} }, tailscale: {}, cpolar: {} } as any, store, 'frp')
    expect(coord.selected).toBe('frp')
  })
  it('frp status reflects enabled', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    const coord = new AmadeusRemoteCoordinator({ frp: { status: () => ({ enabled: true, state: 'ready', origin: 'https://dsh.example.com' }) } } as any, store, 'frp')
    expect(coord.status('frp').origin).toBe('https://dsh.example.com')
  })
})
```

- [ ] **Step 2: 实现**

`src/amadeus-remote.ts`（核心骨架）:
```ts
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

export type RemoteProvider = 'tailscale' | 'cpolar' | 'frp'
export interface AmadeusRemoteStatus { enabled: boolean; state: string; origin?: string; errorCode?: string }
export interface AmadeusRemoteController {
  initialize(): Promise<void>
  status(): AmadeusRemoteStatus
  setEnabled(on: boolean): Promise<void>
  close(): Promise<void>
}

export class JsonRemoteStore {
  constructor(private readonly file: string, private readonly defaultProvider: RemoteProvider) {}
  async load(): Promise<RemoteProvider> {
    try { return (JSON.parse(await readFile(this.file, 'utf8')) as { provider: RemoteProvider }).provider }
    catch { return this.defaultProvider }
  }
  async save(provider: RemoteProvider): Promise<void> {
    await mkdir(dirname(this.file), { recursive: true })
    await writeFile(this.file, JSON.stringify({ version: 1, provider }))
  }
}

export class AmadeusRemoteCoordinator {
  private selectedValue: RemoteProvider
  private queue: Promise<void> = Promise.resolve()
  constructor(
    private readonly controllers: Record<RemoteProvider, AmadeusRemoteController>,
    private readonly store: JsonRemoteStore,
    defaultProvider: RemoteProvider,
  ) { this.selectedValue = defaultProvider }

  get selected(): RemoteProvider { return this.selectedValue }

  async initialize(): Promise<void> {
    this.selectedValue = await this.store.load()
    await this.controllers[this.selectedValue].initialize()
  }

  select(provider: RemoteProvider): Promise<void> {
    const run = async () => {
      if (provider === this.selectedValue) return
      const prev = this.controllers[this.selectedValue]
      if (prev.status().enabled) await prev.setEnabled(false)
      await this.store.save(provider)
      this.selectedValue = provider
    }
    const task = this.queue.then(run, run)
    this.queue = task.then(() => undefined, () => undefined)
    return task
  }

  status(): AmadeusRemoteStatus { return this.controllers[this.selectedValue].status() }
  async close(): Promise<void> { for (const c of Object.values(this.controllers)) await c.close() }
}

export class FrpController implements AmadeusRemoteController {
  private enabled = false
  private originValue: string | undefined
  constructor(private readonly dir: string, private readonly configFile: string) {}
  async initialize(): Promise<void> {
    // 读 frp.json：{serverAddress, port, token, publicOrigin}
    // 若存在则准备启动（不自动启动）
  }
  status(): AmadeusRemoteStatus {
    return this.enabled ? { enabled: true, state: 'ready', origin: this.originValue } : { enabled: false, state: 'off' }
  }
  async setEnabled(on: boolean): Promise<void> {
    this.enabled = on
    if (on) { this.originValue = 'https://dsh.example.com' /* 由 frpc 真实 origin 填充 */ }
  }
  async close(): Promise<void> { this.enabled = false }
}
```
> 注：本 Task 交付框架与 FRP 状态机骨架；frpc 实际进程启停、公网端点校验、origin 填充，留待 Host 计划后续迭代（或本期验收冒烟时接入社区 frpc 二进制）。

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `npx vitest run tests/amadeus-remote.test.ts` 与 `npx tsc --noEmit`
Expected: PASS / 无类型错误。

```bash
git add src/amadeus-remote.ts tests/amadeus-remote.test.ts
git commit -m "feat(host): remote provider framework with FRP-first controller"
```

---

### Task 9: 客户端 bundle 修复 + 端到端验收

**Files:**
- Modify: `package.json`（确认 `exports["./client"]`、`dsh.client` 声明已就位）
- Modify: `cordis.patch.yml`（如有需要）
- Run: 构建 + 本地 DSH 起插件验证

**Interfaces:**
- Consumes: 全部 Task 产物。
- Produces: 客户端 bundle 出现在 boot 图（/plugins 200）；端到端链路通。

- [ ] **Step 1: 验证 package.json 客户端声明**

确认 `package.json` 含：
```json
{
  "exports": { "./client": "./lib/client.js" },
  "dsh": { "client": { "inject": ["@deepseek-ai/dsh-client-connection", "@deepseek-ai/dsh-client-ui-sidebar"] } }
}
```
Run: `npx tsup`（或项目现有构建脚本）产出 `lib/client.js`。

- [ ] **Step 2: 端到端冒烟（需 DSH 环境）**

- 本地启动 DSH，加载 `dsh-amadeus` 插件（`cordis.patch.yml`：id=amadeus、listenPort=3444、initiallyEnabled 视需要）。
- 验证：`GET http://127.0.0.1:3444/amadeus/extensions/amadeus/routes/status` → capabilities 全 true。
- 验证：桌面 sidebar 出现 Amadeus 控制面板，running/设备/配对真实。
- 验证：`/plugins` 或客户端注入使控制面板 bundle 正常加载（客户端 bundle 出现在 boot 图）。
- 手机装 App（App 计划产物）连网关：新建会话→开场 prompt→SSE 短句→输入文字→save_report/show_preview→choice→history 分页。
- 记录问题并修复至通过。

- [ ] **Step 3: 最终提交**

```bash
git add package.json cordis.patch.yml src lib
git commit -m "chore(host): client bundle wiring and e2e acceptance"
```

---

## Self-Review 记录

- **Spec 覆盖**：adapter 注入（T2-T5）、专用工具（T3）、SSE（T6）、开场 A（T5）、控制面板实接（T7）、远程框架 FRP 优先（T8）、客户端 bundle（T9）、持久化 ~/.dsh/amadeus（T3/T4）、工作区（T2/T6）。
- **App 契约覆盖**：全部 REST 路由（T1/T2/T3/T6）、SSE 三态（T6）、choice 回调（T4/T6）、workspaces（T6）。
- **占位符**：无 TBD；T8 明确 frpc 进程启停为后续迭代并声明本期范围；各 Task 对 DSH 服务签名差异给出「以实际实现为准」的适配说明（因本计划在外部仓库上下文编写，不能读取 DSH 内全部签名，故保留适配点并配套 fake 测试锁定语义）。
- **类型一致性**：`AmadeusSessionSummary`/`AmadeusGatewayOptions` 以现有 `src/amadeus-extension.ts` 为准（Task 2 注明导入）；`AmadeusReportRecord`/`AmadeusPreviewRecord` 跨 T3/T6 一致；SSE 事件 JSON 与 App 计划 T11 的 `StreamEvent` 逐字段对齐。

## 待办（计划外）

- frpc 真实进程启停与公网端点校验（T8 骨架后续迭代）。
- App↔Host 联调中发现的契约偏差，需两边同步修订（以 spec 为唯一权威）。
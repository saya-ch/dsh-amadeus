# Amadeus Whale Host 远程 frpc 实接 + P1-5 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `src/amadeus-remote.ts` 的 `FrpController` 从骨架变为可用的 frpc 进程控制器（真实启停 + 公网端点校验 + 状态上报），接入 plugin 与桌面控制面板；并补强 P1-5（`user-questions/request` sessionId 路由的端到端测试锁链路）。

**Architecture:** `FrpController` 依赖注入可执行命令抽象（测试用假命令，生产用 `frpc`），`setEnabled(true)` spawn 子进程、`setEnabled(false)` 终止；公网端点用 Node 内置 `net`/`http` 探测。`AmadeusRemoteCoordinator` 已在 `amadeus-remote.ts` 就位（串行/持久化单 provider），本期补 `plugin` 接线 + `control` 面板 remote 状态字段。P1-5 在 `tests/amadeus-choices.test.ts` / `amadeus-routes.test.ts` 补端到端用例。

**Tech Stack:** Node.js + TypeScript（tsdown 构建）、vitest、Cordis。

**工作目录:** 仓库根 `C:\develop\dsh-amadeus`（Host）。命令：`npm test`（vitest）、`npm run typecheck`（tsc --noEmit）、`npm run build`（tsdown）。

## Global Constraints

- 所有改动仅限 `src/` 与 `tests/`；不触碰 DSH/dsh-mobile 源码。
- 不修改 `src/gateway.ts`/`access.ts`/`http-security.ts` 的认证模型；远程接入只在业务层。
- frpc 可执行路径走配置项（`frp.json` 或 plugin config），默认探测 `frpc` PATH；真实 frpc 不在仓库（测试用 `node -e` / `cmd /c` 假命令模拟）。
- `RemoteProvider = 'tailscale' | 'cpolar' | 'frp'`；Tailscale/cpolar 保持骨架（不实现）。
- `AmadeusRemoteStatus{enabled,state,origin?,errorCode?}` 契约不变。
- 测试基线：`tests/mobile-extension.test.ts:333` 已知失败（inject 断言，与任务无关），不得新增其他失败。
- 文件末尾加换行；每任务单提交。
- 运行验证命令：`npm test`、`npm run typecheck`。

---

### Task 1: FrpController 进程启停 + 公网端点校验

**Files:**
- Modify: `src/amadeus-remote.ts`
- Modify: `tests/amadeus-remote.test.ts`

**Interfaces:**
- Consumes: 现有 `AmadeusRemoteController` 接口（`initialize()/status()/setEnabled(on)/close()`）、`AmadeusRemoteStatus`。
- Produces:
  - `interface FrpRuntime { serverAddress: string; serverPort: number; token?: string; remotePort?: number; publicOrigin?: string }`（来自 `frp.json`）
  - `class FrpController implements AmadeusRemoteController`：
    - 构造：`(dir: string, configFile: string, options?: { command?: string; spawn?: typeof import('node:child_process').spawn })`。`command` 默认 `'frpc'`。
    - `initialize()`：读 `configFile`（`~/.dsh/amadeus/frp.json`，由 plugin 传入 `stateDir` 路径），解析 `FrpRuntime`；无文件则 `state='unconfigured'`。
    - `status()`：`enabled` false → `{enabled:false, state: 'off' | 'unconfigured'}`；enabled 且进程活着 → `{enabled:true, state:'running', origin?, pid}`；进程退出 → `{enabled:false, state:'failed', errorCode}`。
    - `setEnabled(true)`：spawn frpc 子进程（`--server_addr host:port --server_port serverPort --token ...` 参数；remote 端口映射写成 frpc.toml/ini 到 `dir/frpc-run.toml`，再 `frpc -c <file>`）。进程 on('exit') 更新状态与 errorCode。`setEnabled(false)`：`kill()` 子进程，清 pid。
    - 公网端点校验：`status().origin` 仅在 `publicOrigin` 配置且端点探测成功时填充；探测用 `net.createConnection({port, host})` 超时 3s 或 `http.get`，成功才报 origin 可用；失败置 `errorCode='endpoint_unreachable'`。
    - `close()`：终止进程 + 清理运行配置文件。
  - `parseFrpConfig(raw: unknown): FrpRuntime` — 校验 `serverAddress`（host）/`serverPort`（1-65535）必填，`remotePort`/`publicOrigin` 可选；非法抛 `Error`。

- [ ] **Step 1: 写失败测试（FrpController 假进程启停 + 端点校验）**

在 `tests/amadeus-remote.test.ts` 的 `frp controller` describe 中扩展（或新增 describe）：

```typescript
import { spawn } from 'node:child_process'
import { once } from 'node:events'

function fakeFrpcCommand(marker: string): string {
  // Windows 下用 cmd /c 写一个可退出子进程脚本；POSIX 用 node -e
  const script = process.platform === 'win32'
    ? `cmd /c "ping -n 60 127.0.0.1 >nul & exit 0"`
    : `node -e "setTimeout(()=>{},60000)"`
  return script
}

describe('frp controller process wiring', () => {
  it('starts and stops a child process', async () => {
    const frp = new FrpController(dir, join(dir, 'frp.json'), {
      command: fakeFrpcCommand('x'),
    })
    await frp.initialize()
    expect(frp.status().state).toBe('unconfigured')
    await frp.setEnabled(true)
    expect(frp.status().state).toBe('running')
    await frp.setEnabled(false)
    expect(frp.status().state).toBe('off')
  })

  it('reports configured origin only when reachable', async () => {
    // 写入 frp.json 带 publicOrigin 指向一个必然失败的端口，断言 errorCode=endpoint_unreachable
    await writeFile(join(dir, 'frp.json'), JSON.stringify({
      serverAddress: 'example.com', serverPort: 7000, publicOrigin: 'https://amw.example.com',
    }))
    const frp = new FrpController(dir, join(dir, 'frp.json'), {
      command: fakeFrpcCommand('x'),
      reachabilityTimeoutMs: 500,
    })
    await frp.initialize()
    expect(frp.status().state).toBe('unconfigured')
    await frp.setEnabled(true)
    const st = frp.status()
    expect(st.state).toBe('running')
    expect(st.errorCode).toBe('endpoint_unreachable')
  })
})
```

注意：`FrpController` 构造的 `command` 注入是设计核心（测试不真正跑 frpc）。spawn 抽象：`options.spawn` 默认 `child_process.spawn`，测试可注入记录型 fake 或真实 `spawn`（用 `node -e` 长 sleep 模拟长驻进程）。**优先注入 spawn fake** 断言启停调用与信号，避免测试真实 spawn 的不稳定。请实现为：

```typescript
export interface FrpControllerOptions {
  command?: string
  spawn?: (command: string, args: string[], options: object) => { on(event: string, cb: (code?: number, signal?: string) => void): unknown; kill(signal?: string): boolean; pid?: number }
  reachabilityTimeoutMs?: number
}
```

测试注入 fake spawn 记录 `{args, killed}`，断言 `setEnabled(true)` 调用 spawn、`setEnabled(false)` 调用 kill、`close()` 终止；端点校验失败置 `errorCode`。

- [ ] **Step 2: 运行确认失败**

Run: `npm test -- tests/amadeus-remote.test.ts`（vitest 单文件，或 `npx vitest run tests/amadeus-remote.test.ts --pool=threads --maxWorkers=1`）
Expected: FAIL（`FrpController` 构造缺 options、无对应行为）。

- [ ] **Step 3: 实现 FrpController（替换骨架）**

```typescript
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { spawn as nodeSpawn } from 'node:child_process'
import { createConnection } from 'node:net'
import { get as httpGet } from 'node:http'

export interface FrpRuntime {
  serverAddress: string
  serverPort: number
  token?: string
  remotePort?: number
  publicOrigin?: string
}

export function parseFrpConfig(raw: unknown): FrpRuntime {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw new Error('frp config must be an object')
  const value = raw as Record<string, unknown>
  if (typeof value.serverAddress !== 'string' || value.serverAddress.length === 0) {
    throw new Error('frp config requires serverAddress')
  }
  const serverPort = value.serverPort
  if (typeof serverPort !== 'number' || !Number.isInteger(serverPort) || serverPort < 1 || serverPort > 65535) {
    throw new Error('frp config requires serverPort in 1..65535')
  }
  return {
    serverAddress: value.serverAddress,
    serverPort,
    ...(typeof value.token === 'string' ? { token: value.token } : {}),
    ...(typeof value.remotePort === 'number' ? { remotePort: value.remotePort } : {}),
    ...(typeof value.publicOrigin === 'string' ? { publicOrigin: value.publicOrigin } : {}),
  }
}
```

在 `FrpController` 中：
- 私有字段：`enabled`、`config: FrpRuntime | undefined`、`child: SpawnHandle | undefined`、`originValue`、`errorCodeValue`、`pidValue`。
- `initialize()`：`readFile(configFile)` 成功 → `parseFrpConfig(JSON.parse(...))` 存 `config`；ENOENT → 置 `unconfigured` 状态（`state='unconfigured'`，`enabled=false`）。parse 失败 → 记 `errorCode='invalid_frp_config'`。
- `status()` 依下表：
  - 未 configure：`{enabled:false, state:'unconfigured'}`
  - enabled=false：`{enabled:false, state:'off'}`
  - enabled=true 且 child 活着：`{enabled:true, state:'running', ...(pidValue? {pid:pidValue} : {}), ...(originValue? {origin:originValue}:{}), ...(errorCodeValue? {errorCode:errorCodeValue}:{})}`
  - enabled=true 但 child 已退出：`{enabled:false, state:'failed', ...(errorCodeValue? {errorCode:errorCodeValue}:{})}`
- `setEnabled(on)`：
  - on=true：写 `frpc-run.toml`（`serverAddr = "<host>"`、`serverPort = <port>`、可选 `auth.token`；remote 段用 `[[proxies]]`：`name="amadeus-whale"`、`type="tcp"`、`localIP="127.0.0.1"`、`localPort=3444`、`remotePort=<remotePort ?? serverPort>`）到 `join(dir,'frpc-run.toml')`；`child = spawn(command, ['-c', configPath], {stdio:['ignore','pipe','pipe']})`；`child.on('exit', code => { pidValue=undefined; errorCodeValue = code===0 ? undefined : `frpc_exit_${code ?? 'signal'}` })`；随后异步探测 `publicOrigin`（见下）。
  - on=false：`child?.kill('SIGTERM')`；child=undefined；enabled=false；originValue 清；errorCodeValue 清。
- 公网端点校验（`probePublicOrigin(origin: string)`）：解析 URL，`createConnection({host, port: parsed.port || 443})` 设 `setTimeout(reachabilityTimeoutMs ?? 3000)`，成功 `originValue = origin`，超时/错误 `errorCodeValue = 'endpoint_unreachable'`。
- `close()`：kill child；`rm(join(dir,'frpc-run.toml'), {force:true})`；enabled=false。

`SpawnHandle` 最小接口（供测试注入）：`{ on(event:'exit', cb:(code?:number, signal?:string)=>void): unknown; kill(signal?:string): boolean; pid?: number }`。

- [ ] **Step 4: 运行确认通过**

Run: `npx vitest run tests/amadeus-remote.test.ts --pool=threads --maxWorkers=1`
Expected: PASS（新增用例 + 既有 12 用例全绿）。

- [ ] **Step 5: typecheck**

Run: `npm run typecheck`
Expected: exit 0。

- [ ] **Step 6: 提交**

```bash
git add src/amadeus-remote.ts tests/amadeus-remote.test.ts
git commit -m "feat(host): frpc process controller with endpoint probe"
```

---

### Task 2: 远程状态接线到 plugin 与桌面控制面板

**Files:**
- Modify: `src/amadeus-plugin.ts`
- Modify: `src/amadeus-control.ts`
- Modify: `tests/amadeus-control.test.ts`
- Modify: `tests/amadeus-plugin-opening.test.ts`（如有必要）

**Interfaces:**
- Consumes: `AmadeusRemoteCoordinator`/`FrpController`/`JsonRemoteStore`（Task 1），`AmadeusControlRoutes`。
- Produces:
  - `plugin.ts`：`apply()` 内构造 `remoteDir = join(stateDir, 'remote')`、`remoteCoordinator = new AmadeusRemoteCoordinator({ frp: new FrpController(stateDir, join(stateDir, 'frp.json')), tailscale: new TailscaleControllerSkeleton(), cpolar: new CpolarControllerSkeleton() }, new JsonRemoteStore(join(stateDir, 'remote.json'), 'frp'), 'frp')`；`await remoteCoordinator.initialize()`（在 ctx.effect 内，launch 时执行）。
  - `control.ts`：`AmadeusControlState` 增加 `remote?: { provider: RemoteProvider; enabled: boolean; state: string; origin?: string; errorCode?: string }`；`AmadeusGatewayControl` 增加可选 `remoteStatus?: () => AmadeusRemoteStatus & { provider: RemoteProvider } | undefined`；`controlGet()` 在 gateway 存在时附带 remote 字段。
  - `AmadeusControlRoutesOptions` 增加 `remoteProvider: () => RemoteProvider`、`remoteStatus: () => AmadeusRemoteStatus`。

- [ ] **Step 1: 写失败测试（control 附带 remote）**

在 `tests/amadeus-control.test.ts` 增加：
```typescript
it('includes remote status when present', () => {
  const routes = new AmadeusControlRoutes({
    isRunning: () => true,
    gateway: () => ({ origin: 'https://127.0.0.1:3444', devices: () => [], pairingStatus: () => ({ open: false }) }),
    remoteProvider: () => 'frp',
    remoteStatus: () => ({ enabled: true, state: 'running', origin: 'https://amw.example.com' }),
  })
  const parsed = JSON.parse(routes.controlGet().body) as { remote?: { provider: string; enabled: boolean; state: string; origin?: string } }
  expect(parsed.remote).toEqual({ provider: 'frp', enabled: true, state: 'running', origin: 'https://amw.example.com' })
})
```
同时更新既有 `controlGet` 测试的构造调用（若 `AmadeusControlRoutes` 构造签名扩展为必需，既有用例需补 `remoteProvider`/`remoteStatus` 参数）。

- [ ] **Step 2: 运行确认失败**

Run: `npx vitest run tests/amadeus-control.test.ts --pool=threads --maxWorkers=1`
Expected: FAIL（构造签名/字段缺失）。

- [ ] **Step 3: 实现 control.ts 扩展 + plugin 接线**

`amadeus-control.ts`：
```typescript
export interface AmadeusRemoteView {
  provider: RemoteProvider
  enabled: boolean
  state: string
  origin?: string
  errorCode?: string
}

export interface AmadeusControlRoutesOptions {
  readonly isRunning: () => boolean
  readonly gateway: () => AmadeusGatewayControl | undefined
  readonly remoteProvider: () => RemoteProvider
  readonly remoteStatus: () => AmadeusRemoteStatus
}

controlGet() {
  const gateway = this.options.gateway()
  const remoteStatus = this.options.remoteStatus()
  const state: AmadeusControlState = {
    running: this.options.isRunning(),
    devices: gateway?.devices().map(...) ?? [],
    pairingOpen: gateway?.pairingStatus().open ?? false,
    ...(gateway === undefined ? {} : { origin: gateway.origin }),
    ...(remoteStatus === undefined ? {} : { remote: { provider: this.options.remoteProvider(), ...remoteStatus } }),
  }
  return { status: 200, body: JSON.stringify(state) }
}
```

`amadeus-plugin.ts`：`apply()` 中在构造 `controlRoutes` 前：
```typescript
import { AmadeusRemoteCoordinator, FrpController, JsonRemoteStore } from './amadeus-remote.js'

const remoteCoordinator = new AmadeusRemoteCoordinator(
  { frp: new FrpController(stateDir, join(stateDir, 'frp.json')), tailscale: new NoopRemoteController(), cpolar: new NoopRemoteController() },
  new JsonRemoteStore(join(stateDir, 'remote.json'), 'frp'),
  'frp',
)
```
其中 `NoopRemoteController` 是未实现 provider 的骨架（`status()` 返回 `{enabled:false,state:'off'}`，其余空实现）——若仓库已有类似骨架请复用（检查 `amadeus-remote.ts` 是否导出）。**注意**：Tailscale/cpolar 用 `FrpController` 之外的占位——可在 `amadeus-remote.ts` 新增 `export class NoopRemoteController implements AmadeusRemoteController { ... }`。

`controlRoutes` 构造加：
```typescript
remoteProvider: () => remoteCoordinator.selected,
remoteStatus: () => remoteCoordinator.status(),
```

`ctx.effect` 内：
```typescript
await remoteCoordinator.initialize()
```
并在 teardown `await remoteCoordinator.close()`。

- [ ] **Step 4: 运行确认通过**

Run: `npx vitest run tests/amadeus-control.test.ts tests/amadeus-plugin-opening.test.ts --pool=threads --maxWorkers=1`
Expected: PASS。

- [ ] **Step 5: typecheck + 全量测试**

Run: `npm run typecheck`（exit 0）；`npm test`（仅基线 `mobile-extension.test.ts:333` 失败）。

- [ ] **Step 6: 提交**

```bash
git add src/amadeus-plugin.ts src/amadeus-control.ts src/amadeus-remote.ts tests/amadeus-control.test.ts
git commit -m "feat(host): wire remote coordinator status into control panel"
```

---

### Task 3: P1-5 user-questions sessionId 路由端到端测试锁链路

**Files:**
- Modify: `tests/amadeus-choices.test.ts`
- Modify: `tests/amadeus-routes.test.ts`

**Interfaces:**
- Consumes: `AmadeusChoicesAdapter.answerRequest`（`sessionIdOf` 用 `agent.session?.id` / `agent.id`）、`registerStream`、`POST /choice`、`POST /choice/cancel`（f278d02 已实现）。
- Produces: 无新接口，仅补测试。

**背景**：P1-5 要确认 `user-questions/request` waterfall 事件里 agent 带 `session.id`，answerer 能据此路由到对应 SSE 流；choice 取消经 `POST /choice/cancel` 后 answerer reject。当前实现已在 62b8140/f278d02 完成，本期用端到端测试锁定契约（模拟 DSH 以 `{questions, agent:{session:{id}}}` 分发 waterfall）。

- [ ] **Step 1: 写失败测试（先确认是否已覆盖，未覆盖则新增）**

在 `tests/amadeus-choices.test.ts` 增加 describe `session routing`：
```typescript
describe('session routing of user-questions/request', () => {
  it('pushes a choice frame to the stream registered for agent.session.id', async () => {
    const ctx = new TestContext()
    const adapter = new AmadeusChoicesAdapter(ctx, dir)
    adapter.install()
    const pushed: object[] = []
    adapter.registerStream('session-1', frame => pushed.push(frame))
    const answer = await adapter.answerRequest({
      questions: [{ id: 'q1', question: '选择', options: [{ label: 'A' }, { label: 'B' }] }],
      agent: { session: { id: 'session-1' } },
    })
    expect(pushed).toHaveLength(1)
    const frame = pushed[0] as { type: string; choiceId: string; question: string; options: Array<{ label: string }> }
    expect(frame.type).toBe('choice')
    expect(frame.question).toBe('选择')
    expect(frame.options.map(o => o.label)).toEqual(['A', 'B'])
    expect(answer.answers[0].id).toBe('q1')
  })

  it('falls back to agent.id when session.id is absent', async () => {
    const ctx = new TestContext()
    const adapter = new AmadeusChoicesAdapter(ctx, dir)
    const pushed: object[] = []
    adapter.registerStream('agent-9', frame => pushed.push(frame))
    // 直接调 answerRequest；agent 是裸 string id
    const promise = adapter.answerRequest({
      questions: [{ id: 'q1', question: 'x', options: [{ label: 'A' }] }],
      agent: 'agent-9',
    })
    expect(pushed).toHaveLength(1)
    const frame = pushed[0] as { type: string; choiceId: string }
    expect(frame.type).toBe('choice')
    // 取消 pending，避免等待悬挂
    await adapter.cancel((frame as { choiceId: string }).choiceId)
    await promise
  })
})
```

在 `tests/amadeus-routes.test.ts` 增加 choice cancel 端到端：
```typescript
it('POST /choice/cancel rejects the pending answerer', async () => {
  // 建 adapter + routes；adapter.registerStream 收集；answerRequest 挂起（await wait）
  // POST /choice/cancel {choiceId} → 200 {ok:true} → answerer 抛 'choice-cancelled'
  // 断言 cancel 响应 + answerRequest 的 rejection
})
```

参考现有 `amadeus-choices.test.ts`/`amadeus-routes.test.ts` 的 TestContext/fixture 模式（真实 `Context` + `ctx.on` + 真实 `waterfall` 分派，见 920912a 修复后的回归测试写法）。**关键**：复用现有 fixture，不要新建全局设施。

- [ ] **Step 2: 运行确认失败**

Run: `npx vitest run tests/amadeus-choices.test.ts tests/amadeus-routes.test.ts --pool=threads --maxWorkers=1`
Expected: 若测试已覆盖则 PASS（说明 P1-5 已锁）；若未覆盖则 FAIL（新增用例揭示缺口，需修实现）。

- [ ] **Step 3: 修复实现缺口（若测试揭示）**

若 `answerRequest`/`sessionIdOf` 与测试不符（如 agent 形状不同），修正 `src/amadeus-choices.ts` 的 `sessionIdOf` 取值逻辑或 `pushChoice` 调用，保持 `sessionIdOf(agent)` 先取 `agent.session?.id` 再 `agent.id` 的既有契约（spec 4 现有实现已如此，通常无需改）。若测试全绿，跳过本步。

- [ ] **Step 4: 运行确认通过 + 全量回归**

Run: `npx vitest run tests/amadeus-choices.test.ts tests/amadeus-routes.test.ts --pool=threads --maxWorkers=1`
Expected: PASS。然后 `npm test`（仅基线失败）、`npm run typecheck`（exit 0）。

- [ ] **Step 5: 提交**

```bash
git add tests/amadeus-choices.test.ts tests/amadeus-routes.test.ts
git commit -m "test(host): lock session-routed choice and cancel end-to-end"
```

---

### Self-Review（控制器执行）

- **Spec 覆盖**：P1-3（Task 1 进程启停+端点校验、Task 2 plugin/control 接线）、P1-5（Task 3）。Tailscale/cpolar 保留骨架（NoopRemoteController）。P0-2 真机联调为验证项，实现完成后执行。
- **占位符扫描**：无 TBD；Task 1 的 SpawnHandle/NoopRemoteController 是明确要新建的接口。
- **类型一致性**：`AmadeusRemoteStatus`/`RemoteProvider` 跨 Task 1/2 一致；`AmadeusControlState.remote` 形状与 `AmadeusRemoteView` 一致；`FrpController` 构造签名在 Task 1 定义、Task 2 使用一致。
- **依赖顺序**：Task 1 先实现 `FrpController`，Task 2 才接线；Task 3 独立可并行，但计划内顺序执行。
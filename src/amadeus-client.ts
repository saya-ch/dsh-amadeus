/**
 * Amadeus Whale — 桌面控制面板（精简版）。
 * sidebar 底部按钮 → 面板：网关开关、地址、配对、设备管理。
 * 完全独立于 dsh-mobile，只走 /api/amadeus。
 */

interface ClientContext {
  effect(effect: () => void | (() => void), label?: string): void
  get(name: string): unknown
  slots: {
    inject(key: string, callback: () => (() => void)): () => void
    register<Props>(options: { name: string; id: string; order?: number; label?: string }, component: (props: Props) => unknown): () => void
  }
}

const BASE = '/api/amadeus'

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag)
  if (className !== undefined) node.className = className
  return node
}

function createElement(tag: string, props: Record<string, unknown>, ...children: (Node | string)[]): HTMLElement {
  const node = document.createElement(tag)
  for (const [key, value] of Object.entries(props)) {
    if (key === 'onClick' && typeof value === 'function') {
      node.addEventListener('click', value as EventListener)
    } else if (key === 'className') {
      node.className = String(value)
    } else {
      node.setAttribute(key, String(value))
    }
  }
  for (const child of children) node.append(child instanceof Node ? child : document.createTextNode(String(child)))
  return node
}

async function controlRequestJson(path: string, init: RequestInit = {}): Promise<Record<string, unknown>> {
  const response = await fetch(`${BASE}${path}`, {
    credentials: 'same-origin',
    headers: { 'content-type': 'application/json' },
    ...init,
  })
  if (!response.ok) throw new Error(`HTTP ${String(response.status)}`)
  return (await response.json()) as Record<string, unknown>
}

const STYLES = `
.amw-control{position:fixed;z-index:1000;left:16px;bottom:112px;font:13px/1.5 system-ui;color:var(--dsw-alias-label-primary,#16181d)}
.amw-control__panel{box-sizing:border-box;width:min(340px,calc(100vw - 32px));max-height:calc(100vh - 140px);overflow-y:auto;padding:14px;border:1px solid var(--dsw-alias-border-subtle,#e1e5eb);border-radius:16px;background:var(--dsw-alias-bg-layer-2,#fff);box-shadow:0 18px 50px rgb(15 23 42 / 18%)}
.amw-control__panel h2{margin:0 0 10px;font-size:16px}
.amw-control__status{margin:0 0 10px;overflow-wrap:anywhere}
.amw-control__status::before{display:inline-block;width:8px;height:8px;margin-right:7px;border-radius:50%;background:#98a1ad;content:""}
.amw-control__status.is-running::before{background:#16a36a}
.amw-control__row{display:flex;gap:8px;margin:8px 0}
.amw-control__row button{flex:1;min-height:36px;border:1px solid #cfd5dd;border-radius:10px;background:transparent;color:inherit;font:600 12px/1.3 system-ui;cursor:pointer}
.amw-control__row button.is-primary{border-color:#2563eb;background:#2563eb;color:#fff}
.amw-control__row button:disabled{cursor:wait;opacity:.5}
.amw-control__link{display:block;margin:6px 0;color:#2563eb;font-size:11px;overflow-wrap:anywhere}
.amw-control__device{display:flex;align-items:center;gap:8px;padding:6px 2px;font-size:12px}
.amw-control__device + .amw-control__device{border-top:1px solid var(--dsw-alias-border-subtle,#e1e5eb)}
.amw-control__trigger{box-sizing:border-box;display:flex;align-items:center;gap:8px;width:100%;height:34px;margin:4px 0;padding:6px 2px 6px 10px;border:0;border-radius:12px;background:transparent;color:var(--dsw-alias-label-primary,#16181d);font:14px/22px system-ui;cursor:pointer;text-align:left}
.amw-control__trigger:hover{background:var(--dsw-alias-interactive-bg-hover,#f1f3f6)}
.amw-control__trigger.is-rail{width:36px;height:36px;margin:8px 0 10px;padding:0;justify-content:center;border-radius:50%}
.amw-control__trigger-icon{position:relative;box-sizing:border-box;flex:none;width:14px;height:19px;border:1.7px solid currentColor;border-radius:3px}
.amw-control__trigger-icon::after{position:absolute;right:4px;bottom:2px;width:4px;height:1.5px;border-radius:2px;background:currentColor;content:""}
.amw-control__trigger-label{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
`

function installControl(): { root: HTMLElement; toggle(): void; remove(): void } {
  const root = element('div', 'amw-control')
  const panel = element('section', 'amw-control__panel')
  panel.hidden = true
  const status = element('p', 'amw-control__status')
  const toggle = element('button')
  const pair = element('button')
  const devicesBox = element('div')

  const refresh = async (): Promise<void> => {
    const state = await controlRequestJson('/control') as { running?: boolean; origin?: string }
    status.textContent = state.running === true
      ? `Amadeus 网关运行中${state.origin === undefined ? '' : ` · ${String(state.origin)}`}`
      : 'Amadeus 网关未开启'
    status.classList.toggle('is-running', state.running === true)
    toggle.textContent = state.running === true ? '关闭网关' : '开启网关'
    toggle.classList.toggle('is-primary', state.running !== true)
    toggle.disabled = false
  }

  const toggleGateway = async (): Promise<void> => {
    const currently = status.classList.contains('is-running')
    toggle.disabled = true
    try {
      await controlRequestJson('/control', { method: 'POST', body: JSON.stringify({ running: !currently }) })
      await refresh()
    } catch (error) {
      status.textContent = `操作失败：${error instanceof Error ? error.message : String(error)}`
      toggle.disabled = false
    }
  }

  const openPairing = async (): Promise<void> => {
    pair.disabled = true
    try {
      const result = await controlRequestJson('/pairing/open', { method: 'POST', body: '{}' }) as { pairUrl?: string; appKey?: string; qrSvg?: string }
      if (result.pairUrl !== undefined) {
        const link = element('a', 'amw-control__link')
        link.href = String(result.pairUrl)
        link.target = '_blank'
        link.rel = 'noopener noreferrer'
        link.textContent = String(result.pairUrl)
        panel.append(link)
        window.navigator.clipboard?.writeText(String(result.pairUrl)).catch(() => undefined)
      }
      if (result.appKey !== undefined) {
        const key = element('p', 'amw-control__status')
        key.textContent = `App 配对密钥：${String(result.appKey)}`
        panel.append(key)
      }
      if (typeof result.qrSvg === 'string' && result.qrSvg.length > 0) {
        const qr = element('img')
        qr.src = `data:image/svg+xml;base64,${btoa(result.qrSvg)}`
        qr.style.width = '140px'
        qr.style.borderRadius = '10px'
        qr.style.background = '#fff'
        qr.style.padding = '6px'
        panel.append(qr)
      }
    } catch (error) {
      status.textContent = `配对失败：${error instanceof Error ? error.message : String(error)}`
    } finally {
      pair.disabled = false
    }
  }

  const listDevices = async (): Promise<void> => {
    try {
      const result = await controlRequestJson('/devices') as { devices?: { id: string; label: string; lastSeenAt: number }[] }
      devicesBox.replaceChildren()
      const devices = result.devices ?? []
      if (devices.length === 0) {
        devicesBox.append(element('p', 'amw-control__status').also(it => { it.textContent = '暂无配对设备' }))
        return
      }
      for (const device of devices) {
        const row = element('div', 'amw-control__device')
        const label = element('span'); label.style.flex = '1'; label.textContent = device.label
        const revoke = element('button'); revoke.textContent = '撤销'
        revoke.addEventListener('click', () => {
          void controlRequestJson('/devices/revoke', { method: 'POST', body: JSON.stringify({ deviceId: device.id }) })
            .then(listDevices)
            .catch(error => { status.textContent = `撤销失败：${error instanceof Error ? error.message : String(error)}` })
        })
        row.append(label, revoke)
        devicesBox.append(row)
      }
    } catch (error) {
      devicesBox.replaceChildren()
      devicesBox.append(element('p', 'amw-control__status').also(it => { it.textContent = `读取设备失败: ${error instanceof Error ? error.message : String(error)}` }))
    }
  }

  panel.append(status)
  const row1 = element('div', 'amw-control__row')
  toggle.addEventListener('click', () => void toggleGateway())
  row1.append(toggle)
  const row2 = element('div', 'amw-control__row')
  pair.textContent = '生成配对'
  pair.addEventListener('click', () => void openPairing())
  row2.append(pair)
  panel.append(row1, row2, devicesBox)
  root.append(panel)

  const open = (): void => {
    panel.hidden = false
    void refresh().catch(error => { status.textContent = `读取失败：${error instanceof Error ? error.message : String(error)}` })
    void listDevices()
  }
  const close = (): void => { panel.hidden = true }
  const toggleOpen = (): void => { if (panel.hidden) open(); else close() }

  return { root, toggle: toggleOpen, remove: () => root.remove() }
}

/** Mount the desktop Amadeus control. */
export function apply(ctx: ClientContext): void {
  ctx.effect(() => {
    const style = element('style'); style.dataset.plugin = 'dsh-amadeus'
    const isLoopback = location.hostname === 'localhost' || location.hostname === '127.0.0.1' || location.hostname === '::1'
    style.textContent = isLoopback ? STYLES : ''
    document.head.append(style)

    const control = installControl()
    document.body.append(control.root)
    const disposeSlot = ctx.slots.inject('sidebar.footer.action', () => ctx.slots.register<{ wide: boolean }>({ name: 'sidebar.footer.action', id: 'dsh-amadeus' }, ({ wide }) => {
      const children: (Node | string)[] = [element('span', 'amw-control__trigger-icon')]
      if (wide) children.push(element('span', 'amw-control__trigger-label').also(it => { it.textContent = 'Amadeus' }))
      return createElement('button', {
        'aria-expanded': false,
        className: `amw-control__trigger${wide ? '' : ' is-rail'}`,
        title: 'Amadeus',
        type: 'button',
        onClick: control.toggle,
      }, ...children)
    }))

    return () => {
      disposeSlot()
      style.remove()
    }
  }, 'dsh-amadeus: desktop control')
}

/** Client services required. */
export const inject: readonly string[] = ['slots']

// tiny also() helper
declare global {
  interface HTMLElement { also(block: (it: HTMLElement) => void): HTMLElement }
}
Object.defineProperty(HTMLElement.prototype, 'also', {
  value(this: HTMLElement, block: (it: HTMLElement) => void): HTMLElement { block(this); return this },
})
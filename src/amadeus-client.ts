/**
 * Amadeus Whale — 桌面控制面板（设置页）。
 * 完整控制：局域网开关 / 配对 / 设备管理 + 远程通道（Tailscale Funnel / cpolar / 自建 FRP）。
 * 完全独立于 dsh-mobile，只走 /api/amadeus。
 */

import { createElement as h } from 'react'

interface ClientContext {
  effect(effect: () => void | (() => void), label?: string): void
  get(name: string): unknown
  slots: {
    inject(key: string, callback: () => (() => void)): () => void
    register<Props>(options: { name: string; id: string; order?: number; label?: string | (() => string) }, component: (props: Props) => unknown): () => void
  }
}

const BASE = '/api/amadeus'
const CONTROL_REQUEST_TIMEOUT_MS = 15_000
const LONG_CONTROL_REQUEST_TIMEOUT_MS = 130_000

function element<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag)
  if (className !== undefined) node.className = className
  return node
}

/** Fetch with abort timeout and JSON error surface (mirrors dsh-mobile requestJson). */
async function requestJson(
  url: string,
  init?: RequestInit,
  timeoutMs = CONTROL_REQUEST_TIMEOUT_MS,
): Promise<Record<string, unknown>> {
  const controller = new AbortController()
  const upstreamSignal = init?.signal
  const abortFromUpstream = (): void => { controller.abort(upstreamSignal?.reason) }
  if (upstreamSignal?.aborted === true) abortFromUpstream()
  else upstreamSignal?.addEventListener('abort', abortFromUpstream, { once: true })
  const timer = window.setTimeout(() => { controller.abort() }, timeoutMs)
  try {
    const response = await fetch(url, {
      ...init,
      signal: controller.signal,
      headers: { 'content-type': 'application/json', ...init?.headers },
    })
    const text = await response.text()
    let body: Record<string, unknown>
    try {
      body = text === '' ? {} : JSON.parse(text) as Record<string, unknown>
    } catch {
      // Non-JSON body (e.g. an empty 4xx from the webserver): surface status.
      throw new Error(`HTTP ${String(response.status)}`)
    }
    if (!response.ok) throw new Error(typeof body.error === 'string' ? body.error : `HTTP ${String(response.status)}`)
    return body
  } catch (error) {
    if (controller.signal.aborted && upstreamSignal?.aborted !== true) {
      throw new Error('操作超时，请确认 DSH 仍在运行后重试。')
    }
    throw error
  } finally {
    clearTimeout(timer)
    upstreamSignal?.removeEventListener('abort', abortFromUpstream)
  }
}

function formatTime(ms: unknown): string {
  return typeof ms === 'number' ? new Date(ms).toLocaleString('zh-CN') : ''
}

function formatMegabytes(bytes: number): string {
  return new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(bytes / 1024 / 1024)
}

/** Restricted frps + Caddy template copied to the VPS (mirrors frp-template.ts). */
export function createFrpServerTemplateForClipboard(serverPort: number, token: string, publicOrigin: string): string {
  if (!Number.isSafeInteger(serverPort) || serverPort < 1 || serverPort > 65_535
    || token.length < 16 || token.length > 512 || /[\s\u0000-\u001f\u007f]/u.test(token)) {
    throw new Error('frp_template_input_invalid')
  }
  let url: URL
  try { url = new URL(publicOrigin) } catch { throw new Error('frp_template_input_invalid') }
  if (url.protocol !== 'https:' || url.port !== '' || url.pathname !== '/' || url.search !== '' || url.hash !== ''
    || url.username !== '' || url.password !== '' || !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/u.test(url.hostname)) {
    throw new Error('frp_template_input_invalid')
  }
  return [
    '# frps.toml',
    `bindPort = ${String(serverPort)}`,
    'proxyBindAddr = "127.0.0.1"',
    'vhostHTTPPort = 7080',
    'auth.method = "token"',
    `auth.token = ${JSON.stringify(token)}`,
    '',
    '# Caddyfile',
    `${url.hostname} {`,
    '  reverse_proxy 127.0.0.1:7080',
    '}',
    '',
  ].join('\n')
}

const STYLES = `
.amw-section-mount{box-sizing:border-box;width:100%;max-width:720px;font:13px/1.5 system-ui;color:var(--dsw-alias-label-primary)}
.amw-section__title{margin:0 0 6px;font-size:18px;font-weight:600;line-height:26px}
.amw-control__header{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:10px}
.amw-control__switcher{display:inline-flex;gap:2px;margin:0 0 14px;padding:2px;border-radius:999px;background:var(--dsw-alias-bg-module-platform)}
.amw-control__tab{min-height:30px;padding:0 14px;border:0;border-radius:999px;background:transparent;color:var(--dsw-alias-label-secondary);font:500 13px/1 system-ui;cursor:pointer}
.amw-control__tab:hover{color:var(--dsw-alias-label-primary)}
.amw-control__tab.is-active{background:var(--dsw-alias-bg-layer-2);color:var(--dsw-alias-label-primary);box-shadow:0 1px 2px rgb(15 23 42 / 8%)}
.amw-control__view[hidden]{display:none}
.amw-control__intro{margin:0 0 14px;color:var(--dsw-alias-label-tertiary);font-size:13px;line-height:1.55}
.amw-control__access{display:flex;align-items:baseline;gap:6px;min-width:0;margin:0 0 12px}
.amw-control__access[hidden]{display:none}
.amw-control__access-label{flex:none;color:var(--dsw-alias-label-secondary);white-space:nowrap}
.amw-control__access-label::after{content:"："}
.amw-control__access-link{min-width:0;overflow:hidden;color:var(--dsw-alias-label-primary);text-decoration:none;text-overflow:ellipsis;white-space:nowrap}
.amw-control__access-link:hover{text-decoration:underline}
.amw-control__qr{display:flex;justify-content:center;margin:0 0 12px}
.amw-control__qr[hidden]{display:none}
.amw-control__qr img{border-radius:12px;background:var(--dsw-alias-bg-layer-2);padding:8px}
.amw-control__status{margin:0 0 14px;overflow-wrap:anywhere;color:var(--dsw-alias-label-secondary)}
.amw-control__status::before{display:inline-block;width:8px;height:8px;margin-right:7px;border-radius:50%;background:var(--dsw-alias-label-dimmed);content:""}
.amw-control__status.is-running::before{background:var(--dsw-alias-label-primary)}
.amw-control__status.is-key{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;word-break:break-all}
.amw-control__extensions{margin:0 0 12px;color:var(--dsw-alias-label-secondary);font-size:12px}
.amw-control__actions{display:flex;flex-wrap:nowrap;gap:6px}
.amw-control__actions button{flex:1 1 0;min-width:0;min-height:40px;padding:8px 4px;border-radius:12px;font:12px/1.2 system-ui;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.amw-control__actions button:disabled{cursor:not-allowed;opacity:.45}
.amw-control__secondary{border:1px solid var(--dsw-alias-border-l2);background:transparent;color:inherit}
.amw-control__primary{border:1px solid transparent;background:var(--dsw-alias-label-primary);color:var(--dsw-alias-bg-layer-2)}
.amw-control__manage-row{display:flex;justify-content:space-between;gap:8px;margin-top:10px}
.amw-control__manage{flex:1 1 0;min-width:0;min-height:34px;padding:6px 8px;border:1px solid var(--dsw-alias-border-l2);border-radius:12px;background:transparent;color:inherit;font:12px/1.3 system-ui;cursor:pointer}
.amw-control__devices{margin-top:10px;border:1px solid var(--dsw-alias-border-l1);border-radius:12px;padding:8px;max-height:220px;overflow-y:auto}
.amw-control__devices[hidden]{display:none}
.amw-control__device-empty{color:var(--dsw-alias-label-secondary);font-size:12px;margin:0}
.amw-control__device{display:flex;align-items:center;gap:8px;padding:6px 2px}
.amw-control__device + .amw-control__device{border-top:1px solid var(--dsw-alias-border-l1)}
.amw-control__device-label{flex:1 1 0;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}
.amw-control__device-meta{flex:none;color:var(--dsw-alias-label-secondary);font-size:11px;white-space:nowrap}
.amw-control__device-revoke{flex:none;min-height:28px;padding:4px 8px;border:1px solid var(--dsw-alias-state-error-primary);border-radius:10px;background:transparent;color:var(--dsw-alias-state-error-primary);font:12px/1.2 system-ui;cursor:pointer}
.amw-control__provider-section{position:relative;margin:0 0 14px}
.amw-control__section-title{margin:0 0 8px;color:var(--dsw-alias-label-primary);font:600 13px/1.4 system-ui}
.amw-control__provider-choices{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
.amw-control__provider{display:flex;min-width:0;flex-direction:column;gap:6px;min-height:94px;padding:10px 11px;border:1px solid var(--dsw-alias-border-l1);border-radius:12px;background:var(--dsw-alias-bg-layer-2);color:var(--dsw-alias-label-primary);text-align:left;cursor:pointer;transition:border-color 160ms ease,background-color 160ms ease,box-shadow 160ms ease}
.amw-control__provider:hover{border-color:var(--dsw-alias-label-dimmed);background:var(--dsw-alias-interactive-bg-hover-solid,var(--dsw-alias-bg-layer-1))}
.amw-control__provider.is-selected{border-color:var(--dsw-alias-label-primary);background:var(--dsw-alias-bg-layer-2);box-shadow:0 0 0 1px var(--dsw-alias-label-primary) inset}
.amw-control__provider:disabled{cursor:wait;opacity:.62}
.amw-control__provider-top{display:flex;min-width:0;align-items:flex-start;justify-content:space-between;gap:5px}
.amw-control__provider-top strong{min-width:0;font-size:12px;line-height:1.3}
.amw-control__provider-badge{flex:none;padding:2px 5px;border-radius:999px;background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary);font:600 9px/1.25 system-ui}
.amw-control__provider-badge.is-cpolar{background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary)}
.amw-control__provider-badge.is-frp{background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary)}
.amw-control__provider-description{color:var(--dsw-alias-label-secondary);font-size:10px;line-height:1.45}
.amw-control__self-hosted{margin:8px 0 0;border:1px solid var(--dsw-alias-border-l1);border-radius:12px;background:var(--dsw-alias-bg-layer-1)}
.amw-control__self-hosted-summary{display:flex;box-sizing:border-box;min-height:48px;align-items:center;justify-content:space-between;gap:10px;padding:8px 11px;cursor:pointer;list-style-position:inside}
.amw-control__self-hosted-summary>span:first-child{display:flex;min-width:0;flex-direction:column;gap:1px}
.amw-control__self-hosted-summary strong{font-size:11px}
.amw-control__self-hosted-summary span span{color:var(--dsw-alias-label-secondary);font-size:9px;line-height:1.35}
.amw-control__self-hosted-body{padding:0 8px 8px}
.amw-control__provider.is-frp{width:100%;min-height:64px;background:var(--dsw-alias-bg-layer-2)}
.amw-control__cpolar-setup{margin:0 0 12px;padding:12px;border:1px solid var(--dsw-alias-border-l1);border-radius:12px;background:var(--dsw-alias-bg-layer-2)}
.amw-control__cpolar-setup[hidden],.amw-control__cpolar-account[hidden],.amw-control__details[hidden],.amw-control__danger[hidden]{display:none}
.amw-control__component-status,.amw-control__component-note{margin:0 0 10px;color:var(--dsw-alias-label-secondary);font-size:11px;line-height:1.55}
.amw-control__cpolar-setup>.amw-control__primary{width:100%;min-height:44px;padding:9px 12px;border-radius:12px;font:600 12px/1.3 system-ui;cursor:pointer}
.amw-control__cpolar-account{margin-top:10px}
.amw-control__link-row{display:flex;flex-wrap:wrap;gap:6px 12px;margin:0 0 10px}
.amw-control__text-link{color:var(--dsw-alias-label-primary);font-size:11px;text-decoration:none}
.amw-control__text-link:hover{text-decoration:underline}
.amw-control__token-label{display:flex;flex-direction:column;gap:5px;margin:0 0 8px;color:var(--dsw-alias-label-secondary);font-size:11px}
.amw-control__token{box-sizing:border-box;width:100%;min-height:44px;padding:9px 10px;border:1px solid var(--dsw-alias-border-l2);border-radius:12px;background:var(--dsw-alias-bg-layer-3,var(--dsw-alias-bg-layer-2));color:var(--dsw-alias-label-primary);font:16px/1.4 system-ui}
.amw-control__cpolar-connect{display:flex;align-items:center;justify-content:center;box-sizing:border-box;width:100%;min-height:44px;padding:10px 14px;border-radius:12px;font:600 13px/1.2 system-ui;cursor:pointer}
.amw-control__cpolar-connect:hover:not(:disabled){background:var(--dsw-alias-label-primary)}
.amw-control__cpolar-connect:disabled{cursor:wait;opacity:.55}
.amw-control__details{margin:10px 0 0;border-top:1px solid var(--dsw-alias-border-l1);padding-top:9px}
.amw-control__details>summary{min-height:30px;color:var(--dsw-alias-label-secondary);font-size:11px;line-height:30px;cursor:pointer}
.amw-control__details-body{display:flex;flex-wrap:wrap;align-items:center;gap:7px 12px;padding:4px 0}
.amw-control__details-body p{flex:1 0 100%;margin:0;color:var(--dsw-alias-label-secondary);font-size:11px;line-height:1.5}
.amw-control__storage{display:block;flex:1 0 100%;max-width:100%;overflow:hidden;padding:7px 8px;border-radius:10px;background:var(--dsw-alias-bg-layer-1);color:var(--dsw-alias-label-secondary);font:10px/1.4 ui-monospace,SFMono-Regular,Consolas,monospace;text-overflow:ellipsis;white-space:nowrap}
.amw-control__danger{flex:1 0 100%;min-height:38px;margin-top:3px;padding:7px 10px;border:1px solid var(--dsw-alias-state-error-primary);border-radius:12px;background:transparent;color:var(--dsw-alias-state-error-primary);font:12px/1.3 system-ui;cursor:pointer}
.amw-control__frp-setup{margin:0;padding:12px;border:1px solid var(--dsw-alias-border-l1);border-radius:12px;background:var(--dsw-alias-bg-layer-2)}
.amw-control__frp-setup[hidden]{display:none}
.amw-control__frp-step{padding:11px 0}
.amw-control__frp-step + .amw-control__frp-step{border-top:1px solid var(--dsw-alias-border-l1)}
.amw-control__frp-step>strong{display:block;margin-bottom:3px;font-size:12px;line-height:1.4}
.amw-control__frp-step>p{margin:0 0 9px;color:var(--dsw-alias-label-secondary);font-size:11px;line-height:1.5}
.amw-control__frp-fields{display:grid;grid-template-columns:minmax(0,1fr) 96px;gap:8px}
.amw-control__field{display:flex;min-width:0;flex-direction:column;gap:5px;color:var(--dsw-alias-label-secondary);font-size:11px}
.amw-control__field:nth-child(3),.amw-control__field:nth-child(4){grid-column:1/-1}
.amw-control__field input{box-sizing:border-box;width:100%;min-height:44px;padding:9px 10px;border:1px solid var(--dsw-alias-border-l2);border-radius:12px;background:var(--dsw-alias-bg-layer-2);color:var(--dsw-alias-label-primary);font:16px/1.4 system-ui}
.amw-control__frp-action{box-sizing:border-box;width:100%;min-height:44px;padding:9px 12px;border-radius:12px;font:600 12px/1.3 system-ui;cursor:pointer}
.amw-control__frp-action:disabled{cursor:not-allowed;opacity:.5}
.amw-control__remote-workspace{margin:0;padding:12px;border:1px solid var(--dsw-alias-border-l1);border-radius:12px;background:var(--dsw-alias-bg-layer-1)}
.amw-control__stage-header{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:9px}
.amw-control__stage-header .amw-control__section-title{margin:0}
.amw-control__stage-meta{display:flex;min-width:0;align-items:center;justify-content:flex-end;gap:5px}
.amw-control__stage-value{max-width:115px;overflow:hidden;color:var(--dsw-alias-label-primary);font:600 10px/1.3 system-ui;text-overflow:ellipsis;white-space:nowrap}
.amw-control__state-badge{flex:none;padding:3px 7px;border-radius:999px;background:var(--dsw-alias-bg-layer-2);color:var(--dsw-alias-label-secondary);font:600 9px/1.25 system-ui}
.amw-control__state-badge.is-ready{background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary)}
.amw-control__state-badge.is-busy{background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary)}
.amw-control__state-badge.is-attention{background:var(--dsw-alias-bg-module-platform);color:var(--dsw-alias-label-secondary)}
.amw-control__remote-workspace>.amw-control__status{box-sizing:border-box;margin:0 0 10px;padding:9px 10px;border-radius:12px;background:var(--dsw-alias-bg-layer-2);font-size:11px;line-height:1.45}
.amw-control__provider-setup-body{margin:0 0 10px}
.amw-control__provider-setup-body>.amw-control__cpolar-setup{margin:0}
.amw-control__provider-setup-body>.amw-control__cpolar-setup>.amw-control__section-title,.amw-control__provider-setup-body>.amw-control__frp-setup>.amw-control__section-title{display:none}
.amw-control__remote-workspace>.amw-control__actions{margin-top:2px}
.amw-control__remote-workspace>.amw-control__qr{margin:10px 0 0}
.amw-control__remote-workspace>.amw-control__manage-row{margin-top:10px;padding-top:10px;border-top:1px solid var(--dsw-alias-border-l1)}
.amw-control button:focus-visible,.amw-control a:focus-visible,.amw-control input:focus-visible,.amw-control summary:focus-visible{outline:3px solid var(--dsw-alias-border-l3);outline-offset:2px}
@media (max-width:359px){.amw-control__provider-choices{grid-template-columns:1fr}.amw-control__provider{min-height:68px}}
`

/** Build the settings-page section tree (command-mode panel, React-mounted). */
function installSection(): { root: HTMLElement; refresh: () => Promise<void>; remove(): void } {
  const lifecycle = new AbortController()
  const controlRequestJson = (path: string, init?: RequestInit, timeoutMs?: number): Promise<Record<string, unknown>> =>
    requestJson(`${BASE}${path}`, { ...init, signal: lifecycle.signal }, timeoutMs)

  const root = element('div', 'amw-section-mount')
  const title = element('h2', 'amw-section__title'); title.textContent = 'Amadeus 网关'
  const switcher = element('div', 'amw-control__switcher')
  const lanTab = element('button', 'amw-control__tab is-active'); lanTab.type = 'button'; lanTab.textContent = '局域网'
  const remoteTab = element('button', 'amw-control__tab'); remoteTab.type = 'button'; remoteTab.textContent = '远程'
  lanTab.setAttribute('aria-pressed', 'true'); remoteTab.setAttribute('aria-pressed', 'false')
  switcher.append(lanTab, remoteTab)

  // ── LAN view ──────────────────────────────────────────────────────────────
  const lanView = element('div', 'amw-control__view')
  const access = element('div', 'amw-control__access'); access.hidden = true
  const accessLabel = element('span', 'amw-control__access-label'); accessLabel.textContent = '浏览器访问'
  const accessLink = element('a', 'amw-control__access-link'); accessLink.target = '_blank'; accessLink.rel = 'noreferrer'
  access.append(accessLabel, accessLink)
  const qrBox = element('div', 'amw-control__qr'); qrBox.hidden = true
  const status = element('p', 'amw-control__status'); status.textContent = '正在读取状态…'
  const extensionStatus = element('p', 'amw-control__extensions'); extensionStatus.hidden = true
  const actions = element('div', 'amw-control__actions')
  const toggle = element('button', 'amw-control__secondary'); toggle.type = 'button'
  const pair = element('button', 'amw-control__primary'); pair.type = 'button'; pair.textContent = '生成并复制密钥'
  const linkPair = element('button', 'amw-control__secondary'); linkPair.type = 'button'; linkPair.textContent = '复制配对链接'
  actions.append(toggle, pair, linkPair)
  const manageRow = element('div', 'amw-control__manage-row')
  const manageDevices = element('button', 'amw-control__manage'); manageDevices.type = 'button'; manageDevices.textContent = '管理配对设备'
  const resetAll = element('button', 'amw-control__manage'); resetAll.type = 'button'; resetAll.textContent = '清除所有设备'
  manageRow.append(manageDevices, resetAll)
  const devicePanel = element('div', 'amw-control__devices'); devicePanel.hidden = true
  lanView.append(access, qrBox, status, extensionStatus, actions, manageRow, devicePanel)

  // ── Remote view: provider selection ───────────────────────────────────────
  const remoteView = element('div', 'amw-control__view'); remoteView.hidden = true
  const remoteIntro = element('p', 'amw-control__intro')
  remoteIntro.textContent = '选择更适合你的远程通道。切换或关闭远程访问不会影响局域网。'
  const providerSection = element('section', 'amw-control__provider-section')
  const providerHeading = element('h3', 'amw-control__section-title'); providerHeading.textContent = '选择连接方式'
  const providerChoices = element('div', 'amw-control__provider-choices'); providerChoices.setAttribute('role', 'radiogroup')

  const tailscaleChoice = element('button', 'amw-control__provider'); tailscaleChoice.type = 'button'; tailscaleChoice.setAttribute('role', 'radio'); tailscaleChoice.setAttribute('aria-checked', 'true')
  const tailscaleTop = element('span', 'amw-control__provider-top')
  const tailscaleName = element('strong'); tailscaleName.textContent = 'Tailscale Funnel'
  const tailscaleBadge = element('span', 'amw-control__provider-badge'); tailscaleBadge.textContent = '内置'
  const tailscaleDesc = element('span', 'amw-control__provider-description'); tailscaleDesc.textContent = '覆盖更广；中国大陆网络可能不稳定，首次需登录并允许 Funnel。'
  tailscaleTop.append(tailscaleName, tailscaleBadge); tailscaleChoice.append(tailscaleTop, tailscaleDesc)

  const cpolarChoice = element('button', 'amw-control__provider'); cpolarChoice.type = 'button'; cpolarChoice.setAttribute('role', 'radio'); cpolarChoice.setAttribute('aria-checked', 'false')
  const cpolarTop = element('span', 'amw-control__provider-top')
  const cpolarName = element('strong'); cpolarName.textContent = 'cpolar'
  const cpolarBadge = element('span', 'amw-control__provider-badge is-cpolar'); cpolarBadge.textContent = '国内网络优先'
  const cpolarDesc = element('span', 'amw-control__provider-description'); cpolarDesc.textContent = '按需安装官方组件，适合国内网络环境。'
  cpolarTop.append(cpolarName, cpolarBadge); cpolarChoice.append(cpolarTop, cpolarDesc)
  providerChoices.append(cpolarChoice, tailscaleChoice)

  const selfHosted = element('details', 'amw-control__self-hosted')
  const selfHostedSummary = element('summary', 'amw-control__self-hosted-summary')
  const selfHostedSummaryText = element('span')
  const selfHostedSummaryTitle = element('strong'); selfHostedSummaryTitle.textContent = '自建连接'
  const selfHostedSummaryDescription = element('span'); selfHostedSummaryDescription.textContent = '适合已有 VPS 和域名的用户，不受公共服务带宽限制。'
  const selfHostedBadge = element('span', 'amw-control__provider-badge is-frp'); selfHostedBadge.textContent = '高级'
  selfHostedSummaryText.append(selfHostedSummaryTitle, selfHostedSummaryDescription)
  selfHostedSummary.append(selfHostedSummaryText, selfHostedBadge)
  const selfHostedBody = element('div', 'amw-control__self-hosted-body')
  const frpChoice = element('button', 'amw-control__provider is-frp'); frpChoice.type = 'button'; frpChoice.setAttribute('aria-pressed', 'false')
  const frpChoiceTop = element('span', 'amw-control__provider-top')
  const frpChoiceName = element('strong'); frpChoiceName.textContent = '自建 FRP'
  const frpChoiceDesc = element('span', 'amw-control__provider-description'); frpChoiceDesc.textContent = '只为当前 DSH 网关建立单用途 FRP 通道，VPS 和公开域名由你掌控。'
  frpChoiceTop.append(frpChoiceName); frpChoice.append(frpChoiceTop, frpChoiceDesc)
  selfHostedBody.append(frpChoice); selfHosted.append(selfHostedSummary, selfHostedBody)
  providerSection.append(providerHeading, providerChoices, selfHosted)

  // ── cpolar setup ──────────────────────────────────────────────────────────
  const cpolarSetup = element('section', 'amw-control__cpolar-setup'); cpolarSetup.hidden = true
  const cpolarSetupTitle = element('h3', 'amw-control__section-title'); cpolarSetupTitle.textContent = '准备 cpolar'
  const cpolarComponentStatus = element('p', 'amw-control__component-status'); cpolarComponentStatus.textContent = '正在检查组件…'
  const cpolarInstall = element('button', 'amw-control__primary'); cpolarInstall.type = 'button'; cpolarInstall.textContent = '安装官方组件'
  const cpolarAccount = element('div', 'amw-control__cpolar-account'); cpolarAccount.hidden = true
  const cpolarAccountText = element('p', 'amw-control__component-note')
  cpolarAccountText.textContent = '登录 cpolar 官网后复制 Authtoken。令牌只保存在本机插件私有目录，不会显示在页面或日志中。'
  const cpolarAccountLinks = element('div', 'amw-control__link-row')
  const cpolarSignup = element('a', 'amw-control__text-link'); cpolarSignup.href = 'https://dashboard.cpolar.com/signup'; cpolarSignup.target = '_blank'; cpolarSignup.rel = 'noopener noreferrer'; cpolarSignup.textContent = '注册 cpolar'
  const cpolarDashboard = element('a', 'amw-control__text-link'); cpolarDashboard.href = 'https://dashboard.cpolar.com/auth'; cpolarDashboard.target = '_blank'; cpolarDashboard.rel = 'noopener noreferrer'; cpolarDashboard.textContent = '打开控制台获取令牌'
  cpolarAccountLinks.append(cpolarSignup, cpolarDashboard)
  const cpolarTokenLabel = element('label', 'amw-control__token-label'); cpolarTokenLabel.textContent = 'Authtoken'
  const cpolarToken = element('input', 'amw-control__token'); cpolarToken.type = 'password'; cpolarToken.autocomplete = 'off'; cpolarToken.spellcheck = false; cpolarToken.placeholder = '粘贴 cpolar Authtoken'
  cpolarTokenLabel.append(cpolarToken)
  const cpolarConfigure = element('button', 'amw-control__primary amw-control__cpolar-connect'); cpolarConfigure.type = 'button'; cpolarConfigure.textContent = '保存并连接'
  cpolarAccount.append(cpolarAccountText, cpolarAccountLinks, cpolarTokenLabel, cpolarConfigure)
  const cpolarDetails = element('details', 'amw-control__details')
  const cpolarDetailsSummary = element('summary'); cpolarDetailsSummary.textContent = '组件来源与清理说明'
  const cpolarDetailsBody = element('div', 'amw-control__details-body')
  const cpolarDetailsText = element('p')
  cpolarDetailsText.textContent = '仅在你点击安装后从 cpolar 官网下载并校验固定版本。不会写入系统服务、开机启动、注册表或 PATH。'
  const cpolarStorage = element('code', 'amw-control__storage'); cpolarStorage.textContent = '插件私有目录'
  const cpolarOfficial = element('a', 'amw-control__text-link'); cpolarOfficial.href = 'https://www.cpolar.com/download'; cpolarOfficial.target = '_blank'; cpolarOfficial.rel = 'noopener noreferrer'; cpolarOfficial.textContent = '官方下载安装页'
  const cpolarTerms = element('a', 'amw-control__text-link'); cpolarTerms.href = 'https://www.cpolar.com/tos'; cpolarTerms.target = '_blank'; cpolarTerms.rel = 'noopener noreferrer'; cpolarTerms.textContent = '服务条款'
  const cpolarPurge = element('button', 'amw-control__danger'); cpolarPurge.type = 'button'; cpolarPurge.textContent = '彻底移除 cpolar 组件与配置'
  cpolarDetailsBody.append(cpolarDetailsText, cpolarStorage, cpolarOfficial, cpolarTerms, cpolarPurge)
  cpolarDetails.append(cpolarDetailsSummary, cpolarDetailsBody)
  cpolarSetup.append(cpolarSetupTitle, cpolarComponentStatus, cpolarInstall, cpolarAccount, cpolarDetails)

  // ── FRP setup (4 steps) ───────────────────────────────────────────────────
  const frpSetup = element('section', 'amw-control__frp-setup'); frpSetup.hidden = true
  const frpSetupTitle = element('h3', 'amw-control__section-title'); frpSetupTitle.textContent = '配置自建 FRP'
  const frpStep1 = element('section', 'amw-control__frp-step')
  const frpStep1Title = element('strong'); frpStep1Title.textContent = '1 · 填写连接信息'
  const frpStep1Text = element('p'); frpStep1Text.textContent = '填写 VPS 地址、frps 控制端口、共享 Token 和公开 HTTPS 地址。'
  const frpFields = element('div', 'amw-control__frp-fields')
  const frpServerLabel = element('label', 'amw-control__field'); frpServerLabel.textContent = 'VPS 地址'
  const frpServer = element('input'); frpServer.type = 'text'; frpServer.autocomplete = 'off'; frpServer.spellcheck = false; frpServer.placeholder = 'frp.example.com'
  const frpPortLabel = element('label', 'amw-control__field'); frpPortLabel.textContent = 'frps 端口'
  const frpPort = element('input'); frpPort.type = 'number'; frpPort.inputMode = 'numeric'; frpPort.min = '1'; frpPort.max = '65535'; frpPort.value = '7000'
  const frpTokenLabel = element('label', 'amw-control__field'); frpTokenLabel.textContent = '共享 Token'
  const frpToken = element('input'); frpToken.type = 'password'; frpToken.autocomplete = 'off'; frpToken.spellcheck = false; frpToken.placeholder = '至少 16 个字符'
  const frpOriginLabel = element('label', 'amw-control__field'); frpOriginLabel.textContent = '公开 HTTPS 地址'
  const frpOrigin = element('input'); frpOrigin.type = 'url'; frpOrigin.autocomplete = 'off'; frpOrigin.spellcheck = false; frpOrigin.placeholder = 'https://dsh.example.com'
  frpServerLabel.append(frpServer); frpPortLabel.append(frpPort); frpTokenLabel.append(frpToken); frpOriginLabel.append(frpOrigin)
  frpFields.append(frpServerLabel, frpPortLabel, frpTokenLabel, frpOriginLabel)
  frpStep1.append(frpStep1Title, frpStep1Text, frpFields)
  const frpStep2 = element('section', 'amw-control__frp-step')
  const frpStep2Title = element('strong'); frpStep2Title.textContent = '2 · 准备 VPS'
  const frpStep2Text = element('p'); frpStep2Text.textContent = '复制受限的 frps 与 Caddy 模板，并在 VPS 上应用。'
  const frpCopyTemplate = element('button', 'amw-control__secondary amw-control__frp-action'); frpCopyTemplate.type = 'button'; frpCopyTemplate.textContent = '复制服务器模板'
  frpStep2.append(frpStep2Title, frpStep2Text, frpCopyTemplate)
  const frpStep3 = element('section', 'amw-control__frp-step')
  const frpStep3Title = element('strong'); frpStep3Title.textContent = '3 · 准备这台电脑'
  const frpStep3Text = element('p'); frpStep3Text.textContent = '仅在你确认后下载并校验固定版本的官方 frpc。'
  const frpComponentStatus = element('p', 'amw-control__component-status'); frpComponentStatus.textContent = '正在检查组件…'
  const frpInstall = element('button', 'amw-control__primary amw-control__frp-action'); frpInstall.type = 'button'; frpInstall.textContent = '安装官方 frpc'
  frpStep3.append(frpStep3Title, frpStep3Text, frpComponentStatus, frpInstall)
  const frpStep4 = element('section', 'amw-control__frp-step')
  const frpStep4Title = element('strong'); frpStep4Title.textContent = '4 · 验证并连接'
  const frpStep4Text = element('p'); frpStep4Text.textContent = '插件只启动 DSH 的 HTTP vhost，并确认公网端点确实属于这台电脑。'
  const frpConfigurationStatus = element('p', 'amw-control__component-status'); frpConfigurationStatus.textContent = '服务器配置未保存'
  const frpConfigure = element('button', 'amw-control__primary amw-control__frp-action'); frpConfigure.type = 'button'; frpConfigure.textContent = '保存并验证连接'
  frpStep4.append(frpStep4Title, frpStep4Text, frpConfigurationStatus, frpConfigure)
  const frpDetails = element('details', 'amw-control__details')
  const frpDetailsSummary = element('summary'); frpDetailsSummary.textContent = 'FRP 来源与彻底清理'
  const frpDetailsBody = element('div', 'amw-control__details-body')
  const frpDetailsText = element('p')
  frpDetailsText.textContent = '固定版本客户端来自 fatedier/frp 官方 Release。DSH Amadeus 不安装系统服务，也不接受任意 FRP 配置。'
  const frpStorage = element('code', 'amw-control__storage'); frpStorage.textContent = '插件私有目录'
  const frpOfficial = element('a', 'amw-control__text-link'); frpOfficial.href = 'https://github.com/fatedier/frp/releases/tag/v0.70.1'; frpOfficial.target = '_blank'; frpOfficial.rel = 'noopener noreferrer'; frpOfficial.textContent = 'FRP 官方 Release'
  const frpPurge = element('button', 'amw-control__danger'); frpPurge.type = 'button'; frpPurge.textContent = '彻底移除 FRP 组件与私有配置'
  frpDetailsBody.append(frpDetailsText, frpStorage, frpOfficial, frpPurge)
  frpDetails.append(frpDetailsSummary, frpDetailsBody)
  frpSetup.append(frpSetupTitle, frpStep1, frpStep2, frpStep3, frpStep4, frpDetails)


  const tailscaleInfo = element('details', 'amw-control__details')
  const tailscaleInfoSummary = element('summary'); tailscaleInfoSummary.textContent = 'Tailscale 使用说明'
  const tailscaleInfoBody = element('div', 'amw-control__details-body')
  const tailscaleInfoText = element('p')
  tailscaleInfoText.textContent = '运行组件已随插件提供。首次连接会打开 Tailscale 官方登录和 Funnel 授权页；插件不会接触你的账号密码。'
  tailscaleInfoBody.append(tailscaleInfoText); tailscaleInfo.append(tailscaleInfoSummary, tailscaleInfoBody)

  // ── Remote workspace: current provider + live status ──────────────────────
  const providerSetupHeader = element('div', 'amw-control__stage-header')
  const providerSetupHeading = element('h3', 'amw-control__section-title'); providerSetupHeading.textContent = '当前连接'
  const providerSetupName = element('span', 'amw-control__stage-value'); providerSetupName.textContent = 'Tailscale Funnel'
  const remoteStateBadge = element('span', 'amw-control__state-badge'); remoteStateBadge.textContent = '未启用'
  const providerSetupMeta = element('div', 'amw-control__stage-meta'); providerSetupMeta.append(providerSetupName, remoteStateBadge)
  providerSetupHeader.append(providerSetupHeading, providerSetupMeta)
  const providerSetupBody = element('div', 'amw-control__provider-setup-body')
  providerSetupBody.append(cpolarSetup, frpSetup, tailscaleInfo)
  const remoteAccess = element('div', 'amw-control__access'); remoteAccess.hidden = true
  const remoteAccessLabel = element('span', 'amw-control__access-label'); remoteAccessLabel.textContent = '远程地址'
  const remoteAccessLink = element('a', 'amw-control__access-link'); remoteAccessLink.target = '_blank'; remoteAccessLink.rel = 'noreferrer'
  remoteAccess.append(remoteAccessLabel, remoteAccessLink)
  const remoteQr = element('div', 'amw-control__qr'); remoteQr.hidden = true
  const remoteStatus = element('p', 'amw-control__status'); remoteStatus.textContent = '正在读取远程状态…'; remoteStatus.setAttribute('aria-live', 'polite')
  const remoteActions = element('div', 'amw-control__actions')
  const remoteToggle = element('button', 'amw-control__primary'); remoteToggle.type = 'button'; remoteToggle.textContent = '启用远程访问'
  const remoteLogin = element('button', 'amw-control__primary'); remoteLogin.type = 'button'; remoteLogin.textContent = '继续登录'; remoteLogin.hidden = true
  const remoteReconnect = element('button', 'amw-control__secondary'); remoteReconnect.type = 'button'; remoteReconnect.textContent = '重新连接'; remoteReconnect.hidden = true
  const remotePair = element('button', 'amw-control__secondary'); remotePair.type = 'button'; remotePair.textContent = '生成远程配对二维码'; remotePair.disabled = true
  remoteActions.append(remoteToggle, remoteLogin, remoteReconnect, remotePair)
  const remoteManageRow = element('div', 'amw-control__manage-row')
  const remoteDevices = element('button', 'amw-control__manage'); remoteDevices.type = 'button'; remoteDevices.textContent = '管理远程设备'; remoteDevices.disabled = true
  const remoteReset = element('button', 'amw-control__manage'); remoteReset.type = 'button'; remoteReset.textContent = '退出并清除远程登录'
  remoteManageRow.append(remoteDevices, remoteReset)
  const remoteDevicePanel = element('div', 'amw-control__devices'); remoteDevicePanel.hidden = true
  const remoteWorkspace = element('section', 'amw-control__remote-workspace')
  remoteWorkspace.append(providerSetupHeader, remoteStatus, remoteAccess, providerSetupBody, remoteActions, remoteQr, remoteManageRow, remoteDevicePanel)
  remoteView.append(remoteIntro, providerSection, remoteWorkspace)
  root.append(title, switcher, lanView, remoteView)

  // ── State ─────────────────────────────────────────────────────────────────
  let running = false
  let origin = ''
  let remoteRunning = false
  let remoteReady = false
  let remoteProvider: 'tailscale' | 'cpolar' | 'frp' = 'tailscale'
  let remoteLoginUrl = ''
  let remoteSetupUrl = ''
  let remoteSetupPending = false
  let remoteSetupOpenedAt = 0
  let remoteReconnectBusy = false
  let remoteProviderBusy = false
  let cpolarInstalled = false
  let cpolarConfigured = false
  let frpInstalled = false
  let frpConfigured = false
  let frpDownloadSize = '14.0'
  let configuredFrpServer = ''
  let configuredFrpPort = 7000
  let configuredFrpOrigin = ''
  let remoteLoadInFlight = false

  const selectView = (view: 'lan' | 'remote'): void => {
    lanView.hidden = view !== 'lan'
    remoteView.hidden = view !== 'remote'
    lanTab.classList.toggle('is-active', view === 'lan')
    remoteTab.classList.toggle('is-active', view === 'remote')
    lanTab.setAttribute('aria-pressed', String(view === 'lan'))
    remoteTab.setAttribute('aria-pressed', String(view === 'remote'))
  }
  lanTab.addEventListener('click', () => { selectView('lan') })
  remoteTab.addEventListener('click', () => { selectView('remote'); loadRemote() })

  const render = (data: Record<string, unknown>): void => {
    running = data.running === true
    origin = running && typeof data.origin === 'string' ? data.origin : ''
    access.hidden = origin === ''
    accessLink.href = origin
    accessLink.textContent = origin
    accessLink.title = origin
    status.classList.toggle('is-running', running)
    status.textContent = running ? '局域网访问已开启。' : '局域网访问已关闭。'
    const extensionData = data.extensions
    if (extensionData !== null && typeof extensionData === 'object') {
      const loaded = typeof (extensionData as { loaded?: unknown }).loaded === 'number' ? (extensionData as { loaded: number }).loaded : 0
      const failed = typeof (extensionData as { failed?: unknown }).failed === 'number' ? (extensionData as { failed: number }).failed : 0
      extensionStatus.hidden = false
      extensionStatus.textContent = failed === 0 ? `扩展：${loaded} 个已加载` : `扩展：${loaded} 个已加载，${failed} 个加载失败`
    } else extensionStatus.hidden = true
    if (!running) qrBox.hidden = true
    toggle.textContent = running ? '关闭局域网访问' : '开启局域网访问'
    pair.disabled = !running
    linkPair.disabled = !running
    manageDevices.disabled = !running
    resetAll.disabled = !running
  }

  const showQr = (svg: string, target: HTMLElement = qrBox): void => {
    target.replaceChildren()
    if (svg === '') { target.hidden = true; return }
    const image = element('img')
    image.alt = '配对二维码'
    image.width = 176
    image.height = 176
    image.src = `data:image/svg+xml;base64,${btoa(svg)}`
    // 强制显示：即使 render() 曾因 running 状态隐藏过 qrBox，也强制恢复
    target.hidden = false
    target.style.display = 'flex'
    target.scrollIntoView({ block: 'nearest' })
    target.append(image)
  }

  const openPairing = (target: 'key' | 'link'): void => {
    void controlRequestJson('/pairing/open', { method: 'POST', body: '{}' }).then(async data => {
      const value = target === 'key'
        ? (typeof data.appKey === 'string' ? data.appKey : '')
        : (typeof data.pairUrl === 'string' ? data.pairUrl : '')
      showQr(typeof data.qrSvg === 'string' ? data.qrSvg : '')
      if (value === '') { status.textContent = '无法生成配对密钥。'; return }
      try {
        await navigator.clipboard.writeText(value)
        status.textContent = target === 'key' ? '配对密钥已复制，请粘贴到 Android App。' : '配对链接已复制，发给手机后 App 粘贴或浏览器打开即可配对。'
      } catch {
        status.textContent = `请复制${target === 'key' ? '配对密钥' : '配对链接'}：${value}`
        status.classList.add('is-key')
      }
    }, error => { status.textContent = `请求失败：${String(error)}` }).finally(() => {
      pair.disabled = !running
      linkPair.disabled = !running
    })
  }

  toggle.addEventListener('click', () => {
    toggle.disabled = true
    void controlRequestJson('/control', { method: 'POST', body: JSON.stringify({ running: !running }) })
      .then(render, error => { status.textContent = `请求失败：${String(error)}` })
      .finally(() => { toggle.disabled = false })
  })
  pair.addEventListener('click', () => { pair.disabled = true; openPairing('key') })
  linkPair.addEventListener('click', () => { linkPair.disabled = true; openPairing('link') })

  const renderDevices = (data: Record<string, unknown>): void => {
    const devices = Array.isArray(data.devices) ? data.devices as Record<string, unknown>[] : []
    devicePanel.replaceChildren()
    if (devices.length === 0) {
      const empty = element('p', 'amw-control__device-empty'); empty.textContent = '暂无配对设备。'
      devicePanel.append(empty)
      return
    }
    for (const device of devices) {
      const row = element('div', 'amw-control__device')
      const label = element('span', 'amw-control__device-label')
      label.textContent = typeof device.label === 'string' ? device.label : '设备'
      const meta = element('span', 'amw-control__device-meta'); meta.textContent = `到期 ${formatTime(device.expiresAt)}`
      const revoke = element('button', 'amw-control__device-revoke'); revoke.type = 'button'; revoke.textContent = '撤销'
      const id = typeof device.id === 'string' ? device.id : ''
      revoke.addEventListener('click', () => {
        void controlRequestJson('/devices/revoke', { method: 'POST', body: JSON.stringify({ deviceId: id }) })
          .then(loadDevices, error => { status.textContent = `请求失败：${String(error)}` })
      })
      row.append(label, meta, revoke)
      devicePanel.append(row)
    }
  }
  const loadDevices = (): void => {
    void controlRequestJson('/devices').then(renderDevices, error => { status.textContent = `请求失败：${String(error)}` })
  }
  manageDevices.addEventListener('click', () => {
    const show = devicePanel.hidden
    devicePanel.hidden = !show
    if (show) loadDevices()
  })
  resetAll.addEventListener('click', () => {
    if (!window.confirm('确定要移除所有配对设备吗？此操作会立即终止已连接设备。')) return
    void controlRequestJson('/devices/reset', { method: 'POST', body: JSON.stringify({ confirm: true }) })
      .then(loadDevices, error => { status.textContent = `请求失败：${String(error)}` })
  })

  const renderRemote = (data: Record<string, unknown>): void => {
    remoteRunning = data.running === true
    remoteProvider = data.provider === 'cpolar' ? 'cpolar' : data.provider === 'frp' ? 'frp' : 'tailscale'
    const cpolar = remoteProvider === 'cpolar'
    const frp = remoteProvider === 'frp'
    const tailscale = remoteProvider === 'tailscale'
    tailscaleChoice.classList.toggle('is-selected', tailscale)
    cpolarChoice.classList.toggle('is-selected', cpolar)
    frpChoice.classList.toggle('is-selected', frp)
    tailscaleChoice.setAttribute('aria-checked', String(tailscale))
    cpolarChoice.setAttribute('aria-checked', String(cpolar))
    frpChoice.setAttribute('aria-pressed', String(frp))
    providerSetupName.textContent = cpolar ? 'cpolar' : frp ? '自建 FRP' : 'Tailscale Funnel'
    tailscaleChoice.disabled = remoteProviderBusy
    cpolarChoice.disabled = remoteProviderBusy
    frpChoice.disabled = remoteProviderBusy
    cpolarSetup.hidden = !cpolar
    frpSetup.hidden = !frp
    if (frp) selfHosted.open = true
    tailscaleInfo.hidden = !tailscale
    remoteReset.textContent = tailscale ? '退出并清除远程登录' : '关闭并清除远程设备'
    const providers = data.providers !== null && typeof data.providers === 'object' ? data.providers as Record<string, unknown> : {}
    const cpolarProvider = providers.cpolar !== null && typeof providers.cpolar === 'object' ? providers.cpolar as Record<string, unknown> : {}
    const component = cpolarProvider.component !== null && typeof cpolarProvider.component === 'object'
      ? cpolarProvider.component as Record<string, unknown>
      : {}
    cpolarInstalled = component.installed === true
    cpolarConfigured = component.configured === true
    cpolarBadge.textContent = cpolarConfigured ? '已就绪' : cpolarInstalled ? '已安装' : '国内网络优先'
    const cpolarSupported = component.supported !== false
    const componentVersion = typeof component.version === 'string' ? component.version : ''
    const componentDownloadBytes = typeof component.downloadBytes === 'number' ? component.downloadBytes : 0
    const componentStorage = typeof component.storagePath === 'string' ? component.storagePath : '插件私有目录'
    cpolarStorage.textContent = componentStorage
    cpolarStorage.title = componentStorage
    cpolarInstall.hidden = cpolarInstalled || !cpolarSupported
    cpolarInstall.textContent = componentDownloadBytes > 0
      ? `安装官方组件 · ${formatMegabytes(componentDownloadBytes)} MB`
      : '安装官方组件'
    cpolarInstall.disabled = remoteProviderBusy
    cpolarAccount.hidden = !cpolarInstalled || cpolarConfigured
    cpolarConfigure.disabled = remoteProviderBusy
    cpolarPurge.hidden = !cpolarInstalled && !cpolarConfigured
    cpolarComponentStatus.textContent = !cpolarSupported
      ? '当前仅支持 Windows x64。你仍可选择内置的 Tailscale Funnel。'
      : !cpolarInstalled
        ? '尚未安装。只有点击下方按钮后，才会从 cpolar 官网下载固定版本。'
        : !cpolarConfigured
          ? `官方组件 ${componentVersion} 已校验，下一步只需保存账号令牌。`
          : `官方组件 ${componentVersion} 与本机账号配置已就绪。`
    const frpProvider = providers.frp !== null && typeof providers.frp === 'object' ? providers.frp as Record<string, unknown> : {}
    const frpComponent = frpProvider.component !== null && typeof frpProvider.component === 'object'
      ? frpProvider.component as Record<string, unknown>
      : {}
    const frpConfiguration = frpProvider.configuration !== null && typeof frpProvider.configuration === 'object'
      ? frpProvider.configuration as Record<string, unknown>
      : {}
    frpInstalled = frpComponent.installed === true
    frpConfigured = frpConfiguration.configured === true
    const frpSupported = frpComponent.supported !== false
    const frpVersion = typeof frpComponent.version === 'string' ? frpComponent.version : ''
    const frpDownloadBytes = typeof frpComponent.downloadBytes === 'number' ? frpComponent.downloadBytes : 0
    if (frpDownloadBytes > 0) frpDownloadSize = formatMegabytes(frpDownloadBytes)
    const frpStoragePath = typeof frpComponent.storagePath === 'string' ? frpComponent.storagePath : '插件私有目录'
    configuredFrpServer = typeof frpConfiguration.serverAddress === 'string' ? frpConfiguration.serverAddress : ''
    configuredFrpPort = typeof frpConfiguration.serverPort === 'number' ? frpConfiguration.serverPort : 7000
    configuredFrpOrigin = typeof frpConfiguration.publicOrigin === 'string' ? frpConfiguration.publicOrigin : ''
    if (frpServer.value === '' && configuredFrpServer !== '') frpServer.value = configuredFrpServer
    if ((frpPort.value === '' || frpPort.value === '7000') && configuredFrpPort !== 7000) frpPort.value = String(configuredFrpPort)
    if (frpOrigin.value === '' && configuredFrpOrigin !== '') frpOrigin.value = configuredFrpOrigin
    frpStorage.textContent = frpStoragePath
    frpStorage.title = frpStoragePath
    frpInstall.hidden = frpInstalled || !frpSupported
    frpInstall.textContent = frpDownloadBytes > 0
      ? `安装官方 frpc · ${formatMegabytes(frpDownloadBytes)} MB`
      : '安装官方 frpc'
    frpInstall.disabled = remoteProviderBusy
    frpConfigure.disabled = remoteProviderBusy || !frpInstalled
    frpPurge.hidden = !frpInstalled && !frpConfigured
    frpComponentStatus.textContent = !frpSupported
      ? '当前桌面平台暂不支持托管安装 frpc。'
      : !frpInstalled
        ? 'frpc 尚未安装。点击安装前不会下载任何内容。'
        : `官方 frpc ${frpVersion} 已校验。`
    frpConfigurationStatus.textContent = frpConfigured ? '服务器配置已保存，Token 只保存在插件私有目录。' : '请填写完整信息、复制 VPS 模板，然后保存并连接。'
    selfHostedBadge.textContent = frpConfigured && frpInstalled ? '已就绪' : '高级'
    const state = typeof data.state === 'string' ? data.state : 'error'
    const errorCode = typeof data.errorCode === 'string' ? data.errorCode : ''
    const remoteOrigin = typeof data.origin === 'string' ? data.origin : ''
    remoteLoginUrl = typeof data.loginUrl === 'string' ? data.loginUrl : ''
    const candidateSetupUrl = tailscale && typeof data.setupUrl === 'string'
      ? ((value: string): string => {
          try {
            const url = new URL(value)
            if (url.protocol === 'https:' && url.hostname === 'login.tailscale.com' && url.port === ''
              && url.username === '' && url.password === '') return url.toString()
          } catch { /* invalid */ }
          const normalized = value.replace(/\/$/u, '')
          if (['https://tailscale.com/s/no-funnel', 'https://tailscale.com/s/https'].includes(normalized)) return normalized
          return ''
        })(data.setupUrl as string)
      : ''
    const fallbackSetupUrls: Record<string, string> = {
      funnel_permission_required: 'https://tailscale.com/s/no-funnel',
      funnel_https_required: 'https://tailscale.com/s/https',
      funnel_start_failed: 'https://tailscale.com/s/no-funnel',
    }
    remoteSetupUrl = candidateSetupUrl !== '' ? candidateSetupUrl : (fallbackSetupUrls[errorCode] ?? '')
    const needsFunnelSetup = state === 'error' && remoteSetupUrl !== ''
    remoteReady = remoteRunning && state === 'ready' && remoteOrigin !== ''
    remoteStateBadge.classList.toggle('is-ready', remoteReady)
    remoteStateBadge.classList.toggle('is-busy', state === 'starting' || state === 'connecting' || state === 'needs-login')
    remoteStateBadge.classList.toggle('is-attention', state === 'error' || state === 'unavailable')
    remoteStateBadge.textContent = remoteReady
      ? '已就绪'
      : state === 'starting' || state === 'connecting' || state === 'needs-login'
        ? '连接中'
        : state === 'error' || state === 'unavailable'
          ? '需处理'
          : '未启用'
    remoteAccess.hidden = !remoteReady
    remoteAccessLink.href = remoteOrigin
    remoteAccessLink.textContent = remoteOrigin
    remoteAccessLink.title = remoteOrigin
    remoteStatus.classList.toggle('is-running', remoteReady)
    const labels: Record<string, string> = {
      off: '远程访问未启用。局域网访问不受影响。',
      unavailable: cpolar ? 'cpolar 尚未安装或未完成本机账号配置。' : frp ? 'frpc 尚未安装或私有服务器配置不完整。' : '当前电脑缺少 Funnel 运行组件，请重新安装完整插件包。',
      starting: cpolar ? '正在连接 cpolar 国内节点…' : frp ? '正在检查 VPS 并准备受限 FRP 通道…' : '正在启动 Tailscale 安全通道…',
      'needs-login': '需要在浏览器完成一次 Tailscale 登录。插件不会读取你的密码。',
      connecting: cpolar ? '公网地址已分配，正在启动 DSH 认证网关…' : frp ? 'frpc 已启动，正在验证公网 HTTPS 端点…' : '登录完成，正在建立公开 HTTPS 地址…',
      ready: '远程访问已就绪。只有已配对设备可以进入 DSH。',
      error: '远程连接未建立。可重新连接，局域网访问仍可正常使用。',
    }
    const errorLabels: Record<string, string> = {
      funnel_permission_required: '登录已完成。请继续授权 Funnel，完成后会自动建立远程连接。',
      funnel_https_required: '登录已完成。请继续授权 Funnel，官方页面会同时启用 HTTPS。',
      funnel_start_failed: '登录已完成。请继续完成 Tailscale Funnel 的首次授权。',
      funnel_start_timeout: 'Tailscale 组件启动超时，请检查网络后重新连接。',
      tailscale_dns_missing: 'Tailscale 暂未提供远程地址。请重新连接并确认已完成登录。',
      gateway_start_failed: '远程网关启动失败。请重新连接，局域网访问不受影响。',
      control_channel_failed: '远程组件连接中断。请重新连接。',
      cpolar_component_missing: 'cpolar 官方组件尚未安装。请先完成上方准备步骤。',
      cpolar_component_invalid: 'cpolar 组件校验失败。请彻底移除后重新安装。',
      cpolar_config_missing: 'cpolar 尚未保存账号令牌。请先完成上方准备步骤。',
      cpolar_config_invalid: 'cpolar 本机配置无效。请重新保存账号令牌。',
      cpolar_port_unavailable: '无法分配本机远程网关端口，请重试。',
      cpolar_launch_failed: 'cpolar 客户端未能启动。',
      cpolar_start_timeout: '连接 cpolar 国内节点超时，请重新连接。',
      cpolar_stopped: 'cpolar 连接已停止。',
      cpolar_exited: 'cpolar 连接意外退出，请重新连接。',
      cpolar_invalid_output: 'cpolar 返回了无法识别的状态。',
      cpolar_invalid_origin: 'cpolar 返回的公网地址未通过校验。',
      frp_component_missing: '请先安装官方 frpc。',
      frp_component_invalid: 'frpc 校验失败，请彻底清理后重新安装。',
      frp_config_missing: '请先保存自建 FRP 连接信息。',
      frp_config_verify_failed: 'frpc 拒绝了生成的配置，请检查服务器信息和 Token。',
      frp_vhost_publicly_reachable: 'VPS 明文 HTTP vhost 可被公网访问。请将其限制到 127.0.0.1，并由 Caddy 提供 HTTPS。',
      frp_vhost_probe_failed: '无法检查 VPS 地址，请确认 DNS 后重试。',
      frp_launch_failed: 'frpc 未能启动。',
      frp_start_timeout: '公网端点未能就绪，请检查 frps、Caddy、DNS 和防火墙。',
      frp_discovery_mismatch: '公开域名连接到了另一台 DSH 电脑，请检查 Caddy 与 frps 映射。',
      frp_discovery_invalid: '公开域名没有返回有效的 DSH Amadeus 发现信息。',
      frp_stopped: 'FRP 连接已停止。',
      frp_exited: 'frpc 意外退出，请检查 VPS 配置后重新连接。',
    }
    remoteStatus.textContent = remoteSetupPending && needsFunnelSetup
      ? 'Tailscale 官方页面已打开。完成启用后返回 DSH，这里会自动重新连接。'
      : (state === 'error' ? (errorLabels[errorCode] ?? labels.error!) : (labels[state] ?? labels.error!))
    remoteToggle.textContent = remoteRunning ? '关闭远程访问' : '启用远程访问'
    const providerPrepared = cpolar ? cpolarInstalled && cpolarConfigured : frp ? frpInstalled && frpConfigured : true
    remoteToggle.disabled = remoteProviderBusy || !providerPrepared
    remoteLogin.hidden = !tailscale || state !== 'needs-login' || remoteLoginUrl === ''
    remoteReconnect.hidden = needsFunnelSetup || (state !== 'error' && state !== 'unavailable') || !providerPrepared
    remoteActions.hidden = !providerPrepared
    remotePair.disabled = !remoteReady
    remoteDevices.disabled = !remoteReady
    if (!remoteReady) remoteQr.hidden = true
    if (!needsFunnelSetup) remoteSetupPending = false
  }

  const loadRemote = (): void => {
    if (remoteLoadInFlight) return
    remoteLoadInFlight = true
    void controlRequestJson('/remote/control')
      .then(renderRemote, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
      .finally(() => { remoteLoadInFlight = false })
  }
  const chooseRemoteProvider = (provider: 'tailscale' | 'cpolar' | 'frp'): void => {
    if (remoteProviderBusy || provider === remoteProvider) return
    if (remoteRunning && !window.confirm('切换连接方式会先关闭当前远程通道。局域网和配对设备不会受影响，是否继续？')) return
    remoteProviderBusy = true
    tailscaleChoice.disabled = true
    cpolarChoice.disabled = true
    frpChoice.disabled = true
    remoteStatus.textContent = provider === 'cpolar' ? '正在切换到 cpolar…' : provider === 'frp' ? '正在切换到自建 FRP…' : '正在切换到 Tailscale Funnel…'
    void controlRequestJson('/remote/provider', { method: 'POST', body: JSON.stringify({ provider }) })
      .then(renderRemote, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
      .finally(() => { remoteProviderBusy = false; loadRemote() })
  }
  tailscaleChoice.addEventListener('click', () => { chooseRemoteProvider('tailscale') })
  cpolarChoice.addEventListener('click', () => { chooseRemoteProvider('cpolar') })
  frpChoice.addEventListener('click', () => { chooseRemoteProvider('frp') })

  cpolarInstall.addEventListener('click', () => {
    if (remoteProviderBusy) return
    if (!window.confirm('将从 cpolar 官方网站下载并校验固定版本，仅解压到 DSH Amadeus 私有目录。不会安装系统服务、写入 PATH/注册表或设置开机启动。是否继续？')) return
    remoteProviderBusy = true
    cpolarInstall.disabled = true
    cpolarInstall.textContent = '正在下载并校验…'
    remoteStatus.textContent = '正在安装 cpolar 官方组件。完成前请保持 DSH 运行。'
    void controlRequestJson('/remote/cpolar/component/install', { method: 'POST', body: JSON.stringify({ confirm: true }) }, LONG_CONTROL_REQUEST_TIMEOUT_MS)
      .then(renderRemote, error => { remoteStatus.textContent = `组件安装失败：${String(error)}` })
      .finally(() => { remoteProviderBusy = false; loadRemote() })
  })
  cpolarConfigure.addEventListener('click', () => {
    if (remoteProviderBusy) return
    const authtoken = cpolarToken.value.trim()
    if (authtoken.length < 20 || /\s/u.test(authtoken)) {
      remoteStatus.textContent = '请粘贴 cpolar 控制台提供的完整 Authtoken。'
      cpolarToken.focus()
      return
    }
    remoteProviderBusy = true
    cpolarConfigure.disabled = true
    cpolarConfigure.setAttribute('aria-busy', 'true')
    cpolarConfigure.textContent = '正在保存…'
    void controlRequestJson('/remote/cpolar/configure', { method: 'POST', body: JSON.stringify({ authtoken }) }, LONG_CONTROL_REQUEST_TIMEOUT_MS)
      .then(() => {
        cpolarToken.value = ''
        remoteStatus.textContent = '账号配置已保存，正在建立 cpolar 远程通道…'
        return controlRequestJson('/remote/control', { method: 'POST', body: JSON.stringify({ running: true }) })
      })
      .then(renderRemote, error => { remoteStatus.textContent = `配置失败：${String(error)}` })
      .finally(() => { remoteProviderBusy = false; cpolarConfigure.setAttribute('aria-busy', 'false'); cpolarConfigure.textContent = '保存并连接'; loadRemote() })
  })
  cpolarPurge.addEventListener('click', () => {
    if (remoteProviderBusy) return
    if (!window.confirm('彻底移除 DSH Amadeus 私有目录中的 cpolar 组件、令牌配置和运行日志？不会影响局域网、DSH 数据或系统中的其他程序。')) return
    remoteProviderBusy = true
    cpolarPurge.disabled = true
    remoteStatus.textContent = '正在关闭通道并清理 DSH Amadeus 管理的 cpolar 文件…'
    void controlRequestJson('/remote/cpolar/component/purge', { method: 'POST', body: JSON.stringify({ confirm: true }) }, LONG_CONTROL_REQUEST_TIMEOUT_MS)
      .then(renderRemote, error => { remoteStatus.textContent = `清理失败：${String(error)}` })
      .finally(() => { remoteProviderBusy = false; cpolarPurge.disabled = false; loadRemote() })
  })

  const validFrpServer = (value: string): boolean => value === value.trim() && value.length > 0 && value.length <= 253
    && !/[\s\u0000-\u001f\u007f/\\@?#]/u.test(value)
  const frpForm = (): { readonly serverAddress: string; readonly serverPort: number; readonly token: string; readonly publicOrigin: string } => ({
    serverAddress: frpServer.value.trim(),
    serverPort: Number(frpPort.value),
    token: frpToken.value,
    publicOrigin: frpOrigin.value.trim(),
  })
  const validFrpForm = (form: ReturnType<typeof frpForm>): boolean => {
    if (!validFrpServer(form.serverAddress)) return false
    try {
      createFrpServerTemplateForClipboard(form.serverPort, form.token, form.publicOrigin)
      return true
    } catch { return false }
  }
  frpCopyTemplate.addEventListener('click', () => {
    const form = frpForm()
    if (!validFrpForm(form)) {
      remoteStatus.textContent = '请检查 VPS 地址、端口、HTTPS 地址和 Token。'
      return
    }
    void navigator.clipboard.writeText(createFrpServerTemplateForClipboard(form.serverPort, form.token, form.publicOrigin))
      .then(() => { remoteStatus.textContent = '服务器模板已复制，请在 VPS 应用 frps.toml 和 Caddyfile。' },
        () => { remoteStatus.textContent = '无法复制模板，请检查上方四项信息。' })
  })
  frpInstall.addEventListener('click', () => {
    if (remoteProviderBusy) return
    if (!window.confirm(`从 FRP 官方 Release 下载并校验 frpc 0.70.1（约 ${frpDownloadSize} MB），只提取 frpc 到 DSH Amadeus 私有目录？不会安装服务、写入 PATH 或设置开机启动。`)) return
    remoteProviderBusy = true
    frpInstall.disabled = true
    frpInstall.textContent = '正在下载并校验…'
    remoteStatus.textContent = '正在安装官方 frpc，完成前请保持 DSH 运行。'
    void controlRequestJson('/remote/frp/component/install', { method: 'POST', body: JSON.stringify({ confirm: true }) }, LONG_CONTROL_REQUEST_TIMEOUT_MS)
      .then(renderRemote, error => { remoteStatus.textContent = `组件安装失败：${String(error)}` })
      .finally(() => { remoteProviderBusy = false; loadRemote() })
  })
  frpConfigure.addEventListener('click', () => {
    if (remoteProviderBusy || !frpInstalled) return
    const form = frpForm()
    const unchanged = frpConfigured && form.token === '' && form.serverAddress === configuredFrpServer
      && form.serverPort === configuredFrpPort && form.publicOrigin === configuredFrpOrigin
    if (!unchanged && !validFrpForm(form)) {
      remoteStatus.textContent = '请检查 VPS 地址、端口、HTTPS 地址和 Token。'
      return
    }
    remoteProviderBusy = true
    frpConfigure.disabled = true
    frpConfigure.setAttribute('aria-busy', 'true')
    frpConfigure.textContent = '正在保存…'
    const configure = unchanged
      ? Promise.resolve<Record<string, unknown>>({})
      : controlRequestJson('/remote/frp/configure', { method: 'POST', body: JSON.stringify(form) })
    void configure.then(() => {
      frpToken.value = ''
      remoteStatus.textContent = '正在保存私有配置并验证完整 FRP 链路…'
      return controlRequestJson('/remote/control', { method: 'POST', body: JSON.stringify({ running: true }) })
    }).then(renderRemote, error => { remoteStatus.textContent = `配置失败：${String(error)}` })
      .finally(() => {
        remoteProviderBusy = false
        frpConfigure.setAttribute('aria-busy', 'false')
        frpConfigure.textContent = '保存并验证连接'
        loadRemote()
      })
  })
  frpPurge.addEventListener('click', () => {
    if (remoteProviderBusy || !window.confirm('关闭 FRP 并移除 DSH Amadeus 管理的 frpc、共享 Token、生成配置、临时文件和日志？不会修改 VPS。')) return
    remoteProviderBusy = true
    frpPurge.disabled = true
    remoteStatus.textContent = '正在关闭 FRP 并清理 DSH Amadeus 管理的文件…'
    void controlRequestJson('/remote/frp/component/purge', { method: 'POST', body: JSON.stringify({ confirm: true }) }, LONG_CONTROL_REQUEST_TIMEOUT_MS)
      .then(renderRemote, error => { remoteStatus.textContent = `清理失败：${String(error)}` })
      .finally(() => { remoteProviderBusy = false; frpPurge.disabled = false; loadRemote() })
  })

  remoteToggle.addEventListener('click', () => {
    remoteToggle.disabled = true
    void controlRequestJson('/remote/control', { method: 'POST', body: JSON.stringify({ running: !remoteRunning }) })
      .then(renderRemote, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
      .finally(loadRemote)
  })
  remoteLogin.addEventListener('click', () => {
    if (remoteLoginUrl !== '') window.open(remoteLoginUrl, '_blank', 'noopener,noreferrer')
  })
  const reconnectRemote = (): void => {
    if (remoteReconnectBusy) return
    remoteReconnectBusy = true
    remoteReconnect.disabled = true
    remoteStatus.textContent = remoteProvider === 'cpolar' ? '正在重新连接 cpolar 国内节点…' : remoteProvider === 'frp' ? '正在验证并重新连接自建 FRP…' : '正在确认 Tailscale 设置并重新连接…'
    void controlRequestJson('/remote/reconnect', { method: 'POST', body: '{}' })
      .then(renderRemote, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
      .finally(() => { remoteReconnectBusy = false; remoteReconnect.disabled = false })
  }
  remoteReconnect.addEventListener('click', reconnectRemote)
  remotePair.addEventListener('click', () => {
    remotePair.disabled = true
    void controlRequestJson('/remote/pairing/open', { method: 'POST', body: '{}' }).then(async data => {
      const pairUrl = typeof data.pairUrl === 'string' ? data.pairUrl : ''
      showQr(typeof data.qrSvg === 'string' ? data.qrSvg : '', remoteQr)
      if (pairUrl !== '') {
        try { await navigator.clipboard.writeText(pairUrl) } catch { /* QR remains the primary remote handoff. */ }
      }
      remoteStatus.textContent = '远程配对二维码已生成。请在 App 的"远程访问"中扫描。'
    }, error => { remoteStatus.textContent = `请求失败：${String(error)}` }).finally(() => { remotePair.disabled = !remoteReady })
  })
  const renderRemoteDevices = (data: Record<string, unknown>): void => {
    const devices = Array.isArray(data.devices) ? data.devices as Record<string, unknown>[] : []
    remoteDevicePanel.replaceChildren()
    if (devices.length === 0) {
      const empty = element('p', 'amw-control__device-empty'); empty.textContent = '暂无远程配对设备。'; remoteDevicePanel.append(empty); return
    }
    for (const device of devices) {
      const row = element('div', 'amw-control__device')
      const label = element('span', 'amw-control__device-label'); label.textContent = typeof device.label === 'string' ? device.label : '设备'
      const meta = element('span', 'amw-control__device-meta'); meta.textContent = `到期 ${formatTime(device.expiresAt)}`
      const revoke = element('button', 'amw-control__device-revoke'); revoke.type = 'button'; revoke.textContent = '撤销'
      const id = typeof device.id === 'string' ? device.id : ''
      revoke.addEventListener('click', () => {
        void controlRequestJson('/remote/devices/revoke', { method: 'POST', body: JSON.stringify({ deviceId: id }) })
          .then(loadRemoteDevices, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
      })
      row.append(label, meta, revoke); remoteDevicePanel.append(row)
    }
  }
  const loadRemoteDevices = (): void => {
    void controlRequestJson('/remote/devices').then(renderRemoteDevices, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
  }
  remoteDevices.addEventListener('click', () => {
    const show = remoteDevicePanel.hidden
    remoteDevicePanel.hidden = !show
    if (show) loadRemoteDevices()
  })
  remoteReset.addEventListener('click', () => {
    const prompt = remoteProvider === 'cpolar'
      ? '关闭 cpolar 远程通道并移除所有远程配对设备？不会修改你的 cpolar 账号或其他隧道。'
      : remoteProvider === 'frp' ? '关闭自建 FRP 并移除所有远程配对设备？已保存的 VPS 配置和服务器不会改变。' : '退出电脑上的 Tailscale 登录并移除所有远程配对设备？局域网配置不会改变。'
    if (!window.confirm(prompt)) return
    void controlRequestJson('/remote/reset', { method: 'POST', body: JSON.stringify({ confirm: true }) })
      .then(renderRemote, error => { remoteStatus.textContent = `请求失败：${String(error)}` })
  })

  const refresh = async (): Promise<void> => {
    await controlRequestJson('/control').then(render, error => { status.textContent = `请求失败：${String(error)}` })
  }

  return {
    root,
    refresh,
    remove: () => {
      lifecycle.abort()
      root.remove()
    },
  }
}

/** Mount the Amadeus settings-page control. */
export function apply(ctx: ClientContext): void {
  ctx.effect(() => {
    const style = element('style'); style.dataset.plugin = 'dsh-amadeus'
    const isLoopback = location.hostname === 'localhost' || location.hostname === '127.0.0.1' || location.hostname === '::1'
    style.textContent = isLoopback ? STYLES : ''
    document.head.append(style)

    // Settings-page section: full control panel as a durable settings entry.
    const section = installSection()
    const disposeSection = ctx.slots.inject('settings.section', () => ctx.slots.register<{ close: () => void }>({
      name: 'settings.section',
      id: 'amadeus',
      order: 30,
      label: () => 'Amadeus 网关',
    }, () => {
      void section.refresh().catch(() => {})
      // React-owned mount site; the command-mode panel lives inside it.
      return h('div', { className: 'amw-section-mount', ref: (node: HTMLElement | null) => {
        if (node === null || node.contains(section.root)) return
        node.append(section.root)
      } })
    }))

    return () => {
      disposeSection()
      section.remove()
      style.remove()
    }
  }, 'dsh-amadeus: settings control')
}

/** Client services required. */
export const inject: readonly string[] = ['slots']

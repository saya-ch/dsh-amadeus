/**
 * 标签协议 [[AMW:{...}]] 的解析、校验与兜底
 * 支持多句分页：每句独立标签，window 扩展
 */

export type AmadeusSprite = 'excited' | 'happy' | 'shy' | 'thinking' | 'exclaim' | 'pout' | 'deadpan' | 'flustered' | 'normal'
export type AmadeusVoice = 'whisper' | 'soft' | 'excited'
export type AmadeusSfx = 'wave' | 'bell' | 'none'
export type AmadeusBgm = 'rain' | 'none'
export type AmadeusWindow = 'none' | 'report' | 'preview' | 'choice'

export interface AmadeusTag {
  readonly sprite: AmadeusSprite
  readonly voice: AmadeusVoice
  readonly sfx: AmadeusSfx
  readonly bgm: AmadeusBgm
  readonly window?: AmadeusWindow
  readonly windowId?: string
  readonly windowTitle?: string
  // choice 窗口由 ask_user_question 工具驱动，window=choice 时携带
  readonly choiceId?: string
  readonly options?: readonly string[]
}

const VALID_SPRITES = new Set<string>(['excited','happy','shy','thinking','exclaim','pout','deadpan','flustered','normal'])
const VALID_VOICES = new Set<string>(['whisper','soft','excited'])
const VALID_SFX = new Set<string>(['wave','bell','none'])
const VALID_BGM = new Set<string>(['rain','none'])
const VALID_WINDOW = new Set<string>(['none','report','preview','choice'])

const TAG_RE = /\[\[AMW:\s*(\{[\s\S]*?\})\s*\]\]\s*$/m
// 多句分页：全局匹配每句的标签
const TAG_RE_GLOBAL = /\[\[AMW:\s*(\{[\s\S]*?\})\s*\]\]/g

export interface AmadeusSegment {
  readonly clean: string
  readonly tag: AmadeusTag | null
  readonly raw: string
}

export function parseAmadeusTag(text: string): { clean: string, tag: AmadeusTag | null } {
  const m = TAG_RE.exec(text)
  if (!m) return { clean: text, tag: null }
  try {
    const raw = JSON.parse(m[1]!) as Record<string, unknown>
    if (!VALID_SPRITES.has(String(raw.sprite))) {
      return { clean: text.slice(0, m.index).trimEnd(), tag: null }
    }
    const voice = VALID_VOICES.has(String(raw.voice)) ? raw.voice as AmadeusVoice : 'soft'
    const sfx = VALID_SFX.has(String(raw.sfx)) ? raw.sfx as AmadeusSfx : 'none'
    const bgm = VALID_BGM.has(String(raw.bgm)) ? raw.bgm as AmadeusBgm : 'none'
    if (raw.window !== undefined && !VALID_WINDOW.has(String(raw.window))) {
      return { clean: text.slice(0, m.index).trimEnd(), tag: null }
    }
    const tag: AmadeusTag = {
      sprite: raw.sprite as AmadeusSprite,
      voice,
      sfx,
      bgm,
      ...(raw.window === undefined ? {} : { window: raw.window as AmadeusWindow }),
      ...(typeof raw.windowId === 'string' ? { windowId: raw.windowId } : {}),
      ...(typeof raw.windowTitle === 'string' ? { windowTitle: raw.windowTitle } : {}),
      ...(typeof raw.choiceId === 'string' ? { choiceId: raw.choiceId } : {}),
      ...(Array.isArray(raw.options) ? { options: raw.options.filter(x => typeof x === 'string') as string[] } : {}),
    }
    return { clean: text.slice(0, m.index).trimEnd(), tag }
  } catch {
    return { clean: text.slice(0, m.index).trimEnd(), tag: null }
  }
}

/**
 * 多句分页解析：把模型的一次回复拆成多段，每段带独立标签
 * 输入示例：
 *   句1\n[[AMW:{...}]]\n句2\n[[AMW:{...}]]
 * 输出: [{clean: 句1, tag: ...}, {clean: 句2, tag: ...}]
 */
export function parseAmadeusSegments(text: string): AmadeusSegment[] {
  const segments: AmadeusSegment[] = []
  let lastIndex = 0
  let m: RegExpExecArray | null
  TAG_RE_GLOBAL.lastIndex = 0
  while ((m = TAG_RE_GLOBAL.exec(text)) !== null) {
    const tagStart = m.index
    const tagEnd = tagStart + m[0].length
    const clean = text.slice(lastIndex, tagStart).trim()
    if (clean.length > 0 || m[1]) {
      const parsed = parseAmadeusTag(clean + m[0])
      segments.push({ clean: parsed.clean, tag: parsed.tag, raw: clean + m[0] })
    }
    lastIndex = tagEnd
    // 跳过标签后的换行
    while (lastIndex < text.length && (text[lastIndex] === '\n' || text[lastIndex] === '\r')) lastIndex++
  }
  const tail = text.slice(lastIndex).trim()
  if (tail.length > 0) {
    segments.push({ clean: tail, tag: null, raw: tail })
  }
  if (segments.length === 0 && text.trim().length > 0) {
    segments.push({ clean: text.trim(), tag: null, raw: text })
  }
  return segments
}

export function stripAmadeusTag(text: string): string {
  return parseAmadeusTag(text).clean
}

export function stripAllTags(text: string): string {
  return parseAmadeusSegments(text).map(s => s.clean).join('\n')
}

export function ensureAmadeusTag(text: string, fallback: AmadeusTag = { sprite: 'normal', voice: 'soft', sfx: 'none', bgm: 'none' }): string {
  const { clean, tag } = parseAmadeusTag(text)
  if (tag) return text
  let sprite: AmadeusSprite = fallback.sprite
  if (/思考|正在|稍等|让我|想想|看看/.test(clean)) sprite = 'thinking'
  else if (/工具|执行|调用|处理|完成/.test(clean)) sprite = 'exclaim'
  else if (/开心|好耶|成功|哇|宝藏|发现|惊喜|喜欢/.test(clean)) sprite = 'excited'
  else if (/抱歉|失败|难过|呜|哭了/.test(clean)) sprite = 'flustered'
  else if (/哼|才不|讨厌|生气/.test(clean)) sprite = 'pout'
  else if (/唉|算了|无语|随便/.test(clean)) sprite = 'deadpan'
  const tag2: AmadeusTag = { ...fallback, sprite }
  return `${clean}\n[[AMW:${JSON.stringify(tag2)}]]`
}

export function hasAmadeusTag(text: string): boolean {
  return TAG_RE.test(text)
}

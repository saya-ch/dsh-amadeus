/**
 * 标签协议 [[AMW:{...}]] 的解析、校验与兜底
 */

export type AmadeusMood = 'shy' | 'think' | 'tool' | 'happy' | 'sad' | 'idle'
export type AmadeusSprite = 'shy' | 'think' | 'tool' | 'wag' | 'gray' | 'smile' | 'talk'
export type AmadeusVoice = 'whisper' | 'soft' | 'excited'
export type AmadeusSfx = 'wave' | 'bell' | 'none'
export type AmadeusBgm = 'rain' | 'none'

export interface AmadeusTag {
  readonly mood: AmadeusMood
  readonly sprite: AmadeusSprite
  readonly voice: AmadeusVoice
  readonly sfx: AmadeusSfx
  readonly bgm: AmadeusBgm
}

const VALID_MOODS = new Set<string>(['shy','think','tool','happy','sad','idle'])
const VALID_SPRITES = new Set<string>(['shy','think','tool','wag','gray','smile','talk'])
const VALID_VOICES = new Set<string>(['whisper','soft','excited'])
const VALID_SFX = new Set<string>(['wave','bell','none'])
const VALID_BGM = new Set<string>(['rain','none'])

const TAG_RE = /\[\[AMW:\s*(\{[\s\S]*?\})\s*\]\]\s*$/m

export function parseAmadeusTag(text: string): { clean: string, tag: AmadeusTag | null } {
  const m = TAG_RE.exec(text)
  if (!m) return { clean: text, tag: null }
  try {
    const raw = JSON.parse(m[1]!) as Record<string, unknown>
    if (!VALID_MOODS.has(String(raw.mood)) || !VALID_SPRITES.has(String(raw.sprite)) ||
        !VALID_VOICES.has(String(raw.voice)) || !VALID_SFX.has(String(raw.sfx)) || !VALID_BGM.has(String(raw.bgm))) {
      return { clean: text.slice(0, m.index).trimEnd(), tag: null }
    }
    const tag: AmadeusTag = {
      mood: raw.mood as AmadeusMood,
      sprite: raw.sprite as AmadeusSprite,
      voice: raw.voice as AmadeusVoice,
      sfx: raw.sfx as AmadeusSfx,
      bgm: raw.bgm as AmadeusBgm,
    }
    return { clean: text.slice(0, m.index).trimEnd(), tag }
  } catch {
    return { clean: text.slice(0, m.index).trimEnd(), tag: null }
  }
}

export function stripAmadeusTag(text: string): string {
  return parseAmadeusTag(text).clean
}

export function ensureAmadeusTag(text: string, fallback: AmadeusTag = { mood: 'idle', sprite: 'smile', voice: 'soft', sfx: 'none', bgm: 'none' }): string {
  const { clean, tag } = parseAmadeusTag(text)
  if (tag) return text
  // 简单情绪兜底：根据关键词猜 mood
  let mood: AmadeusMood = fallback.mood
  let sprite: AmadeusSprite = fallback.sprite
  if (/思考|正在|稍等|让我/.test(clean)) { mood = 'think'; sprite = 'think' }
  else if (/工具|执行|调用|处理/.test(clean)) { mood = 'tool'; sprite = 'tool' }
  else if (/开心|好耶|成功|完成/.test(clean)) { mood = 'happy'; sprite = 'wag' }
  else if (/抱歉|失败|难过|呜/.test(clean)) { mood = 'sad'; sprite = 'gray' }
  const tag2: AmadeusTag = { ...fallback, mood, sprite }
  return `${clean}\n[[AMW:${JSON.stringify(tag2)}]]`
}

export function hasAmadeusTag(text: string): boolean {
  return TAG_RE.test(text)
}

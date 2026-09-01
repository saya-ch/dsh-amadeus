/**
 * DSH message-content text extraction, aligned with the official
 * `extractSessionEventText` (session-query): text lives in ContentBlocks,
 * never on `message.text`. Both the SSE hub and the history page read
 * assistant/user/tool events through here so the extraction stays correct
 * when DSH evolves its block vocabulary.
 */

/** Minimal structural surface of one DSH content block. */
export interface AmadeusContentBlock {
  readonly type: string
  readonly text?: string
  readonly name?: string
  readonly arguments?: string
  readonly content?: readonly AmadeusContentBlock[]
}

/** Join trimmed, non-empty parts with newlines (DSH `joinText`). */
function joinText(parts: readonly string[]): string {
  return parts.map(part => part.trim()).filter(Boolean).join('\n')
}

/** Text of one content block, mirroring DSH `blockText` (reasoning is hidden). */
function blockText(block: AmadeusContentBlock): string[] {
  switch (block.type) {
    case 'text':
      return block.text === undefined ? [] : [block.text]
    case 'reasoning':
      return []
    case 'tool-call':
      // 产品 1.5：工具调用不进对话（是幕后事件，进事件流小窗）
      return []
    case 'tool-result':
      return []
    default:
      // ContentBlockMap is merge-extensible: unknown blocks stay non-textual.
      return []
  }
}

/** Visible text of a DSH `Message.content` array (`contentText`). */
export function amadeusContentText(content: readonly AmadeusContentBlock[] | undefined): string {
  if (content === undefined) return ''
  return joinText(content.flatMap(blockText))
}

/** The user-facing text of an `assistant/message` event (data.message.content). */
export function assistantMessageText(data: unknown): string {
  const record = data as { message?: { content?: readonly AmadeusContentBlock[] } } | undefined
  return amadeusContentText(record?.message?.content)
}

/** The user-facing text of a `user/message` event (data.content). */
export function userMessageText(data: unknown): string {
  const record = data as { content?: readonly AmadeusContentBlock[] } | undefined
  return amadeusContentText(record?.content)
}

/** The tool name of a `tool/result` event (data.name). */
export function toolResultLabel(data: unknown): string {
  const record = data as { name?: unknown } | undefined
  return typeof record?.name === 'string' ? record.name : ''
}

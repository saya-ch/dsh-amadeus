import { randomBytes } from 'node:crypto'
import type { AmadeusReportsAdapter, AmadeusPreviewStore } from './amadeus-reports.js'

/** Structural surface of the DSH `ctx.tools` registry (core/tools). */
export interface AmadeusToolsRegistry {
  readonly tools: {
    register(tool: {
      name: string
      description: string
      parameters: Record<string, unknown>
      output: {
        schema: Record<string, unknown>
        render(args: unknown, value: unknown): Array<{ type: 'text'; text: string }>
      }
      execute(args: unknown, exec?: { signal?: AbortSignal }): Promise<unknown>
    }): void
  }
}

function registryOf(ctx: unknown): AmadeusToolsRegistry {
  // DSH publishes `tools` on the agent-scoped Context at runtime; the Cordis
  // Context type does not carry it, so the structural surface is asserted here.
  return ctx as AmadeusToolsRegistry
}

const PREVIEW_TYPES = new Set<string>(['web', 'image', 'code', 'table'])

export function registerAmadeusTools(ctx: unknown, reports: AmadeusReportsAdapter, previews: AmadeusPreviewStore): void {
  const registry = registryOf(ctx)
  registry.tools.register({
    name: 'save_report',
    description: '把一份长 Markdown/文件列表存为报告窗口，返回 windowId 供 [[AMW:]] 标签引用',
    parameters: {
      title: { type: 'string', required: true, description: '报告标题' },
      markdown: { type: 'string', required: true, description: '报告完整 Markdown 内容' },
    },
    output: {
      schema: {
        type: 'object',
        properties: {
          windowId: { type: 'string' },
          title: { type: 'string' },
        },
        required: ['windowId', 'title'],
        additionalProperties: false,
      },
      render(_args: unknown, value: unknown) {
        const v = value as { windowId?: string; title?: string }
        return [{ type: 'text', text: `报告已保存：${v.title ?? ''} (${v.windowId ?? ''})` }]
      },
    },
    async execute(args: { title: string; markdown: string }) {
      const id = `rpt_${randomBytes(4).toString('hex')}`
      await reports.save({ id, title: args.title, markdown: args.markdown, createdAt: Date.now() })
      return { windowId: id, title: args.title }
    },
  })
  registry.tools.register({
    name: 'show_preview',
    description: '把一个结果（网页 URL/图片/表格/代码 diff）存为预览窗口，返回 windowId',
    parameters: {
      title: { type: 'string', required: true, description: '预览标题' },
      type: { type: 'string', required: true, description: 'web|image|code|table' },
      content: { type: 'string', required: true, description: '按 type 的预览内容' },
    },
    output: {
      schema: {
        type: 'object',
        properties: {
          windowId: { type: 'string' },
          title: { type: 'string' },
          error: { type: 'string' },
        },
        additionalProperties: false,
      },
      render(_args: unknown, value: unknown) {
        const v = value as { windowId?: string; title?: string; error?: string }
        if (typeof v.error === 'string') return [{ type: 'text', text: v.error }]
        return [{ type: 'text', text: `预览已创建：${v.title ?? ''} (${v.windowId ?? ''})` }]
      },
    },
    async execute(args: { title: string; type: string; content: string }) {
      if (!PREVIEW_TYPES.has(args.type)) {
        return { error: `unsupported preview type: ${args.type} (expected web|image|code|table)` }
      }
      const id = `pv_${randomBytes(4).toString('hex')}`
      await previews.save({ id, type: args.type, content: args.content, title: args.title, createdAt: Date.now() })
      return { windowId: id, title: args.title }
    },
  })
}
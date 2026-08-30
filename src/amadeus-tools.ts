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
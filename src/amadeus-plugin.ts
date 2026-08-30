import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
import { createAmadeusExtension } from './amadeus-extension.js'

/** Independent Cordis plugin consuming the DSH Mobile extension registry. */
export const name = 'dsh-amadeus'

/** DSH Mobile is the sole provider of pairing, credentials and network listeners. */
export const inject = ['mobileAccess']

/** Amadeus has no separate gateway or credential-file configuration. */
export const Config = z.object({})

/** Ensure the user preset `amadeus` exists (first-run copy from bundled preset). */
async function ensureAmadeusPreset(): Promise<void> {
  const { homedir } = await import('node:os')
  const { join } = await import('node:path')
  const { mkdir, copyFile, stat } = await import('node:fs/promises')
  const { fileURLToPath } = await import('node:url')
  const presetDir = join(homedir(), '.dsh', '.agent-presets', 'amadeus')
  try {
    await stat(join(presetDir, 'agent.cordis.yml'))
    return
  } catch {}
  const bundled = join(fileURLToPath(new URL('../presets/amadeus', import.meta.url)))
  await mkdir(presetDir, { recursive: true })
  await copyFile(join(bundled, 'preset.yml'), join(presetDir, 'preset.yml'))
  await copyFile(join(bundled, 'agent.cordis.yml'), join(presetDir, 'agent.cordis.yml'))
}

/** Register Amadeus routes and ensure the `amadeus` preset is discoverable. */
export function apply(ctx: Context): void {
  // Fire-and-forget preset copy — discovery re-reads on next roster call
  ensureAmadeusPreset().catch(() => {})
  ctx.effect(() => ctx.mobileAccess.registerExtension(createAmadeusExtension()))
}

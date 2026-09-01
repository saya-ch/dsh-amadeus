import { execFile as execFileCallback } from 'node:child_process'
import { chmod } from 'node:fs/promises'
import { promisify } from 'node:util'

const execFile = promisify(execFileCallback)
let userSidTask: Promise<string> | undefined

async function currentWindowsUserSid(): Promise<string> {
  userSidTask ??= resolveWindowsUserSid().then((sid) => {
    if (sid === undefined) throw new Error('unable to resolve the current Windows user SID')
    return sid
  })
  return userSidTask
}

/**
 * Resolve the current Windows user SID without shelling out to `whoami.exe`.
 * Going through PowerShell avoids Git Bash's MSYS path translation (which
 * turns `/user` into a Windows path and breaks `whoami /user` invocations),
 * and it works under any spawn environment. We invoke `powershell.exe`
 * directly with no `cmd /c` intermediary so nested quoting round-trips
 * reliably under Git Bash (where `cmd /c '...'` collapses the inner
 * PowerShell expression into a single literal argument).
 */
async function resolveWindowsUserSid(): Promise<string | undefined> {
  if (process.platform !== 'win32') return undefined
  const { stdout } = await execFile('powershell.exe', [
    '-NoProfile',
    '-Command',
    '(New-Object System.Security.Principal.NTAccount($env:USERNAME)).Translate([System.Security.Principal.SecurityIdentifier]).Value',
  ], { encoding: 'utf8', windowsHide: true })
  return stdout.trim()
}

/** Restrict a sensitive regular file to the current user and Windows administrators. */
export async function restrictPrivateFile(file: string, mode = 0o600): Promise<void> {
  await chmod(file, mode)
  if (process.platform !== 'win32') return
  const userSid = await currentWindowsUserSid()
  await execFile('icacls.exe', [
    file,
    '/inheritance:r',
    '/grant:r',
    `*${userSid}:(F)`,
    '*S-1-5-18:(F)',
    '*S-1-5-32-544:(F)',
    '/remove:g',
    '*S-1-1-0',
    '*S-1-5-11',
    '*S-1-5-32-545',
  ], { encoding: 'utf8', windowsHide: true })
}

// Amadeus LAN setup generator: writes ~/.dsh/amadeus/setup.json + self-signed TLS.
// Usage: node scripts/amadeus-setup.mjs [--address <lan-ip>] [--port 3444] [--firewall]
import { homedir } from 'node:os'
import { join } from 'node:path'
import { mkdir, writeFile } from 'node:fs/promises'

// Load the built managed-setup helpers from the package lib.
const { ensureManagedCa, refreshManagedServerCertificate, selectLanNetwork, preferredLanInterfaceNames, availableLanNetworks } = await import('../lib/index.mjs')

const args = process.argv.slice(2)
const address = args.includes('--address') ? args[args.indexOf('--address') + 1] : undefined
const port = args.includes('--port') ? Number(args[args.indexOf('--port') + 1]) : 3444
const directory = join(homedir(), '.dsh', 'amadeus')

const networks = availableLanNetworks()
console.log('可用局域网:')
for (const n of networks) console.log(`  ${n.name} = ${n.address}`)

const preferred = address === undefined ? await preferredLanInterfaceNames() : []
const network = selectLanNetwork(address, undefined, undefined, preferred)
console.log(`\n选中: ${network.name} = ${network.address}`)

const tls = join(directory, 'tls')
await mkdir(tls, { recursive: true, mode: 0o700 })
const managedTls = {
  mode: 'managed',
  caCertFile: join(tls, 'ca.pem'),
  caKeyFile: join(tls, 'ca-key.pem'),
  certFile: join(tls, 'server-cert.pem'),
  keyFile: join(tls, 'server-key.pem'),
}
await ensureManagedCa(managedTls)
const setup = {
  version: 2,
  networkInterface: network.name,
  listenPort: port,
  upstreamOrigin: 'http://127.0.0.1:3080',
  tls: managedTls,
}
await refreshManagedServerCertificate(setup, network.address)
await mkdir(directory, { recursive: true, mode: 0o700 })
await writeFile(join(directory, 'setup.json'), `${JSON.stringify(setup, null, 2)}\n`)
console.log(`\n已生成 ${join(directory, 'setup.json')}`)
console.log(`手机连接地址: https://${network.address}:${port}`)

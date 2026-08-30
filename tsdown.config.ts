import { defineConfig } from 'tsdown'

export default defineConfig([{
  entry: ['src/index.ts'],
  outDir: 'lib',
  format: ['esm'],
  platform: 'node',
  target: 'node22',
  dts: true,
  sourcemap: true,
  clean: true,
  deps: {
    neverBundle: [
      '@deepseek-ai/cordis',
      '@deepseek-ai/dsh-host-webserver',
    ],
  },
}])

import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Separate from vite.config.ts so the build's outDir/proxy never bleed into the test run. The tests drive the
// real pages with a stubbed API (MSW), so they need a DOM (jsdom) and the React plugin — nothing else.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      // Test harness, the entrypoint, and pure type files carry no testable logic.
      exclude: ['src/test/**', 'src/main.tsx', 'src/vite-env.d.ts', '**/*.d.ts'],
      // Enforced floor (gates `npm run test:cov` / CI). Current is ~98% lines / 89% functions / 84% branches;
      // minimums set a safe margin below so a regression fails CI without blocking a normal PR.
      thresholds: { lines: 90, statements: 90, functions: 80, branches: 75 },
    },
  },
})

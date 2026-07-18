import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
    css: false,
    // Unit tests live under src/; e2e/*.spec.ts are Playwright specs run via
    // `npm run e2e`. Without this, Vitest's default glob sweeps e2e/ and fails
    // on Playwright's test.describe() (different runner).
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});

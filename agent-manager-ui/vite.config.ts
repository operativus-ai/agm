import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  resolve: {
    // Edition manifest seam (agm-core-oss-execution.md §4.5): the ONLY bridge through
    // which edition UI can enter the app. Core resolves both aliases to empty stubs;
    // an edition build re-points them at its real features-ee manifests.
    alias: {
      '@ee/routes': path.resolve(__dirname, 'src/features-ee-stub/routes.tsx'),
      '@ee/nav': path.resolve(__dirname, 'src/features-ee-stub/nav.ts'),
      '@ee/observability-tabs': path.resolve(__dirname, 'src/features-ee-stub/observabilityTabs.ts'),
      '@ee/dashboard-widgets': path.resolve(__dirname, 'src/features-ee-stub/dashboardWidgets.tsx'),
      '@ee/agent-admin': path.resolve(__dirname, 'src/features-ee-stub/agentAdmin.tsx'),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/mcp/sse': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/mcp/messages': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Workflow live WebSocket (run-graph viewer + live viewer). ws:true upgrades the connection;
      // the exact path keeps it from shadowing the /api/.../workflows REST calls.
      '/workflows/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})

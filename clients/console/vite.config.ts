import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The console talks to AGM through the Vite dev proxy (no CORS in dev), exactly
// like demo-client. Port 5175 so it can run beside demo-client (5174) and
// agent-manager-ui (5173). @agm/sdk is a workspace TS package — allow Vite to
// transpile it from source.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5175,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      // So the SDK's reachability/health probes work through the browser too.
      '/v3': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});

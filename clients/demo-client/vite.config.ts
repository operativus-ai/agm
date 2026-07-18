import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev proxy mirrors agent-manager-ui: every /api call is forwarded to the AGM
// backend on :8080 (boot it with `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`).
// Port 5174 so the demo can run side-by-side with agent-manager-ui (5173).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});

import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ command, mode }) => {
  if (command === 'build') {
    // C-02: the realm has no default, so a build without it must fail.
    const env = loadEnv(mode, process.cwd(), 'VITE_');
    if (!env.VITE_KEYCLOAK_REALM?.trim()) {
      throw new Error('VITE_KEYCLOAK_REALM is required for a build (PRD §8.3)');
    }
  }

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 3002,
      strictPort: true,
      proxy: {
        // D-03: the local dev server forwards the admin API straight to the service.
        // changeOrigin false: the service sees the same Host a same-origin deployment sends.
        '/admin/v1': { target: 'http://localhost:8085', changeOrigin: false },
      },
    },
  };
});

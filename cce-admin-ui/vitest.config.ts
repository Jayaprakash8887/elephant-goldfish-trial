import { coverageConfigDefaults, defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// First statement after the imports: formatInstant gives the same text on every machine and in
// the Docker verify stage.
process.env.TZ = 'UTC';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    passWithNoTests: true,
    env: {
      VITE_KEYCLOAK_URL: 'http://keycloak.test/auth',
      VITE_KEYCLOAK_REALM: 'cce',
      VITE_KEYCLOAK_CLIENT_ID: 'cce-admin-ui',
      // Absolute, because Node's fetch rejects relative URLs; MSW handlers match */admin/v1/...
      VITE_API_BASE_URL: 'http://localhost:3002',
    },
    coverage: {
      provider: 'v8',
      include: ['src/**'],
      exclude: [
        ...coverageConfigDefaults.exclude,
        'src/test/**',
        'src/main.tsx',
        '**/__lint_fixtures__/**',
      ],
      thresholds: {
        lines: 80,
        statements: 80,
        functions: 80,
        branches: 70,
      },
    },
  },
});

import { defineConfig, devices } from '@playwright/test';

// UI-AD-10: one test at a time, because CUJ 6 switches the shared stand-in gateway.
export default defineConfig({
  testDir: 'e2e',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: 'http://localhost:3002',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/admin.json',
      },
      dependencies: ['setup'],
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3002',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});

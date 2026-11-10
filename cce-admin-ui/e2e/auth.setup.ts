// Signs in once through the real Keycloak login page (F1 exit check, "Login redirect works") and
// saves the browser state that every CUJ test reuses (item 99). e2e/.auth is git-ignored.
import { fileURLToPath } from 'node:url';
import { test as setup } from '@playwright/test';
import { adminCredentials, CONSOLE_URL } from './env';
import { expectHeaderUser } from './fixtures';

const AUTH_FILE = fileURLToPath(new URL('.auth/admin.json', import.meta.url));

setup('sign in through the Keycloak login page', async ({ page }) => {
  const { username, password } = adminCredentials();
  await page.goto('/');
  await page.waitForURL((url) => url.origin === 'http://localhost:8180');
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === CONSOLE_URL && url.pathname === '/users');
  await expectHeaderUser(page);
  await page.context().storageState({ path: AUTH_FILE });
});

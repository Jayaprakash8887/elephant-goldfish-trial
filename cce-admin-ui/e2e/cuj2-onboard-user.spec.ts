// CUJ 2: onboard a user (S2, F-11, F-05). The role is created through the API; the user is
// created in the console and found again with the Users list search (F4 exit checks).
import { email, expect, expectHeaderUser, test, username } from './fixtures';

test('CUJ 2: onboard a user with one role', async ({ page, api, suffix }) => {
  const role = await api.createRole('CUJ2A');
  const newUsername = username('CUJ2U', suffix);
  const newEmail = email('CUJ2U', suffix);

  await page.goto('/users/new');
  const form = page.getByRole('form', { name: 'New user' });
  await form.getByLabel('Username', { exact: true }).fill(newUsername);
  await form.getByLabel('Email', { exact: true }).fill(newEmail);
  await form.getByLabel('First name', { exact: true }).fill('E2E');
  await form.getByLabel('Last name', { exact: true }).fill('CUJ2U');
  await form.getByRole('checkbox', { name: `${role.displayName} (${role.name})` }).check();
  await form.getByRole('button', { name: 'Create user' }).click();

  await expect(page).toHaveURL(/\/users\/[0-9a-f-]{36}$/);
  await expect(
    page.getByText(`User created. A set-password email was sent to ${newEmail}.`),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: newUsername })).toBeVisible();

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Users' }).click();
  await page.getByLabel('Search', { exact: true }).fill(newUsername);
  const row = page.getByRole('row').filter({ hasText: newUsername });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(role.displayName);
  await expectHeaderUser(page);
});

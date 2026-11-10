// CUJ 5: deactivate, lock and re-activate a user from the Users list row actions (S5, F-10).
import { expect, expectHeaderUser, test } from './fixtures';

test('CUJ 5: deactivate, lock and activate a user', async ({ page, api }) => {
  const role = await api.createRole('CUJ5A');
  const user = await api.createUser('CUJ5U', [role.id]);

  await page.goto('/users');
  await page.getByLabel('Search', { exact: true }).fill(user.username);
  await expect(page).toHaveURL(new RegExp(`[?&]q=${user.username}`));
  const row = page.getByRole('row').filter({ hasText: user.username });
  await expect(row).toHaveCount(1);
  // Columns: username, email, status, roles, ...
  const statusCell = row.getByRole('cell').nth(2);
  await expect(statusCell).toHaveText('Active');

  const steps = [
    { action: 'Deactivate', text: 'Inactive' },
    { action: 'Lock', text: 'Locked' },
    { action: 'Activate', text: 'Active' },
  ];
  for (const { action, text } of steps) {
    await row.getByRole('button', { name: action, exact: true }).click();
    const dialog = page.getByRole('dialog', { name: `${action} ${user.username}?` });
    await dialog.getByRole('button', { name: action, exact: true }).click();
    await expect(page.getByRole('region', { name: 'Row action result' })).toContainText(
      `${user.username} is now ${text}.`,
    );
    await expect(statusCell).toHaveText(text);
    await expect(row).toContainText(role.displayName);
  }
  await expectHeaderUser(page);
});

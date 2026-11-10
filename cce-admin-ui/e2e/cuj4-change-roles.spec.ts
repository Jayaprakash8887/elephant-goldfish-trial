// CUJ 4: change a user's roles (S4, F-11). The console sends the full new set in one
// `PUT /users/:id/roles`.
import { expect, expectHeaderUser, test } from './fixtures';

test("CUJ 4: replace a user's roles with the full new set", async ({ page, api }) => {
  const roleA = await api.createRole('CUJ4A');
  const roleB = await api.createRole('CUJ4B');
  const user = await api.createUser('CUJ4U', [roleA.id]);

  await page.goto(`/users/${user.id}`);
  const rolesForm = page.getByRole('form', { name: 'Roles' });
  const checkboxA = rolesForm.getByRole('checkbox', {
    name: `${roleA.displayName} (${roleA.name})`,
  });
  const checkboxB = rolesForm.getByRole('checkbox', {
    name: `${roleB.displayName} (${roleB.name})`,
  });
  await expect(checkboxA).toBeChecked();
  await checkboxA.uncheck();
  await checkboxB.check();

  const sent = page.waitForRequest(
    (request) => request.method() === 'PUT' && request.url().endsWith(`/users/${user.id}/roles`),
  );
  await rolesForm.getByRole('button', { name: 'Save roles' }).click();
  expect((await sent).postDataJSON()).toEqual({ roleIds: [roleB.id] });
  await expect(rolesForm.getByText('Roles saved.', { exact: true })).toBeVisible();

  await expect(checkboxB).toBeChecked();
  await expect(checkboxA).not.toBeChecked();
  const saved = await api.getUser(user.id);
  expect(saved.roles.map((role) => role.id)).toEqual([roleB.id]);
  await expectHeaderUser(page);
});

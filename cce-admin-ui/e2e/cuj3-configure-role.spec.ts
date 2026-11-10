// CUJ 3: create and configure a role (S3, F-12, F-13, F-14). Every step goes through the console
// (F2 and F3 exit checks).
import type { SuccessEnvelope } from '../src/api/contract';
import { expect, expectHeaderUser, roleDisplayName, roleName, test } from './fixtures';

const NAME_WARNING =
  'The name cannot be changed later. It is also the Keycloak realm role name and the gateway permission name.';
const DETAIL_WARNING = 'Patient detail is shown only when Patients is also granted.';

test('CUJ 3: create a role, grant menus and save permissions', async ({ page, suffix }) => {
  const name = roleName('CUJ3', suffix);

  // Create, with the name warning and Confirm.
  await page.goto('/roles');
  const createForm = page.getByRole('form', { name: 'Create role' });
  await createForm.getByLabel('Name', { exact: true }).fill(name);
  await createForm
    .getByLabel('Display name', { exact: true })
    .fill(roleDisplayName('CUJ3', suffix));
  await expect(createForm.getByText(NAME_WARNING)).toBeVisible();
  await createForm.getByRole('button', { name: 'Create role' }).click();
  const dialog = page.getByRole('dialog', { name: `Create ${name}?` });
  await expect(dialog).toContainText(NAME_WARNING);
  await dialog.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page).toHaveURL(/\/roles\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { name })).toBeVisible();

  // Menus tab: Patient detail without its parent Patients.
  const menusTab = page.getByRole('tabpanel', { name: 'Menus' });
  await menusTab.getByRole('checkbox', { name: 'Compliance', exact: true }).check();
  await menusTab.getByRole('checkbox', { name: 'Patient detail', exact: true }).check();
  await expect(menusTab.getByText(DETAIL_WARNING)).toBeVisible();
  const menusSaved = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' && /\/roles\/[^/]+\/menus$/.test(response.url()),
  );
  await menusTab.getByRole('button', { name: 'Save', exact: true }).click();
  const menusResponse = await menusSaved;
  expect(menusResponse.status()).toBe(200);
  const { warnings } = (await menusResponse.json()) as SuccessEnvelope<unknown>;
  expect(warnings?.map((warning) => warning.code)).toEqual(['DETAIL_WITHOUT_PARENT']);
  await expect(menusTab.getByText('Menus saved.', { exact: true })).toBeVisible();
  await expect(menusTab.getByText(DETAIL_WARNING)).toBeVisible();

  // Permissions tab: the two implicit rows read-only, then one explicit row.
  await page.getByRole('tab', { name: 'Permissions' }).click();
  await expect(page).toHaveURL(/\?tab=permissions$/);
  const implicit = page.getByRole('table', { name: 'Always included' });
  await expect(implicit.getByRole('cell', { name: '/admin/v1/me', exact: true })).toBeVisible();
  await expect(
    implicit.getByRole('cell', { name: '/admin/v1/me/menus', exact: true }),
  ).toBeVisible();
  await expect(implicit.getByRole('row').filter({ hasText: /^GET/ })).toHaveCount(2);
  await expect(implicit.getByRole('textbox')).toHaveCount(0);

  const rules = page.getByRole('form', { name: 'Permission rules' });
  await rules.getByRole('button', { name: 'Add rule' }).click();
  await rules.getByLabel('URI pattern, row 1', { exact: true }).fill('/v1/insights/**');
  const permissionsSaved = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' && /\/roles\/[^/]+\/permissions$/.test(response.url()),
  );
  await rules.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await permissionsSaved).status()).toBe(200);
  await expect(page.getByText('Saved.', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Gateway sync' })).toContainText('Synced ·');
  await expectHeaderUser(page);
});

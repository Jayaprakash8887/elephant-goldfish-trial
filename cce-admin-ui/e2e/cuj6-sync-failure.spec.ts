// CUJ 6: recover from a failed gateway sync (S6, F-14, F-05), with the stand-in gateway switched
// to `fail` and back. The stand-in is always set to `ok` again when the test ends (UI-AD-10).
import { expect, expectHeaderUser, roleDisplayName, roleName, setStubMode, test } from './fixtures';

const CREATED_NOT_SYNCED = 'The role was created but not synced. Use Re-sync before assigning it.';
const PERMISSIONS_NOT_SYNCED =
  'The permissions were saved but not synced. Use Re-sync, and do not assign the role until a sync succeeds.';

test('CUJ 6: a role created while the sync fails is assigned after Re-sync', async ({
  page,
  api,
  suffix,
}) => {
  // 1. Role A and a user holding it, while the stand-in is `ok`.
  const roleA = await api.createRole('CUJ6A');
  const user = await api.createUser('CUJ6U', [roleA.id]);
  const nameB = roleName('CUJ6B', suffix);
  const displayB = roleDisplayName('CUJ6B', suffix);

  try {
    // 2. The sync fails from now on.
    await setStubMode('fail');

    // 3. Create role B in the console.
    await page.goto('/roles');
    const createForm = page.getByRole('form', { name: 'Create role' });
    await createForm.getByLabel('Name', { exact: true }).fill(nameB);
    await createForm.getByLabel('Display name', { exact: true }).fill(displayB);
    await createForm.getByRole('button', { name: 'Create role' }).click();
    await page
      .getByRole('dialog', { name: `Create ${nameB}?` })
      .getByRole('button', { name: 'Create', exact: true })
      .click();
    await expect(page).toHaveURL(/\/roles\/[0-9a-f-]{36}\?tab=permissions$/);
    await expect(page.getByText(CREATED_NOT_SYNCED)).toBeVisible();
    const syncPanel = page.getByRole('region', { name: 'Gateway sync' });
    await expect(syncPanel).toContainText('Sync failed ·');

    // 4. Save a rule: 502 ROLE_PERMISSIONS_SAVE; the rows are saved and refetched.
    const rules = page.getByRole('form', { name: 'Permission rules' });
    await rules.getByRole('button', { name: 'Add rule' }).click();
    await rules.getByLabel('URI pattern, row 1', { exact: true }).fill('/v1/insights/**');
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        /\/roles\/[^/]+\/permissions$/.test(response.url()),
    );
    await rules.getByRole('button', { name: 'Save', exact: true }).click();
    expect((await saved).status()).toBe(502);
    await expect(page.getByRole('alert').filter({ hasText: PERMISSIONS_NOT_SYNCED })).toBeVisible();
    await expect(rules.getByLabel('URI pattern, row 1', { exact: true })).toHaveValue(
      '/v1/insights/**',
    );
    await expect(rules.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
    await expect(syncPanel).toContainText('Sync failed ·');

    // 5. Assigning B is refused with ROLE_NOT_SYNCED. The user opens in a second tab, so the
    // role's page stays open for Re-sync and the Roles form keeps the selection it sent.
    const userPage = await page.context().newPage();
    await userPage.goto(`/users/${user.id}`);
    const rolesForm = userPage.getByRole('form', { name: 'Roles' });
    await rolesForm.getByRole('checkbox', { name: `${displayB} (${nameB})` }).check();
    await rolesForm.getByRole('button', { name: 'Save roles' }).click();
    await expect(
      rolesForm.getByText(
        `Not synced yet: ${displayB} (${nameB}). Open the role and use Re-sync.`,
        { exact: true },
      ),
    ).toBeVisible();

    // 6. The sync works again; Re-sync B.
    await setStubMode('ok');
    await syncPanel.getByRole('button', { name: 'Re-sync' }).click();
    await expect(syncPanel).toContainText('Synced ·');
    await expectHeaderUser(page);

    // 7. The same roles saved again now succeed.
    await rolesForm.getByRole('button', { name: 'Save roles' }).click();
    await expect(rolesForm.getByText('Roles saved.', { exact: true })).toBeVisible();
    await expectHeaderUser(userPage);
  } finally {
    await setStubMode('ok');
  }
});

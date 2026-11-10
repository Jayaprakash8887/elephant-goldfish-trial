// Pre-check before any browser test (item 98): the service and the stand-in gateway must answer,
// the stand-in starts in `ok` mode, and the role count must fit the role pickers (UI-AD-20).
import type { Role } from '../src/api/contract';
import { SERVICE_URL, STUB_MODE_URL } from './env';
import { apiCall, apiToken, setStubMode } from './fixtures';

const START_COMMANDS =
  'Start the admin-service stack first (admin-service §9.3), in cce-admin-service/: ' +
  '`docker compose up -d --wait`, then `./mvnw spring-boot:run` or ' +
  '`docker compose --profile app up -d --build --wait`.';

const MAX_ROLES = 400;

async function answers(url: string): Promise<boolean> {
  try {
    const response = await fetch(url);
    return response.ok;
  } catch {
    return false;
  }
}

export default async function globalSetup(): Promise<void> {
  const health = `${SERVICE_URL}/health`;
  const [serviceUp, stubUp] = await Promise.all([answers(health), answers(STUB_MODE_URL)]);
  if (!serviceUp || !stubUp) {
    const missing = [!serviceUp && health, !stubUp && STUB_MODE_URL].filter(Boolean).join(', ');
    throw new Error(`No answer from ${missing}. ${START_COMMANDS}`);
  }
  await setStubMode('ok');

  const { pagination } = await apiCall<Role[]>(await apiToken(), 'GET', '/roles?page=1&pageSize=1');
  if ((pagination?.totalItems ?? 0) > MAX_ROLES) {
    throw new Error(
      'Too many roles for the role pickers. Reset the local stack: ' +
        'docker compose --profile app down -v (admin-service §9.3).',
    );
  }
}

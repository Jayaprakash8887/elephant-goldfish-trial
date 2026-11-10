// @vitest-environment node
// Goal 7: the Vite environment is read only in src/config.ts, and only for the four PRD §8.3
// variables, so no flag or static token can bypass sign-in.
import { readdirSync, readFileSync } from 'node:fs';
import { join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const srcDir = fileURLToPath(new URL('..', import.meta.url));

function sourceFiles(): string[] {
  return readdirSync(srcDir, { recursive: true, encoding: 'utf8' })
    .map((file) => file.split(sep).join('/'))
    .filter((file) => /\.(ts|tsx)$/.test(file))
    .filter((file) => !/\.test\.(ts|tsx)$/.test(file))
    .filter((file) => !file.startsWith('test/'))
    .filter((file) => !file.includes('__lint_fixtures__'));
}

const ENV_ACCESS = /import\.meta\.env/;
const ENV_NAME = /import\.meta\.env\.([A-Za-z0-9_]+)/g;

describe('configuration source', () => {
  it('finds the source files', () => {
    const files = sourceFiles();
    expect(files).toContain('config.ts');
    expect(files).toContain('bootstrap.tsx');
  });

  it('reads the Vite environment only in src/config.ts', () => {
    const readers = sourceFiles().filter((file) =>
      ENV_ACCESS.test(readFileSync(join(srcDir, file), 'utf8')),
    );
    expect(readers).toEqual(['config.ts']);
  });

  it('reads exactly the four PRD §8.3 variables', () => {
    const source = readFileSync(join(srcDir, 'config.ts'), 'utf8');
    const names = new Set([...source.matchAll(ENV_NAME)].map((match) => match[1]));
    expect([...names].sort()).toEqual([
      'VITE_API_BASE_URL',
      'VITE_KEYCLOAK_CLIENT_ID',
      'VITE_KEYCLOAK_REALM',
      'VITE_KEYCLOAK_URL',
    ]);
  });
});

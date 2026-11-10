// @vitest-environment node
// Checks the four import rules of eslint.config.js against the seven lint fixtures (item 96).
import { ESLint } from 'eslint';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('../../..', import.meta.url));

const VIOLATIONS = [
  'src/pages/__lint_fixtures__/importsApi.tsx',
  'src/components/__lint_fixtures__/importsHook.tsx',
  'src/components/__lint_fixtures__/importsQuery.tsx',
  'src/utils/__lint_fixtures__/importsKeycloak.ts',
  'src/utils/__lint_fixtures__/importsMsw.ts',
  'src/auth/__lint_fixtures__/importsPlaywright.ts',
];

const ALLOWED = 'src/pages/__lint_fixtures__/importsContractType.tsx';

describe('import rules', { timeout: 60_000 }, () => {
  let results: ESLint.LintResult[];

  beforeAll(async () => {
    const eslint = new ESLint({ cwd: projectRoot, ignore: false });
    results = await eslint.lintFiles([...VIOLATIONS, ALLOWED]);
  }, 60_000);

  function messagesFor(file: string) {
    const result = results.find((r) => r.filePath.endsWith(file));
    if (!result) throw new Error(`No lint result for ${file}`);
    return result.messages;
  }

  it.each(VIOLATIONS)('%s reports no-restricted-imports', (file) => {
    const messages = messagesFor(file);
    expect(messages.map((m) => m.ruleId)).toEqual(['@typescript-eslint/no-restricted-imports']);
  });

  it('the allowed type import reports nothing', () => {
    expect(messagesFor(ALLOWED)).toEqual([]);
  });
});

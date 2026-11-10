import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import prettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// "Test files" for the import rules (cce-admin-ui design, item 9).
const testFiles = ['**/*.test.{ts,tsx}', 'src/test/**'];

const msg = {
  pagesApi: 'Pages never import api/ modules (PRD §10); use a hook.',
  componentsApi: 'Components never import api/ modules (PRD §10); data arrives as props.',
  contractTypesOnly:
    'Pages and components may import only types from api/contract (use `import type`).',
  componentsHooks: 'Components never import hooks/ (PRD §10); data and callbacks arrive as props.',
  componentsQuery: 'Components never import @tanstack/react-query; data arrives as props.',
  keycloak: 'Only src/auth/** and test files may import keycloak-js (F-01).',
  msw: 'Only test files, src/test/** and e2e/** may import msw.',
  playwright: 'Only e2e/**, playwright.config.ts and test files may import @playwright/test.',
};

const mswPattern = { group: ['msw/*'], message: msg.msw };
const mswPath = { name: 'msw', message: msg.msw };
const playwrightPath = { name: '@playwright/test', message: msg.playwright };
const keycloakPath = { name: 'keycloak-js', message: msg.keycloak };

export default defineConfig([
  globalIgnores([
    'dist',
    'coverage',
    'playwright-report',
    'test-results',
    'e2e/.auth',
    '**/__lint_fixtures__/**',
  ]),

  // Base
  js.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    extends: [tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Coding conventions: onSubmit={handleSubmit(fn)} passes; unawaited promises use `void`.
      '@typescript-eslint/no-misused-promises': [
        'error',
        { checksVoidReturn: { attributes: false } },
      ],
      '@typescript-eslint/no-floating-promises': 'error',
    },
  },
  reactHooks.configs['recommended-latest'],
  {
    files: ['src/**'],
    languageOptions: { globals: globals.browser },
  },
  {
    files: ['*.config.{js,ts}', 'e2e/**'],
    languageOptions: { globals: globals.node },
  },
  {
    files: testFiles,
    rules: {
      // Assertions such as expect(instance.updateToken) reference methods unbound.
      '@typescript-eslint/unbound-method': 'off',
    },
  },

  // Import rules: five file groups that do not overlap, one block each.
  {
    files: ['src/components/**/*.{ts,tsx}'],
    ignores: testFiles,
    rules: {
      '@typescript-eslint/no-restricted-imports': [
        'error',
        {
          paths: [
            { name: '@tanstack/react-query', message: msg.componentsQuery },
            keycloakPath,
            mswPath,
            playwrightPath,
          ],
          patterns: [
            { group: ['**/api/contract'], allowTypeImports: true, message: msg.contractTypesOnly },
            { group: ['**/api/*', '!**/api/contract'], message: msg.componentsApi },
            { group: ['**/hooks/*'], message: msg.componentsHooks },
            mswPattern,
          ],
        },
      ],
    },
  },
  {
    files: ['src/pages/**/*.{ts,tsx}'],
    ignores: testFiles,
    rules: {
      '@typescript-eslint/no-restricted-imports': [
        'error',
        {
          paths: [keycloakPath, mswPath, playwrightPath],
          patterns: [
            { group: ['**/api/contract'], allowTypeImports: true, message: msg.contractTypesOnly },
            { group: ['**/api/*', '!**/api/contract'], message: msg.pagesApi },
            mswPattern,
          ],
        },
      ],
    },
  },
  {
    files: ['src/auth/**/*.{ts,tsx}'],
    ignores: testFiles,
    rules: {
      '@typescript-eslint/no-restricted-imports': [
        'error',
        {
          paths: [mswPath, playwrightPath],
          patterns: [mswPattern],
        },
      ],
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: [
      ...testFiles,
      'src/components/**/*.{ts,tsx}',
      'src/pages/**/*.{ts,tsx}',
      'src/auth/**/*.{ts,tsx}',
    ],
    rules: {
      '@typescript-eslint/no-restricted-imports': [
        'error',
        {
          paths: [keycloakPath, mswPath, playwrightPath],
          patterns: [mswPattern],
        },
      ],
    },
  },
  {
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}', 'e2e/**/*.ts', 'playwright.config.ts'],
    rules: {
      '@typescript-eslint/no-restricted-imports': 'off',
    },
  },

  prettier,
]);

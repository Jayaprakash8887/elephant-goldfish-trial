// Lint fixture, rule 4: only tests may import @playwright/test, also in auth/ (lintRules.test.ts).
import { test } from '@playwright/test';

export const fixture = test;

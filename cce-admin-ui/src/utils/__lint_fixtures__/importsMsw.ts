// Lint fixture, rule 4: only tests may import msw (checked by lintRules.test.ts).
import { http } from 'msw';

export const fixture = http;

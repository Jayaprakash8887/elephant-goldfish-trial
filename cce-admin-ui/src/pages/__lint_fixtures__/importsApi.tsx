// Lint fixture, rule 1: pages may not import values from api/ (checked by lintRules.test.ts).
import { getMe } from '../../api/me';

export const fixture = getMe;

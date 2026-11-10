// Lint fixture, rule 2: components may not import hooks/ (checked by lintRules.test.ts).
import { useMe } from '../../hooks/useMe';

export const fixture = useMe;

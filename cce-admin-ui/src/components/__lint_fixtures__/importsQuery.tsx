// Lint fixture, rule 2: components may not import @tanstack/react-query (lintRules.test.ts).
import { useQuery } from '@tanstack/react-query';

export const fixture = useQuery;

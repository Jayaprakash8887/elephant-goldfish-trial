// Lint fixture, rule 3: only auth/ and tests may import keycloak-js (lintRules.test.ts).
import Keycloak from 'keycloak-js';

export const fixture = Keycloak;

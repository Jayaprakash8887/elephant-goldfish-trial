#!/usr/bin/env node
// Gets a token from the local Keycloak realm "cce" and prints its claims (Phase 1 of the design).
//
//   node scripts/token-claims.mjs admin [--token]    password grant from cce-e2e, test administrator
//   node scripts/token-claims.mjs service [--token]  client-credentials grant of cce-admin-service
//
// Without --token it prints iss, aud, realm_access.roles and auth_time. With --token it prints only
// the raw access token. Values come from .env (KEY=VALUE, ${...} resolved from earlier keys).
// Node 22, no dependencies.
//
// DESIGN-GAP: Phase 1 exit check step 3 expects an auth_time in the "admin" token. Keycloak 26.4.7
// sets the AUTH_TIME session note (read by the auth_time mapper of the basic scope) only after a
// browser (authorization code) sign-in, never for the password grant this script must use, so
// the admin token has no auth_time and the script prints "(absent)". Tokens from cce-admin-ui's
// browser sign-in do carry auth_time.

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOKEN_ENDPOINT = 'http://localhost:8180/auth/realms/cce/protocol/openid-connect/token';

function fail(message) {
  console.error(`token-claims: ${message}`);
  process.exit(1);
}

// DESIGN-GAP: the design says the script "reads .env" without saying from which directory. It
// reads the .env next to the project's docker-compose.yml (the parent of scripts/), so the
// script works whatever the current directory is.
function readEnvFile() {
  const envPath = resolve(dirname(fileURLToPath(import.meta.url)), '..', '.env');
  let text;
  try {
    text = readFileSync(envPath, 'utf8');
  } catch (e) {
    fail(`cannot read ${envPath} (copy .env.example to .env first): ${e.message}`);
  }
  const values = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    const value = line
      .slice(eq + 1)
      .trim()
      .replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, name) => values[name] ?? '');
    values[key] = value;
  }
  return values;
}

function required(env, key) {
  const value = env[key];
  if (!value) fail(`${key} is not set in .env`);
  return value;
}

function decodePayload(token) {
  const parts = token.split('.');
  if (parts.length !== 3) fail('the access token is not a JWT');
  return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
}

async function requestToken(form) {
  let response;
  try {
    response = await fetch(TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(form),
    });
  } catch (e) {
    fail(`cannot reach ${TOKEN_ENDPOINT}: ${e.cause?.message ?? e.message}`);
  }
  const body = await response.text();
  if (!response.ok) fail(`token request failed with HTTP ${response.status}: ${body.slice(0, 500)}`);
  const accessToken = JSON.parse(body).access_token;
  if (!accessToken) fail('the token response has no access_token');
  return accessToken;
}

const args = process.argv.slice(2);
const rawOnly = args.includes('--token');
const mode = args.find((a) => !a.startsWith('--'));
if (mode !== 'admin' && mode !== 'service') {
  fail('usage: node scripts/token-claims.mjs admin|service [--token]');
}

const env = readEnvFile();
const form =
  mode === 'admin'
    ? {
        grant_type: 'password',
        client_id: 'cce-e2e',
        username: required(env, 'CCE_TEST_ADMIN_USERNAME'),
        password: required(env, 'CCE_TEST_ADMIN_PASSWORD'),
      }
    : {
        grant_type: 'client_credentials',
        client_id: 'cce-admin-service',
        client_secret: required(env, 'KEYCLOAK_ADMIN_CLIENT_SECRET'),
      };

const token = await requestToken(form);
if (rawOnly) {
  process.stdout.write(`${token}\n`);
} else {
  const claims = decodePayload(token);
  const show = (v) => (v === undefined ? '(absent)' : JSON.stringify(v));
  console.log(`iss: ${show(claims.iss)}`);
  console.log(`aud: ${show(claims.aud)}`);
  console.log(`realm_access.roles: ${show(claims.realm_access?.roles)}`);
  console.log(`auth_time: ${show(claims.auth_time)}`);
}

// Pure helpers over permission rows (design, "Permissions tab"). Rows are compared the way the
// service decides a no-op (admin-service §5.7): method upper case, strings trimmed, an empty
// description or category treated as absent, and the rows taken as a set of
// (method, pattern, description, category).
import type { PermissionRow, PermissionRowInput } from '../api/contract';

/** A row from the server (`null` when empty) or from the rules form (`''` when empty). */
export interface RuleLike {
  httpMethod: string;
  uriPattern: string;
  description?: string | null;
  resourceCategory?: string | null;
}

/** Trims an optional string; `undefined` when it is empty. */
function optional(value: string | null | undefined): string | undefined {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? undefined : trimmed;
}

/** The row in its compared and sent form; empty optional fields are left out. */
export function normalizeRow(row: RuleLike): PermissionRowInput {
  const normalized: PermissionRowInput = {
    httpMethod: row.httpMethod.trim().toUpperCase(),
    uriPattern: row.uriPattern.trim(),
  };
  const description = optional(row.description);
  const resourceCategory = optional(row.resourceCategory);
  if (description !== undefined) normalized.description = description;
  if (resourceCategory !== undefined) normalized.resourceCategory = resourceCategory;
  return normalized;
}

/** The rows an administrator edits: every row that is not implicit (admin-service AD-16). */
export function explicitRows(rows: readonly PermissionRow[]): PermissionRow[] {
  return rows.filter((row) => !row.implicit);
}

function ruleKey(row: RuleLike): string {
  const { httpMethod, uriPattern, description, resourceCategory } = normalizeRow(row);
  return JSON.stringify([httpMethod, uriPattern, description ?? null, resourceCategory ?? null]);
}

/** True when both sides hold the same set of normalized rules, in any order. */
export function sameRuleSet(a: readonly RuleLike[], b: readonly RuleLike[]): boolean {
  const left = new Set(a.map(ruleKey));
  const right = new Set(b.map(ruleKey));
  return left.size === right.size && [...left].every((key) => right.has(key));
}

/** The request rows, built field by field; empty description and category are left out. */
export function toRequestRows(formRows: readonly RuleLike[]): PermissionRowInput[] {
  return formRows.map(normalizeRow);
}

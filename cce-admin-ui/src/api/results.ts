import type { PageInfo, Warning } from './contract';

/** A page of a paginated list. */
export type Page<T> = { items: T[]; pagination: PageInfo };

/** A result with the envelope's warnings; `warnings` is `[]` when the envelope has none. */
export type WithWarnings<T> = { data: T; warnings: Warning[] };

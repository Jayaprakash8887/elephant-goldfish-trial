import { useEffect } from 'react';
import { useSearchParams } from 'react-router';
import { PAGE_SIZES, type PageSize } from '../schemas/rules';

const DEFAULT_PAGE_SIZE: PageSize = 20;

/**
 * A `page` that is not a whole number of at least 1 becomes 1, and a `pageSize` other than 20, 50
 * or 100 becomes 20, because the service rejects out-of-range values instead of clamping them.
 */
export function normalizePagingParams(params: URLSearchParams): {
  page: number;
  pageSize: PageSize;
  changed: boolean;
} {
  const rawPage = params.get('page');
  const rawPageSize = params.get('pageSize');
  const page = rawPage === null ? 1 : wholeNumberAtLeastOne(rawPage);
  const pageSize =
    rawPageSize === null
      ? DEFAULT_PAGE_SIZE
      : PAGE_SIZES.find((size) => String(size) === rawPageSize);
  return {
    page: page ?? 1,
    pageSize: pageSize ?? DEFAULT_PAGE_SIZE,
    changed: page === undefined || pageSize === undefined,
  };
}

function wholeNumberAtLeastOne(value: string): number | undefined {
  if (!/^\d+$/.test(value)) return undefined;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 1 ? n : undefined;
}

/** `totalPages` when `page > totalPages >= 1`, otherwise `page`. */
export function clampPageToTotal(page: number, totalPages: number): number {
  return totalPages >= 1 && page > totalPages ? totalPages : page;
}

/** `page` and `pageSize` from the URL; every setter keeps the other search parameters. */
export function usePagingParams() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { page, pageSize, changed } = normalizePagingParams(searchParams);

  useEffect(() => {
    if (!changed) return;
    const next = new URLSearchParams(searchParams);
    next.set('page', String(page));
    next.set('pageSize', String(pageSize));
    void setSearchParams(next, { replace: true });
  }, [changed, page, pageSize, searchParams, setSearchParams]);

  function update(values: { page: number; pageSize?: number }) {
    void setSearchParams((current) => {
      const next = new URLSearchParams(current);
      next.set('page', String(values.page));
      if (values.pageSize !== undefined) next.set('pageSize', String(values.pageSize));
      return next;
    });
  }

  return {
    page,
    pageSize,
    setPage: (n: number) => update({ page: n }),
    setPageSize: (n: PageSize) => update({ page: 1, pageSize: n }),
    resetPage: () => update({ page: 1 }),
  };
}

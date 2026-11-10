import { PAGE_SIZES, type PageSize } from '../../schemas/rules';

export function Pager({
  page,
  totalPages,
  totalItems,
  pageSize,
  onPageChange,
  onPageSizeChange,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  pageSize: PageSize;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: PageSize) => void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 text-sm">
      <button
        type="button"
        onClick={() => onPageChange(page - 1)}
        disabled={page <= 1}
        className="rounded border border-gray-300 px-2 py-1 disabled:opacity-50"
      >
        Previous
      </button>
      <span>
        Page {page} of {totalPages} · {totalItems} items
      </span>
      <button
        type="button"
        onClick={() => onPageChange(page + 1)}
        disabled={page >= totalPages}
        className="rounded border border-gray-300 px-2 py-1 disabled:opacity-50"
      >
        Next
      </button>
      <label className="flex items-center gap-1">
        {/* DESIGN-GAP: the design does not name the page-size select; "Page size" is used. */}
        Page size
        <select
          value={pageSize}
          onChange={(event) => {
            const size = PAGE_SIZES.find((s) => String(s) === event.target.value);
            if (size !== undefined) onPageSizeChange(size);
          }}
          className="rounded border border-gray-300 px-1 py-0.5"
        >
          {PAGE_SIZES.map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

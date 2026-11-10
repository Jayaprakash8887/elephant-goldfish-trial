import { format, parseISO } from 'date-fns';

/** `yyyy-MM-dd HH:mm` in the browser's local time; `empty` when there is no instant. */
export function formatInstant(iso: string | null, empty = 'Never'): string {
  if (iso === null) return empty;
  return format(parseISO(iso), 'yyyy-MM-dd HH:mm');
}

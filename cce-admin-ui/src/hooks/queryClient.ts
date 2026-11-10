import { MutationCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/ApiError';
import { describeError } from '../utils/describeError';

/** Queries retry once, and only for status 0 or 5xx: retrying a 4xx cannot succeed (UI-AD-18). */
function retryOnce(failureCount: number, error: unknown): boolean {
  if (failureCount >= 1) return false;
  return error instanceof ApiError && (error.status === 0 || error.status >= 500);
}

/** Kinds after which a write may have happened, so every active query is refetched. */
const REFETCH_KINDS: ReadonlySet<string> = new Set(['syncFailed', 'network', 'unexpected']);

export function createQueryClient(options: { retry?: boolean } = {}): QueryClient {
  // The refetch rule (F-06): after a 2xx, a 502, a network error or an unexpected error, refetch
  // every active query. After a 4xx with the service's envelope nothing changed, so nothing is
  // refetched. Mutations never touch the cache before the server answers (no optimistic updates).
  const mutationCache = new MutationCache({
    onSettled: (_data, error) => {
      if (error === null || REFETCH_KINDS.has(describeError(error).kind)) {
        void queryClient.invalidateQueries();
      }
    },
  });
  const queryClient = new QueryClient({
    mutationCache,
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: options.retry === false ? false : retryOnce,
      },
      mutations: { retry: 0 },
    },
  });
  return queryClient;
}

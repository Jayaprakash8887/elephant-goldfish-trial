// Records every request the default handlers receive, so tests assert on exactly what the
// console sent (UI-AD-19).

export interface RecordedRequest {
  method: string;
  path: string;
  query: Record<string, string>;
  body: unknown;
}

let recorded: RecordedRequest[] = [];

export async function recordRequest(request: Request): Promise<void> {
  const url = new URL(request.url);
  const text = await request.clone().text();
  let body: unknown = undefined;
  if (text) {
    try {
      body = JSON.parse(text) as unknown;
    } catch {
      body = text;
    }
  }
  recorded.push({
    method: request.method,
    path: url.pathname,
    query: Object.fromEntries(url.searchParams),
    body,
  });
}

export function recordedRequests(): readonly RecordedRequest[] {
  return recorded;
}

export function clearRequests(): void {
  recorded = [];
}

import { vi } from "vitest";

export type Route = [method: string, pattern: RegExp, respond: (init: RequestInit) => { status?: number; body: unknown }];

/** Installs a fetch mock that serves the given routes and records every request. */
export function mockFetch(routes: Route[]) {
  const calls: { method: string; url: string; headers: Headers; body: unknown }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    const method = (init.method ?? "GET").toUpperCase();
    calls.push({ method, url, headers: new Headers(init.headers), body: init.body ? JSON.parse(String(init.body)) : undefined });
    const route = routes.find(([m, pattern]) => m === method && pattern.test(url));
    if (!route) return new Response(JSON.stringify({ detail: `no mock for ${method} ${url}` }), { status: 404 });
    const { status = 200, body } = route[2](init);
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
  vi.stubGlobal("fetch", fetchMock);
  return calls;
}

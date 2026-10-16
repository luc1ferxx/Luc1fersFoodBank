/** Response builders and request helpers shared by the fake backend and the tests. */

export const json = (body: unknown, init: ResponseInit = {}): Response =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });

/** An error body shaped like the backend's ApiErrorResponse. */
export const apiError = (
  status: number,
  message: string,
  { traceId, code = "ERROR" }: { traceId?: string; code?: string } = {},
): Response =>
  json(
    { code, message, status, trace_id: traceId },
    { status, headers: { "Content-Type": "application/json" } },
  );

export const noContent = (status = 204) => new Response(null, { status });

export interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}

export const deferred = <T = Response>(): Deferred<T> => {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

/** One request the fake backend received. */
export interface FakeRequest {
  url: string;
  path: string;
  query: URLSearchParams;
  method: string;
  body: string | null;
  headers: Headers;
  signal: AbortSignal | null | undefined;
}

export type FakeHandler = (
  request: FakeRequest,
  match: RegExpExecArray,
) => Response | Promise<Response>;

export type FakeRoute = [method: string, pattern: RegExp, handler: FakeHandler];

/** The JSON body of a recorded request, as `unknown` for assertions and decoders. */
export const requestJson = (request: FakeRequest | undefined): unknown => {
  const parsed: unknown = JSON.parse(request?.body ?? "null");
  return parsed;
};

/** The Idempotency-Key header of each request. */
export const idempotencyKeys = (requests: FakeRequest[]): (string | null)[] =>
  requests.map((request) => request.headers.get("Idempotency-Key"));

/** The first capture group of a route pattern as a number (the id in `/orders/(\d+)`). */
export const idParam = (match: RegExpExecArray) => Number(match[1]);

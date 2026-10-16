import { isJsonObject } from "./decode";

export interface ApiErrorDetails {
  status?: number | undefined;
  /** The backend's machine-readable error code, e.g. CART_RESTAURANT_CONFLICT. */
  code?: string | undefined;
  traceId?: string | undefined;
  body?: string | undefined;
  cause?: unknown;
}

export class ApiError extends Error {
  readonly status: number | undefined;
  readonly code: string | undefined;
  readonly traceId: string | undefined;
  readonly body: string | undefined;

  constructor(message: string, { status, code, traceId, body, cause }: ApiErrorDetails = {}) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.body = body;
  }
}

/** True when `error` is an ApiError the backend tagged with `code`. */
export const hasErrorCode = (error: unknown, code: string): boolean =>
  error instanceof ApiError && error.code === code;

const statusMessages: Readonly<Record<number, string>> = {
  400: "The request is invalid. Check the fields and try again.",
  401: "Sign in to continue.",
  403: "This account is not allowed to perform that action.",
  404: "The requested record was not found.",
  409: "The server rejected this state change. Refresh and try again.",
  429: "Too many requests. Wait a moment and try again.",
  500: "The server failed to complete the request.",
};

const parseJson = (text: string): unknown => {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed;
  } catch {
    return null;
  }
};

const stringField = (body: unknown, key: string): string | undefined => {
  if (!isJsonObject(body)) {
    return undefined;
  }
  const value = body[key];
  return typeof value === "string" && value !== "" ? value : undefined;
};

type UnauthorizedListener = (error: ApiError) => void;

const unauthorizedListeners = new Set<UnauthorizedListener>();

/**
 * Registers a callback for 401 responses from endpoints that need a signed-in
 * session. Returns the unsubscribe function, so it can be returned from an effect.
 */
export const onUnauthorized = (listener: UnauthorizedListener): (() => void) => {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
};

const notifyUnauthorized = (error: ApiError) => {
  unauthorizedListeners.forEach((listener) => listener(error));
};

// Not `instanceof Error`: fetch rejects with a DOMException, which need not share the
// realm of the page's Error (it does not under jsdom).
export const isAbortError = (error: unknown): boolean =>
  typeof error === "object" && error !== null && "name" in error && error.name === "AbortError";

export const createIdempotencyKey = (): string => {
  const cryptoApi = typeof window !== "undefined" ? window.crypto : undefined;

  if (typeof cryptoApi?.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }

  return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  headers?: Record<string, string>;
  body?: string;
  signal?: AbortSignal | undefined;
  fallbackMessage?: string;
  reportUnauthorized?: boolean;
}

/**
 * fetch wrapper for the backend JSON API.
 *
 * - Sends the session cookie and forwards `signal`, so callers can abort stale requests.
 * - Throws ApiError for non-2xx responses, carrying the status, error code, and X-Trace-Id.
 * - A 401 from an endpoint that requires a session tells every onUnauthorized listener
 *   that the session is gone. `/login` and `/me` opt out with reportUnauthorized: false
 *   because a 401 there means bad credentials or a guest visitor.
 * - Resolves with the parsed JSON body as `unknown` (null for an empty or non-JSON
 *   body); the endpoint functions decode it into typed DTOs.
 */
export const request = async (
  url: string,
  {
    method = "GET",
    headers,
    body,
    signal,
    fallbackMessage = "Request failed",
    reportUnauthorized = true,
  }: RequestOptions = {},
): Promise<unknown> => {
  let response: Response;

  try {
    response = await fetch(url, {
      method,
      credentials: "include",
      headers,
      body,
      signal,
    });
  } catch (error) {
    if (isAbortError(error) || signal?.aborted) {
      throw error;
    }
    throw new ApiError("Unable to reach the server. Check the connection and try again.", {
      cause: error,
    });
  }

  if (response.status < 200 || response.status >= 300) {
    const text = await response.text();
    const parsed = text ? parseJson(text) : null;
    const message = isJsonObject(parsed)
      ? (stringField(parsed, "message") ??
        stringField(parsed, "error") ??
        statusMessages[response.status] ??
        fallbackMessage)
      : text || statusMessages[response.status] || fallbackMessage;
    const error = new ApiError(message, {
      status: response.status,
      code: stringField(parsed, "code"),
      traceId: response.headers.get("X-Trace-Id") || stringField(parsed, "trace_id"),
      body: text,
    });

    if (response.status === 401 && reportUnauthorized) {
      notifyUnauthorized(error);
    }
    throw error;
  }

  const contentType = response.headers.get("content-type");

  if (contentType && contentType.includes("application/json")) {
    const json: unknown = await response.json();
    return json;
  }

  return null;
};

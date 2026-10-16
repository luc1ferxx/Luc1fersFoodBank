export class ApiError extends Error {
  constructor(message, { status, traceId, body, cause } = {}) {
    super(message, cause ? { cause } : undefined);
    this.name = "ApiError";
    this.status = status;
    this.traceId = traceId;
    this.body = body;
  }
}

const statusMessages = {
  400: "The request is invalid. Check the fields and try again.",
  401: "Sign in to continue.",
  403: "This account is not allowed to perform that action.",
  404: "The requested record was not found.",
  409: "The server rejected this state change. Refresh and try again.",
  429: "Too many requests. Wait a moment and try again.",
  500: "The server failed to complete the request.",
};

const parseErrorBody = (body) => {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
};

const unauthorizedListeners = new Set();

/**
 * Registers a callback for 401 responses from endpoints that need a signed-in
 * session. Returns the unsubscribe function, so it can be returned from an effect.
 */
export const onUnauthorized = (listener) => {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
};

const notifyUnauthorized = (error) => {
  unauthorizedListeners.forEach((listener) => listener(error));
};

export const isAbortError = (error) => error?.name === "AbortError";

export const createIdempotencyKey = () => {
  const cryptoApi = typeof window !== "undefined" ? window.crypto : undefined;

  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID();
  }

  return `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

/**
 * fetch wrapper for the backend JSON API.
 *
 * - Sends the session cookie and forwards `signal`, so callers can abort stale requests.
 * - Throws ApiError for non-2xx responses, carrying the status and X-Trace-Id.
 * - A 401 from an endpoint that requires a session tells every onUnauthorized listener
 *   that the session is gone. `/login` and `/me` opt out with reportUnauthorized: false
 *   because a 401 there means bad credentials or a guest visitor.
 */
export const request = async (
  url,
  {
    method = "GET",
    headers,
    body,
    signal,
    fallbackMessage = "Request failed",
    reportUnauthorized = true,
  } = {},
) => {
  let response;

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
    const parsed = text ? parseErrorBody(text) : null;
    const message = parsed
      ? parsed.message || parsed.error || statusMessages[response.status] || fallbackMessage
      : text || statusMessages[response.status] || fallbackMessage;
    const error = new ApiError(message, {
      status: response.status,
      traceId: response.headers.get("X-Trace-Id") || parsed?.trace_id,
      body: text,
    });

    if (response.status === 401 && reportUnauthorized) {
      notifyUnauthorized(error);
    }
    throw error;
  }

  const contentType = response.headers.get("content-type");

  if (contentType && contentType.includes("application/json")) {
    return response.json();
  }

  return null;
};

import { useCallback, useEffect, useState } from "react";
import { ApiError, isAbortError } from "../api/client";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

interface Snapshot<TKey extends string, TData> {
  key: TKey;
  token: number;
  data: TData | null;
  error: string;
  /** The HTTP status of the failed read, null after a successful one or a network error. */
  errorStatus: number | null;
}

/**
 * A server resource read with GET, keyed by `key` (a status filter, say). It is read
 * again when the key changes and on `reload()`, and only the newest read may update
 * the screen: switching the filter while a read runs drops that read's response.
 *
 * `applyAndReload(change)` is for command results. It drops the read in flight (it
 * may have started before the command and carry the old state), applies the
 * command's result to the data on screen at once, and reads again.
 *
 * `fetcher` must be stable (declare it at module level): it is an effect dependency.
 */
export function useLiveResource<TKey extends string, TData>(
  key: TKey,
  fetcher: (key: TKey, signal: AbortSignal) => Promise<TData>,
  fallbackMessage: string,
) {
  const [token, setToken] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot<TKey, TData> | null>(null);
  const nextReadSignal = useLatestRequest();

  useEffect(() => {
    const signal = nextReadSignal();
    fetcher(key, signal)
      .then((data) => {
        if (!signal.aborted) {
          setSnapshot({ key, token, data, error: "", errorStatus: null });
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          // Keep what is on screen for this key; a failed refresh does not blank it.
          setSnapshot((current) => ({
            key,
            token,
            data: current?.key === key ? current.data : null,
            error: errorCopy(requestError, fallbackMessage),
            errorStatus: requestError instanceof ApiError ? (requestError.status ?? null) : null,
          }));
        }
      });
  }, [key, token, fetcher, fallbackMessage, nextReadSignal]);

  const reload = useCallback(() => setToken((current) => current + 1), []);

  const applyAndReload = useCallback(
    (change: (data: TData) => TData) => {
      nextReadSignal();
      setSnapshot((current) =>
        current && current.data !== null ? { ...current, data: change(current.data) } : current,
      );
      setToken((current) => current + 1);
    },
    [nextReadSignal],
  );

  const current = snapshot?.key === key ? snapshot : null;

  return {
    /** The data read for the current key, or null before the first read for it answers. */
    data: current ? current.data : null,
    error: current ? current.error : "",
    /** The HTTP status of the latest read for the current key when it failed (403, say). */
    errorStatus: current ? current.errorStatus : null,
    /** A read for the current key and reload count has not answered yet. */
    loading: snapshot === null || snapshot.key !== key || snapshot.token !== token,
    reload,
    applyAndReload,
  };
}

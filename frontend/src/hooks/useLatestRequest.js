import { useCallback, useEffect, useRef } from "react";

/**
 * Returns `next()`, which aborts the previous request started through it and hands
 * out a fresh AbortSignal. Callers check `signal.aborted` before applying a
 * response, so a slow, superseded response can never overwrite a newer one, even
 * when a mocked or polyfilled fetch ignores the signal. Unmounting aborts the
 * in-flight request.
 */
export function useLatestRequest() {
  const controllerRef = useRef(null);

  useEffect(
    () => () => {
      controllerRef.current?.abort();
    },
    [],
  );

  return useCallback(() => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    return controller.signal;
  }, []);
}

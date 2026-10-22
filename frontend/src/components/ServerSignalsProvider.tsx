import type { ReactNode } from "react";
import { ServerSignalsContext, useEventStream } from "../hooks/useServerSignals";

interface ServerSignalsProviderProps {
  /** A user is signed in; a guest gets no stream. */
  signedIn: boolean;
  children: ReactNode;
}

/**
 * One change-signal stream (GET /events/stream, ADR 0003) for every screen of the
 * signed-in user. App renders it inside the shell it keys by the user, so logging out or
 * an expired session closes the stream and the next login opens a new one.
 */
export function ServerSignalsProvider({ signedIn, children }: ServerSignalsProviderProps) {
  const signals = useEventStream(signedIn);
  return <ServerSignalsContext value={signals}>{children}</ServerSignalsContext>;
}

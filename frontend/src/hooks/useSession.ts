import { useCallback, useEffect, useReducer } from "react";
import { isAbortError, onUnauthorized } from "../api/client";
import type { Credentials, CurrentUser, SignupRequest } from "../api/dto";
import {
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  signup as signupRequest,
} from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

export const SESSION_EXPIRED_MESSAGE = "Your session expired. Sign in again to keep ordering.";

/** "loading" (probing /me) | "signed-in" | "guest" | "expired" (a protected call returned 401) */
export type SessionStatus = "loading" | "signed-in" | "guest" | "expired";
export type AuthMode = "login" | "signup";

export interface SessionState {
  user: CurrentUser | null;
  status: SessionStatus;
  mode: AuthMode;
  pending: boolean;
  message: string;
  error: string;
}

export type SessionAction =
  | { type: "restored"; user: CurrentUser | null }
  | { type: "mode"; mode: AuthMode }
  | { type: "pending" }
  | { type: "failed"; error: string }
  | { type: "signedIn"; user: CurrentUser }
  | { type: "signedUp" }
  | { type: "signedOut" }
  | { type: "expired" }
  | { type: "sessionRequired"; error: string };

const initialState: SessionState = {
  user: null,
  status: "loading",
  mode: "login",
  pending: false,
  message: "",
  error: "",
};

export function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  switch (action.type) {
    case "restored":
      return action.user
        ? { ...state, user: action.user, status: "signed-in" }
        : { ...state, user: null, status: "guest" };
    case "mode":
      return { ...state, mode: action.mode, error: "" };
    case "pending":
      return { ...state, pending: true, message: "", error: "" };
    case "failed":
      return { ...state, pending: false, message: "", error: action.error };
    case "signedIn":
      return {
        ...state,
        user: action.user,
        status: "signed-in",
        pending: false,
        message: "Signed in. Cart, orders, and notifications are now active.",
        error: "",
      };
    case "signedUp":
      return {
        ...state,
        mode: "login",
        pending: false,
        message: "Account created. Sign in with the new credentials.",
        error: "",
      };
    case "signedOut":
      return { ...initialState, status: "guest", message: "Signed out." };
    case "expired":
      // Only a session we believed was live can expire; guests and repeated 401s are no-ops.
      return state.user
        ? { ...initialState, status: "expired", error: SESSION_EXPIRED_MESSAGE }
        : state;
    case "sessionRequired":
      return { ...state, mode: "login", message: "", error: action.error };
  }
}

export function useSession() {
  const [state, dispatch] = useReducer(sessionReducer, initialState);
  const nextSessionSignal = useLatestRequest();

  useEffect(() => {
    const signal = nextSessionSignal();
    getCurrentUser({ signal })
      .then((user) => {
        if (!signal.aborted) {
          dispatch({ type: "restored", user });
        }
      })
      .catch((error: unknown) => {
        if (!signal.aborted && !isAbortError(error)) {
          dispatch({ type: "restored", user: null });
        }
      });
  }, [nextSessionSignal]);

  // Any protected request that comes back 401 means the server-side session is gone.
  useEffect(() => onUnauthorized(() => dispatch({ type: "expired" })), []);

  const setMode = useCallback((mode: AuthMode) => dispatch({ type: "mode", mode }), []);

  const login = useCallback(
    async ({ username, password }: Credentials): Promise<boolean> => {
      if (!username || !password) {
        dispatch({ type: "failed", error: "Email and password are required." });
        return false;
      }

      dispatch({ type: "pending" });
      try {
        await loginRequest({ username, password });
        const signal = nextSessionSignal();
        const user = await getCurrentUser({ signal });
        if (signal.aborted) {
          return false;
        }
        dispatch({ type: "signedIn", user });
        return true;
      } catch (error) {
        if (!isAbortError(error)) {
          dispatch({ type: "failed", error: errorCopy(error, "Login failed.") });
        }
        return false;
      }
    },
    [nextSessionSignal],
  );

  const signup = useCallback(async (data: SignupRequest): Promise<boolean> => {
    if (!data.email || !data.password || !data.first_name || !data.last_name) {
      dispatch({
        type: "failed",
        error: "Email, password, first name, and last name are required.",
      });
      return false;
    }

    dispatch({ type: "pending" });
    try {
      await signupRequest(data);
      dispatch({ type: "signedUp" });
      return true;
    } catch (error) {
      dispatch({ type: "failed", error: errorCopy(error, "Signup failed.") });
      return false;
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } catch {
      // The local session is cleared either way; the cookie is useless once the server drops it.
    }
    dispatch({ type: "signedOut" });
  }, []);

  const requireSession = useCallback((error: string) => {
    dispatch({ type: "sessionRequired", error });
    document
      .getElementById("auth-panel")
      ?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, []);

  return {
    ...state,
    setMode,
    login,
    signup,
    logout,
    requireSession,
  };
}

export type Session = ReturnType<typeof useSession>;

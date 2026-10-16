# Luc1fer's Food Bank frontend

React 19 single-page app built with [Vite](https://vite.dev). The Spring Boot backend in `../backend` serves the production build and the JSON API.

Requires Node `^20.19` or `>=22.12`.

## Scripts

| Command                  | What it does                                                                                                                                                                            |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm ci`                 | Install the locked dependencies (no `--legacy-peer-deps` needed).                                                                                                                       |
| `npm start`              | Vite dev server on http://localhost:3000. API paths from `src/api/proxyPaths.js` are proxied to http://localhost:8080; set `DEV_PROXY_TARGET` to use another backend.                   |
| `npm test`               | Vitest (jsdom + React Testing Library), single run. `npm run test:watch` keeps it running.                                                                                              |
| `npm run lint`           | ESLint (flat config with the React Hooks rules) and `prettier --check`. `npm run format` rewrites files with Prettier.                                                                  |
| `npm run build`          | Production build into `build/` (hashed assets in `build/static/`), then `scripts/verify-csp-build.js` fails the build if `index.html` has an inline script the backend CSP would block. |
| `npm run preview`        | Serve `build/` locally with the same API proxy.                                                                                                                                         |
| `npm run deploy:backend` | Build and copy `build/` into `../backend/src/main/resources/public`.                                                                                                                    |

`./gradlew bootJar` in `../backend` runs `npm run build` itself and embeds `build/` in the jar, so install dependencies first.

## Layout

```text
src/
  api/          client.js (fetch wrapper: AbortSignal, ApiError, 401 -> onUnauthorized), endpoints.js, proxyPaths.js
  hooks/        useSession, useMenu, useCart, useCheckout, useOrders, useNotifications, useAdminTools, useLatestRequest
  components/   presentational sections, ErrorBoundary
  pages/        OrderingPage: session-scoped state, remounted when the signed-in user changes
  lib/          formatting helpers
  test/         fakeBackend.js, the in-memory API used by the UI tests
```

Behaviour worth knowing before changing things:

- A 401 from any endpoint that needs a session (everything except `/login` and `/me`) signs the UI out and shows a session-expired message.
- Switching restaurants aborts the previous menu request; a late response for a restaurant the user already left is dropped.
- Retrying the same checkout, order cancel, or admin status change reuses its `Idempotency-Key`; editing the payment form starts a new attempt.
- Notifications poll every 15 seconds only while the tab is visible, and refresh once when it becomes visible again.
- Client-side environment variables must be prefixed `VITE_` (none are used today).

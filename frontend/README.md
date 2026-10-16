# Luc1fer's Food Bank frontend

React 19 single-page app in strict TypeScript, built with [Vite](https://vite.dev). The Spring Boot backend in `../backend` serves the production build and the JSON API.

Requires Node `^20.19`, `^22.13`, or `>=24` (the range the locked Vite, Vitest, ESLint, and jsdom all support).

## Scripts

| Command                  | What it does                                                                                                                                                                            |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm ci`                 | Install the locked dependencies (no `--legacy-peer-deps` needed).                                                                                                                       |
| `npm start`              | Vite dev server on http://localhost:3000. API paths from `src/api/proxyPaths.ts` are proxied to http://localhost:8080; set `DEV_PROXY_TARGET` to use another backend.                   |
| `npm run typecheck`      | `tsc --noEmit` for `tsconfig.app.json` (everything in `src/`, tests included) and `tsconfig.node.json` (`vite.config.ts`, `scripts/*.js` through JSDoc, the Node-side CSP build test).  |
| `npm test`               | Vitest (jsdom + React Testing Library), single run. `npm run test:watch` keeps it running. Vitest strips types without checking them, so run `typecheck` too.                           |
| `npm run lint`           | ESLint (flat config, type-aware typescript-eslint, React Hooks rules) and `prettier --check`. `npm run format` rewrites files with Prettier.                                            |
| `npm run build`          | Production build into `build/` (hashed assets in `build/static/`), then `scripts/verify-csp-build.js` fails the build if `index.html` has an inline script the backend CSP would block. |
| `npm run preview`        | Serve `build/` locally with the same API proxy.                                                                                                                                         |
| `npm run deploy:backend` | Build and copy `build/` into `../backend/src/main/resources/public`.                                                                                                                    |

`./gradlew bootJar` in `../backend` runs `npm run build` itself and embeds `build/` in the jar, so install dependencies first. CI runs `typecheck`, `lint`, `test`, and `build`.

## TypeScript

- `strict` plus `noUncheckedIndexedAccess`, `noUnusedLocals`/`Parameters`, and `verbatimModuleSyntax`.
- Backend JSON is never trusted as typed: `request()` resolves with `unknown`, and each endpoint function in `src/api/endpoints.ts` decodes it into a DTO from `src/api/dto.ts` with the field readers in `src/api/decode.ts`. A missing or mistyped field fails with `Unexpected response from the server: <path> should be ...` instead of surfacing as `undefined` in a component. The backend drops null fields (`non_null` inclusion), so nullable fields decode a missing key as `null`.
- ESLint forbids `any`, `as` casts (except `as const`), and non-null assertions, and runs the type-aware `recommendedTypeChecked` rules (floating promises must be awaited or marked `void`).
- TypeScript stays on 6.0 because typescript-eslint supports `<6.1`; move to the TypeScript 7 native compiler once it does.

## Layout

```text
src/
  api/          client.ts (fetch wrapper: AbortSignal, ApiError with status/code/trace id, 401 -> onUnauthorized),
                decode.ts + dto.ts (typed DTOs and their decoders), endpoints.ts, proxyPaths.ts
  hooks/        useSession, useMenu, useCart, useCheckout, useOrders, useNotifications, useAdminTools,
                useLatestRequest, usePolling
  components/   presentational sections, ErrorBoundary
  pages/        OrderingPage: session-scoped state, remounted when the signed-in user changes
  lib/          formatting and form helpers
  test/         fakeBackend.ts (the in-memory API used by the UI tests), helpers.tsx
```

Behaviour worth knowing before changing things:

- A 401 from any endpoint that needs a session (everything except `/login` and `/me`) signs the UI out and shows a session-expired message.
- Switching restaurants aborts the previous menu request; a late response for a restaurant the user already left is dropped.
- Retrying the same checkout, order cancel, or admin status change reuses its `Idempotency-Key`; editing the payment form starts a new attempt.
- Notifications poll every 15 seconds only while the tab is visible, and refresh once when it becomes visible again (`usePolling`).
- Client-side environment variables must be prefixed `VITE_` (none are used today).

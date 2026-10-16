// Path prefixes of every backend endpoint the API client calls. The Vite dev and
// preview servers proxy exactly these to the Spring Boot backend; everything else
// (/, /src/*, /@vite/*, public files) is served by Vite itself.
export const API_PATH_PREFIXES: readonly string[] = [
  "/login",
  "/logout",
  "/signup",
  "/me",
  "/restaurants",
  "/restaurant",
  "/cart",
  "/payments",
  "/orders",
  "/notifications",
  "/dead-letters",
  "/merchant",
  "/rider",
  "/admin",
];

// Vite treats proxy keys that start with ^ as regular expressions matched against
// the request URL (path plus query string).
export const API_PROXY_PATTERN = `^(${API_PATH_PREFIXES.join("|")})(/|\\?|$)`;

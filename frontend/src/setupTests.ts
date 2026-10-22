import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";
import { installFakeEventSource } from "./test/fakeEventSource";

// jsdom has no EventSource. Each test gets the fake, whose streams stay CONNECTING (so
// every screen polls at its own interval) until the test opens one.
beforeEach(() => {
  installFakeEventSource();
});

// Vitest runs without globals, so Testing Library cannot register its own cleanup.
afterEach(() => {
  cleanup();
});

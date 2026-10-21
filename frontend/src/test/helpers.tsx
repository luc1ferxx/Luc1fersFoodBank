import { act, render, screen } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import App from "../App";

export const renderApp = (): UserEvent => {
  const user = userEvent.setup();
  render(<App />);
  return user;
};

/** Lets pending promise callbacks (e.g. a dropped stale response) run. */
export const flushPromises = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));

/** The menu card of the dish called `name`; fails the test when there is none. */
export const menuCard = (name: string): HTMLElement => {
  const card = screen.getByRole("heading", { name }).closest("article");
  if (!card) {
    throw new Error(`No menu card for ${name}`);
  }
  return card;
};

/** The form that contains `element`; fails the test when there is none. */
export const formOf = (element: HTMLElement): HTMLFormElement => {
  const form = element.closest("form");
  if (!form) {
    throw new Error("Element is not inside a form.");
  }
  return form;
};

export const setVisibility = (state: DocumentVisibilityState) => {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  act(() => {
    document.dispatchEvent(new Event("visibilitychange"));
  });
};

export const resetVisibility = () => {
  Reflect.deleteProperty(document, "visibilityState");
};

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ErrorBoundary } from "./ErrorBoundary";

let shouldThrow = true;

function Flaky() {
  if (shouldThrow) {
    throw new Error("boom");
  }
  return <p>Recovered content</p>;
}

describe("ErrorBoundary", () => {
  it("renders a fallback instead of crashing and recovers on retry", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    shouldThrow = true;

    render(
      <>
        <p>Sibling stays visible</p>
        <ErrorBoundary label="The menu">
          <Flaky />
        </ErrorBoundary>
      </>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("The menu failed to render.");
    expect(screen.getByText("Sibling stays visible")).toBeInTheDocument();
    expect(consoleError).toHaveBeenCalled();

    shouldThrow = false;
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(screen.getByText("Recovered content")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

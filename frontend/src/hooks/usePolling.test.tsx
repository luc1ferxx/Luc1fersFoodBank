import { act, render } from "@testing-library/react";
import { useLayoutEffect } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { usePolling } from "./usePolling";

const INTERVAL_MS = 1000;

interface PollerProps {
  enabled: boolean;
  onTick: () => void;
  /** Runs at commit, before React runs the effect cleanup that clears the interval. */
  onCommit: (enabled: boolean) => void;
}

function Poller({ enabled, onTick, onCommit }: PollerProps) {
  usePolling(onTick, { enabled, intervalMs: INTERVAL_MS });
  useLayoutEffect(() => onCommit(enabled), [enabled, onCommit]);
  return null;
}

describe("usePolling", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not tick between the render that turns polling off and the interval's cleanup", () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const onTick = vi.fn();
    const armedAtCommit: number[] = [];
    // Layout effects run at commit and the cleanup that clears the interval after them;
    // when the render did not start inside act() (a fetch that answered on its own),
    // React runs that cleanup only in a later task, after the screen already changed.
    // Firing the interval here is the tick a busy runner or browser sees in that gap.
    const onCommit = (enabled: boolean) => {
      if (!enabled) {
        armedAtCommit.push(vi.getTimerCount());
        vi.advanceTimersByTime(INTERVAL_MS);
      }
    };
    const { rerender } = render(<Poller enabled onTick={onTick} onCommit={onCommit} />);

    act(() => {
      vi.advanceTimersByTime(INTERVAL_MS);
    });
    expect(onTick).toHaveBeenCalledTimes(1);

    rerender(<Poller enabled={false} onTick={onTick} onCommit={onCommit} />);
    expect(armedAtCommit).toEqual([1]);
    expect(onTick).toHaveBeenCalledTimes(1);

    expect(vi.getTimerCount()).toBe(0);
    act(() => {
      vi.advanceTimersByTime(INTERVAL_MS * 3);
    });
    expect(onTick).toHaveBeenCalledTimes(1);
  });
});

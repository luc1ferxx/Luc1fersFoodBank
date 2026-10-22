import { act, render } from "@testing-library/react";
import { useLayoutEffect } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PushTopic } from "../api/dto";
import { resetVisibility, setVisibility } from "../test/helpers";
import { usePolling } from "./usePolling";
import {
  PUSH_SIGNAL_COALESCE_MS,
  type ServerSignals,
  ServerSignalsContext,
  type SignalListener,
} from "./useServerSignals";

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

/* ADR 0003: a topic's signals are coalesced into at most one read per PUSH_SIGNAL_COALESCE_MS. */

interface SignalledPollerProps {
  topic: PushTopic;
  onTick: () => void;
  enabled?: boolean;
}

function SignalledPoller({ topic, onTick, enabled = true }: SignalledPollerProps) {
  usePolling(onTick, { enabled, intervalMs: INTERVAL_MS, signalTopic: topic });
  return null;
}

/** An open stream whose signals the test sends itself, by calling the recorded listeners. */
const fakeServerSignals = () => {
  const listeners = new Map<PushTopic, Set<SignalListener>>();
  const signals: ServerSignals = {
    connected: true,
    subscribe: (topic, listener) => {
      const ofTopic = listeners.get(topic) ?? new Set<SignalListener>();
      ofTopic.add(listener);
      listeners.set(topic, ofTopic);
      return () => {
        ofTopic.delete(listener);
      };
    },
  };
  const signal = (topic: PushTopic) => {
    act(() => {
      for (const listener of listeners.get(topic) ?? []) {
        listener();
      }
    });
  };
  const subscribers = (topic: PushTopic) => listeners.get(topic)?.size ?? 0;
  return { signals, signal, subscribers };
};

const advance = (ms: number) => {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
};

describe("usePolling with coalesced signals", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  const renderSignalled = (enabled = true) => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "setTimeout", "clearTimeout"] });
    const stream = fakeServerSignals();
    const onTick = vi.fn();
    const view = render(
      <ServerSignalsContext.Provider value={stream.signals}>
        <SignalledPoller topic="merchant.orders" onTick={onTick} enabled={enabled} />
      </ServerSignalsContext.Provider>,
    );
    expect(stream.subscribers("merchant.orders")).toBe(1);
    return { ...view, stream, onTick };
  };

  it("reads once on the first signal of a burst and once more when the window ends", () => {
    const { stream, onTick } = renderSignalled();

    for (let sent = 0; sent < 5; sent += 1) {
      stream.signal("merchant.orders");
    }
    expect(onTick).toHaveBeenCalledTimes(1);

    advance(PUSH_SIGNAL_COALESCE_MS - 1);
    expect(onTick).toHaveBeenCalledTimes(1);
    advance(1);
    expect(onTick).toHaveBeenCalledTimes(2);

    // The trailing read's own window ends with nothing pending: no third read.
    advance(PUSH_SIGNAL_COALESCE_MS * 3);
    expect(onTick).toHaveBeenCalledTimes(2);
  });

  it("reads at once again on a signal after a quiet window", () => {
    const { stream, onTick } = renderSignalled();

    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(1);
    advance(PUSH_SIGNAL_COALESCE_MS);
    expect(onTick).toHaveBeenCalledTimes(1);

    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(2);
  });

  it("drops the pending read when the hook unmounts during the window", () => {
    const { stream, onTick, unmount } = renderSignalled();

    stream.signal("merchant.orders");
    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(1);

    unmount();
    expect(stream.subscribers("merchant.orders")).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    advance(PUSH_SIGNAL_COALESCE_MS * 2);
    expect(onTick).toHaveBeenCalledTimes(1);
  });

  it("drops the pending read when polling is turned off during the window", () => {
    const { stream, onTick, rerender } = renderSignalled();

    stream.signal("merchant.orders");
    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(1);

    rerender(
      <ServerSignalsContext.Provider value={stream.signals}>
        <SignalledPoller topic="merchant.orders" onTick={onTick} enabled={false} />
      </ServerSignalsContext.Provider>,
    );
    expect(stream.subscribers("merchant.orders")).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    advance(PUSH_SIGNAL_COALESCE_MS * 2);
    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(1);
  });

  it("ignores a signal while the tab is hidden and opens no window for it", () => {
    const { stream, onTick } = renderSignalled();

    setVisibility("hidden");
    stream.signal("merchant.orders");
    advance(PUSH_SIGNAL_COALESCE_MS * 2);
    expect(onTick).toHaveBeenCalledTimes(0);

    // Shown again: the one catch-up read, then the next signal reads at once.
    setVisibility("visible");
    expect(onTick).toHaveBeenCalledTimes(1);
    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(2);
  });

  it("drops the pending read when the window ends on a hidden tab", () => {
    const { stream, onTick } = renderSignalled();

    stream.signal("merchant.orders");
    stream.signal("merchant.orders");
    expect(onTick).toHaveBeenCalledTimes(1);

    setVisibility("hidden");
    advance(PUSH_SIGNAL_COALESCE_MS);
    expect(onTick).toHaveBeenCalledTimes(1);

    // Showing the tab reads once; nothing is owed on top of that.
    setVisibility("visible");
    expect(onTick).toHaveBeenCalledTimes(2);
    advance(PUSH_SIGNAL_COALESCE_MS * 2);
    expect(onTick).toHaveBeenCalledTimes(2);
  });
});

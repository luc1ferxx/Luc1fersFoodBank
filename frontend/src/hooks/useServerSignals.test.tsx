import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PushTopic } from "../api/dto";
import { ServerSignalsProvider } from "../components/ServerSignalsProvider";
import { eventStreams, latestEventStream, serveEventStreams } from "../test/fakeEventSource";
import { resetVisibility, setVisibility } from "../test/helpers";
import { usePolling } from "./usePolling";
import {
  PUSH_FALLBACK_POLL_MS,
  STREAM_RETRY_BASE_MS,
  STREAM_RETRY_MAX_MS,
  useServerSignals,
} from "./useServerSignals";

const INTERVAL_MS = 1000;

interface PollerProps {
  topic: PushTopic;
  onTick: () => void;
  enabled?: boolean;
}

function Poller({ topic, onTick, enabled = true }: PollerProps) {
  usePolling(onTick, { enabled, intervalMs: INTERVAL_MS, signalTopic: topic });
  return null;
}

function Status() {
  const { connected } = useServerSignals();
  return <p>{connected ? "connected" : "polling"}</p>;
}

const status = () => screen.getByRole("paragraph").textContent;

describe("ServerSignalsProvider", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  it("calls the subscribers of a signal's topic only, and every subscriber when the stream opens", () => {
    const orders = vi.fn();
    const deliveries = vi.fn();
    render(
      <ServerSignalsProvider signedIn>
        <Status />
        <Poller topic="merchant.orders" onTick={orders} />
        <Poller topic="rider.deliveries" onTick={deliveries} />
      </ServerSignalsProvider>,
    );
    const stream = latestEventStream();
    expect(stream.url).toBe("/events/stream");
    expect(status()).toBe("polling");

    act(() => stream.connect());
    expect(status()).toBe("connected");
    expect([orders.mock.calls.length, deliveries.mock.calls.length]).toEqual([1, 1]);

    act(() => stream.signal("merchant.orders"));
    act(() => stream.signal("merchant.orders"));
    expect([orders.mock.calls.length, deliveries.mock.calls.length]).toEqual([3, 1]);
    act(() => stream.signal("rider.deliveries"));
    expect([orders.mock.calls.length, deliveries.mock.calls.length]).toEqual([3, 2]);
  });

  it("logs and ignores a signal it cannot decode", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const orders = vi.fn();
    render(
      <ServerSignalsProvider signedIn>
        <Poller topic="merchant.orders" onTick={orders} />
      </ServerSignalsProvider>,
    );
    const stream = latestEventStream();
    act(() => stream.connect());
    expect(orders).toHaveBeenCalledTimes(1);

    act(() => {
      // A topic of a newer backend, data that is not JSON, and a topic that is no string.
      stream.signal("kitchen.tickets");
      stream.send("signal", "merchant.orders");
      stream.send("signal", JSON.stringify({ topic: 7 }));
    });

    expect(orders).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledTimes(3);
    expect(String(warn.mock.calls[0]?.[1])).toContain(
      "signal.topic should be one of notifications, merchant.orders, merchant.after_sales, rider.deliveries",
    );
    expect(stream.readyState).toBe(stream.OPEN);
  });

  it("reports the stream down after an error and calls every subscriber once when it reopens", () => {
    const notifications = vi.fn();
    render(
      <ServerSignalsProvider signedIn>
        <Status />
        <Poller topic="notifications" onTick={notifications} />
      </ServerSignalsProvider>,
    );
    const stream = latestEventStream();
    act(() => stream.connect());
    expect(notifications).toHaveBeenCalledTimes(1);

    act(() => stream.drop());
    expect(status()).toBe("polling");
    expect(notifications).toHaveBeenCalledTimes(1);

    act(() => stream.connect());
    expect(status()).toBe("connected");
    expect(notifications).toHaveBeenCalledTimes(2);
    expect(eventStreams()).toHaveLength(1);
  });

  it("closes the stream for good on forbidden and lets every subscriber read once", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const orders = vi.fn();
    const notifications = vi.fn();
    render(
      <ServerSignalsProvider signedIn>
        <Status />
        <Poller topic="merchant.orders" onTick={orders} />
        <Poller topic="notifications" onTick={notifications} />
      </ServerSignalsProvider>,
    );
    const stream = latestEventStream();
    act(() => stream.connect());

    act(() => stream.forbid());

    expect(stream.closedByClient).toBe(true);
    expect(status()).toBe("polling");
    expect([orders.mock.calls.length, notifications.mock.calls.length]).toEqual([2, 2]);
    act(() => {
      vi.advanceTimersByTime(STREAM_RETRY_MAX_MS * 2);
    });
    expect(eventStreams()).toHaveLength(1);
  });

  it("tries a new stream after a growing back-off once the browser gave one up", () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    // 503: the instance is at its stream limit. EventSource reports it as error, CLOSED.
    serveEventStreams(() => ({ refusedWith: 503 }));
    const { unmount } = render(
      <ServerSignalsProvider signedIn>
        <Status />
      </ServerSignalsProvider>,
    );

    const waits: number[] = [];
    for (let attempt = 1; attempt <= 6; attempt += 1) {
      const refused = latestEventStream();
      act(() => refused.connect());
      expect(refused.closedByClient).toBe(true);
      expect(status()).toBe("polling");
      let waited = 0;
      while (eventStreams().length === attempt) {
        act(() => {
          vi.advanceTimersByTime(1000);
        });
        waited += 1000;
      }
      waits.push(waited);
    }
    expect(waits).toEqual([
      STREAM_RETRY_BASE_MS,
      STREAM_RETRY_BASE_MS * 2,
      STREAM_RETRY_BASE_MS * 4,
      STREAM_RETRY_BASE_MS * 8,
      STREAM_RETRY_MAX_MS,
      STREAM_RETRY_MAX_MS,
    ]);

    // A stream that opens starts the back-off over.
    serveEventStreams(() => ({ recipients: [] }));
    const accepted = latestEventStream();
    act(() => accepted.connect());
    expect(status()).toBe("connected");
    act(() => accepted.drop());
    act(() => accepted.giveUp());
    act(() => {
      vi.advanceTimersByTime(STREAM_RETRY_BASE_MS);
    });
    expect(eventStreams()).toHaveLength(8);

    // Signing out (the shell unmounts) closes the stream and drops a pending retry.
    act(() => latestEventStream().connect());
    act(() => latestEventStream().drop());
    act(() => latestEventStream().giveUp());
    unmount();
    act(() => {
      vi.advanceTimersByTime(STREAM_RETRY_MAX_MS);
    });
    expect(eventStreams()).toHaveLength(8);
  });

  it("opens no stream for a guest or in a browser without EventSource", () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const onTick = vi.fn();
    const { unmount } = render(
      <ServerSignalsProvider signedIn={false}>
        <Poller topic="notifications" onTick={onTick} />
      </ServerSignalsProvider>,
    );
    expect(eventStreams()).toHaveLength(0);
    unmount();

    vi.stubGlobal("EventSource", undefined);
    render(
      <ServerSignalsProvider signedIn>
        <Status />
        <Poller topic="notifications" onTick={onTick} />
      </ServerSignalsProvider>,
    );
    expect(eventStreams()).toHaveLength(0);
    expect(status()).toBe("polling");
    act(() => {
      vi.advanceTimersByTime(INTERVAL_MS * 3);
    });
    expect(onTick).toHaveBeenCalledTimes(3);
  });
});

describe("usePolling with a signal topic", () => {
  afterEach(() => {
    resetVisibility();
    vi.useRealTimers();
  });

  it("polls every PUSH_FALLBACK_POLL_MS while the stream is open and at its own interval otherwise", () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const onTick = vi.fn();
    render(
      <ServerSignalsProvider signedIn>
        <Poller topic="notifications" onTick={onTick} />
      </ServerSignalsProvider>,
    );
    act(() => {
      vi.advanceTimersByTime(INTERVAL_MS * 2);
    });
    expect(onTick).toHaveBeenCalledTimes(2);

    const stream = latestEventStream();
    act(() => stream.connect());
    expect(onTick).toHaveBeenCalledTimes(3);
    act(() => {
      vi.advanceTimersByTime(PUSH_FALLBACK_POLL_MS - 1);
    });
    expect(onTick).toHaveBeenCalledTimes(3);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onTick).toHaveBeenCalledTimes(4);

    act(() => stream.drop());
    act(() => {
      vi.advanceTimersByTime(INTERVAL_MS);
    });
    expect(onTick).toHaveBeenCalledTimes(5);
  });

  it("ignores signals while the tab is hidden or polling is off", () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const onTick = vi.fn();
    const { rerender } = render(
      <ServerSignalsProvider signedIn>
        <Poller topic="merchant.orders" onTick={onTick} />
      </ServerSignalsProvider>,
    );
    const stream = latestEventStream();
    act(() => stream.connect());
    expect(onTick).toHaveBeenCalledTimes(1);

    setVisibility("hidden");
    act(() => stream.signal("merchant.orders"));
    expect(onTick).toHaveBeenCalledTimes(1);
    // Shown again: one call catches up on whatever was signalled meanwhile.
    setVisibility("visible");
    expect(onTick).toHaveBeenCalledTimes(2);
    act(() => stream.signal("merchant.orders"));
    expect(onTick).toHaveBeenCalledTimes(3);

    rerender(
      <ServerSignalsProvider signedIn>
        <Poller topic="merchant.orders" onTick={onTick} enabled={false} />
      </ServerSignalsProvider>,
    );
    act(() => stream.signal("merchant.orders"));
    act(() => stream.drop());
    act(() => stream.connect());
    expect(onTick).toHaveBeenCalledTimes(3);
  });
});

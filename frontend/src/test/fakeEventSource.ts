import { vi } from "vitest";
import type { PushTopic } from "../api/dto";

/* jsdom has no EventSource: the UI tests get this stand-in on window (setupTests.ts). */

const CONNECTING = 0;
const OPEN = 1;
const CLOSED = 2;

/**
 * How the server answers a stream's connect (GET /events/stream): the recipients it
 * subscribes the stream to, or the HTTP status that refuses it (401, 403, 429, 503),
 * which the browser reports as `error` with readyState CLOSED.
 */
export type StreamAnswer = { recipients: readonly string[] } | { refusedWith: number };

/** The recipients of ADR 0003, the way the backend names them. */
export const customerRecipient = (customerId: number) => `customer:${customerId}`;
export const restaurantRecipient = (restaurantId: number) => `restaurant:${restaurantId}`;
export const riderRecipient = (riderId: number) => `rider:${riderId}`;

let streams: FakeEventSource[] = [];
let server: (() => StreamAnswer) | null = null;

/**
 * An EventSource whose server and network the test plays. A new stream stays CONNECTING
 * until the test lets the server answer (`connect()`), so a test that never does sees
 * every screen poll at its own interval, as without push signals. Without a fake backend
 * serving streams, `connect()` opens one with no recipients.
 */
export class FakeEventSource extends EventTarget {
  static readonly CONNECTING = CONNECTING;
  static readonly OPEN = OPEN;
  static readonly CLOSED = CLOSED;
  readonly CONNECTING = CONNECTING;
  readonly OPEN = OPEN;
  readonly CLOSED = CLOSED;

  readonly url: string;
  readonly withCredentials: boolean;
  readyState: number = CONNECTING;
  /** What the server subscribed the stream to when it opened. */
  recipients: readonly string[] = [];
  /** The client called close(). */
  closedByClient = false;

  constructor(url: string | URL, init: EventSourceInit = {}) {
    super();
    this.url = String(url);
    this.withCredentials = init.withCredentials ?? false;
    streams.push(this);
  }

  close(): void {
    this.readyState = CLOSED;
    this.closedByClient = true;
  }

  /** The server answers the pending connect: `open`, or a refusal (`error`, CLOSED). */
  connect(): void {
    this.expect(CONNECTING, "connect");
    const answer = server?.() ?? { recipients: [] };
    if ("refusedWith" in answer) {
      this.giveUp();
      return;
    }
    this.recipients = answer.recipients;
    this.readyState = OPEN;
    this.dispatchEvent(new Event("open"));
  }

  /** `event: signal` with `data: {"topic":"<topic>"}`; any string, to try unknown topics. */
  signal(topic: string): void {
    this.send("signal", JSON.stringify({ topic }));
  }

  /** One event of `type` with raw `data`, e.g. data that is not JSON. */
  send(type: string, data: string): void {
    this.expect(OPEN, `send ${type}`);
    this.dispatchEvent(new MessageEvent(type, { data }));
  }

  /**
   * The keepalive found the login deactivated: `event: forbidden`, then the server
   * completes the stream, which the browser reconnects unless the client closed it.
   */
  forbid(): void {
    this.send("forbidden", "");
    if (!this.closedByClient) {
      this.drop();
    }
  }

  /** The connection broke: the browser reports `error` and reconnects (CONNECTING). */
  drop(): void {
    this.expect(OPEN, "drop");
    this.readyState = CONNECTING;
    this.dispatchEvent(new Event("error"));
  }

  /** The browser gave up on the stream (a refused reconnect): `error` with CLOSED. */
  giveUp(): void {
    this.expect(CONNECTING, "give up");
    this.readyState = CLOSED;
    this.dispatchEvent(new Event("error"));
  }

  private expect(state: number, action: string) {
    if (this.closedByClient || this.readyState !== state) {
      throw new Error(
        `Cannot ${action}: the stream ${this.url} is in readyState ${this.readyState}`,
      );
    }
  }
}

/** Puts FakeEventSource on window with no streams and no server; setupTests.ts runs it. */
export function installFakeEventSource() {
  streams = [];
  server = null;
  vi.stubGlobal("EventSource", FakeEventSource);
}

/** How the fake backend answers connects (`installFakeBackend` registers it). */
export function serveEventStreams(answer: () => StreamAnswer) {
  server = answer;
}

/** Every stream the app created in this test, oldest first. */
export const eventStreams = (): readonly FakeEventSource[] => [...streams];

/** The stream the app created last; fails the test when it created none. */
export const latestEventStream = (): FakeEventSource => {
  const stream = streams.at(-1);
  if (!stream) {
    throw new Error("The app opened no event stream.");
  }
  return stream;
};

/** What PushSignals.publish does after a commit: `topic` to every open stream of `recipient`. */
export function publishSignal(recipient: string, topic: PushTopic) {
  streams
    .filter((stream) => stream.readyState === OPEN && stream.recipients.includes(recipient))
    .forEach((stream) => stream.signal(topic));
}

/**
 * The keepalive's re-check of the login (every 25 s on the server): `forbidden` to every
 * open stream once the server would refuse a new connect with 403 (a deactivated
 * merchant or rider). The fake backend has one user, so it checks that one.
 */
export function sendKeepalive() {
  const answer = server?.();
  if (answer && "refusedWith" in answer && answer.refusedWith === 403) {
    streams.filter((stream) => stream.readyState === OPEN).forEach((stream) => stream.forbid());
  }
}

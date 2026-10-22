package com.laioffer.onlineorder.push;


/**
 * The text a push stream sends, written as it goes over the wire (text/event-stream, ADR 0003 "Transport"). Each frame
 * ends with the blank line that dispatches it.
 */
final class SseFrames {

    /**
     * The browser's reconnect delay, sent once when a stream opens.
     */
    static final long RECONNECT_DELAY_MILLIS = 5000;

    static final String OPEN = "retry: " + RECONNECT_DELAY_MILLIS + "\n\n";

    /**
     * A comment line: EventSource ignores it, while proxies and the client's TCP stack see traffic.
     */
    static final String KEEPALIVE = ":keepalive\n\n";

    /**
     * The login lost the role the stream was opened with (a deactivated merchant or rider). An event without a data
     * line would never be dispatched by EventSource, so it carries an empty object.
     */
    static final String FORBIDDEN = "event: forbidden\ndata: {}\n\n";


    private SseFrames() {
    }


    /**
     * {@code event: signal} with {@code data: {"topic":"<topic>"}}. Topic names are fixed ASCII without quotes, so
     * they need no escaping.
     */
    static String signal(PushTopic topic) {
        return "event: signal\ndata: {\"topic\":\"" + topic.wireName() + "\"}\n\n";
    }
}

package com.laioffer.onlineorder.push;


import org.junit.jupiter.api.Assertions;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


/**
 * A push stream over real HTTP, read line by line on a thread of its own as a browser's EventSource would, for the
 * tests that run the app on a port. Waiting for a line blocks on the queue the reader fills.
 */
public final class SseTestClient implements AutoCloseable {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final HttpResponse<Stream<String>> response;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String> unread = new LinkedBlockingQueue<>();
    private final CountDownLatch ended = new CountDownLatch(1);
    private final Thread reader;


    private SseTestClient(HttpResponse<Stream<String>> response) {
        this.response = response;
        this.reader = new Thread(this::read, "sse-test-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }


    /**
     * Opens {@code GET /events/stream} with the session cookie ({@code name=value}) and returns once the response
     * headers arrived.
     */
    public static SseTestClient open(String baseUrl, String sessionCookie) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/events/stream"))
                .header("Accept", "text/event-stream")
                .header("Cookie", sessionCookie)
                .GET()
                .build();
        try {
            return new SseTestClient(CLIENT.send(request, HttpResponse.BodyHandlers.ofLines()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    public int status() {
        return response.statusCode();
    }


    public String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }


    /**
     * Waits for the next line that equals {@code expected}, consuming the lines before it.
     */
    public void awaitLine(String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                String line = left <= 0 ? null : unread.poll(left, TimeUnit.NANOSECONDS);
                if (line == null) {
                    Assertions.fail("No line \"" + expected + "\" within " + timeout + "; read so far: " + lines);
                }
                if (expected.equals(line)) {
                    return;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    /**
     * Waits for {@code event: signal} followed by the data line of {@code topic}.
     */
    public void awaitSignal(String topic, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                awaitLine("event: signal", Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                String data = unread.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (("data: {\"topic\":\"" + topic + "\"}").equals(data)) {
                    return;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    /**
     * Waits until the server ended the response.
     */
    public void awaitEnd(Duration timeout) {
        try {
            Assertions.assertTrue(ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    "the stream did not end within " + timeout + "; read so far: " + lines);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    public List<String> lines() {
        return List.copyOf(lines);
    }


    @Override
    public void close() {
        response.body().close();
        reader.interrupt();
    }


    private void read() {
        try (Stream<String> body = response.body()) {
            body.forEach(line -> {
                lines.add(line);
                unread.add(line);
            });
        } catch (RuntimeException ex) {
            // Closed by the test, or the connection dropped.
        } finally {
            ended.countDown();
        }
    }
}

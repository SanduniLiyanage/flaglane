package io.github.sanduniliyanage.flaglane.streaming;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A Server-Sent Events client for tests: {@code GET /sdk/stream} over a real connection, read line
 * by line as the server writes, so a test can wait for the next event, the next heartbeat or the
 * end of the stream, each with a deadline.
 */
final class EventStream implements AutoCloseable {

  /** One event: its name, its id, and its data. */
  record Event(String name, String id, String data) {}

  private static final String END = "\u0000end";

  private final HttpClient client;
  private final HttpResponse<Stream<String>> response;
  private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
  private boolean ended;

  private EventStream(HttpClient client, HttpResponse<Stream<String>> response) {
    this.client = client;
    this.response = response;
    Thread.ofVirtual()
        .start(
            () -> {
              try (Stream<String> body = response.body()) {
                body.forEach(lines::add);
              } catch (RuntimeException e) {
                // The connection was closed; the stream has ended either way.
              } finally {
                lines.add(END);
              }
            });
  }

  /** Opens a stream with the key; returns once the response's headers have arrived. */
  static EventStream open(int port, String key) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sdk/stream"))
            .header("Authorization", "Bearer " + key)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    return new EventStream(client, client.send(request, HttpResponse.BodyHandlers.ofLines()));
  }

  int status() {
    return response.statusCode();
  }

  String header(String name) {
    return response.headers().firstValue(name).orElse(null);
  }

  /** The body of a response that is not a stream, such as a 429. */
  String errorBody(Duration timeout) throws InterruptedException {
    StringBuilder body = new StringBuilder();
    for (String line = poll(timeout); line != null && !line.equals(END); line = poll(timeout)) {
      body.append(line);
    }
    return body.toString();
  }

  /** The next event, passing over heartbeats; null if none arrives in time or the stream ends. */
  Event nextEvent(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      List<String> block = nextBlock(remaining(deadline));
      if (block == null) {
        return null;
      }
      if (!isComment(block)) {
        return parse(block);
      }
    }
  }

  /** Whether a heartbeat arrives in time, passing over any event before it. */
  boolean heartbeat(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      List<String> block = nextBlock(remaining(deadline));
      if (block == null) {
        return false;
      }
      if (isComment(block)) {
        return true;
      }
    }
  }

  /** Whether the server ends the stream in time. */
  boolean ends(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!ended) {
      String line = poll(remaining(deadline));
      if (line == null) {
        return false;
      }
      if (line.equals(END)) {
        ended = true;
      }
    }
    return true;
  }

  @Override
  public void close() {
    client.shutdownNow();
  }

  private List<String> nextBlock(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<String> block = new ArrayList<>();
    while (true) {
      String line = poll(remaining(deadline));
      if (line == null) {
        return null;
      }
      if (line.equals(END)) {
        ended = true;
        return null;
      }
      if (line.isEmpty()) {
        if (!block.isEmpty()) {
          return block;
        }
        continue;
      }
      block.add(line);
    }
  }

  private String poll(Duration timeout) throws InterruptedException {
    if (ended) {
      return END;
    }
    return lines.poll(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
  }

  private static boolean isComment(List<String> block) {
    return block.stream().allMatch(line -> line.startsWith(":"));
  }

  private static Event parse(List<String> block) {
    String name = null;
    String id = null;
    StringBuilder data = new StringBuilder();
    for (String line : block) {
      if (line.startsWith("event:")) {
        name = line.substring("event:".length()).strip();
      } else if (line.startsWith("id:")) {
        id = line.substring("id:".length()).strip();
      } else if (line.startsWith("data:")) {
        data.append(line.substring("data:".length()).strip());
      }
    }
    return new Event(name, id, data.toString());
  }

  private static Duration remaining(long deadline) {
    return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
  }
}

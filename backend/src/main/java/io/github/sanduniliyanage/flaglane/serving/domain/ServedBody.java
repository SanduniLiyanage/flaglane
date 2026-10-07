package io.github.sanduniliyanage.flaglane.serving.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * A ruleset's JSON body as {@code GET /sdk/config} sends it: the UTF-8 bytes, and the same bytes
 * compressed with gzip. Both are made once, when the snapshot is built, so that no request pays to
 * encode or compress the ruleset it is sent (ADR-033). The bytes are never handed out, only
 * written, so nothing that serves them can change them.
 */
public final class ServedBody {

  /** How the body is sent. */
  public enum Coding {
    IDENTITY,
    GZIP
  }

  private final byte[] identity;
  private final byte[] gzip;

  private ServedBody(byte[] identity, byte[] gzip) {
    this.identity = identity;
    this.gzip = gzip;
  }

  public static ServedBody of(String json) {
    Objects.requireNonNull(json, "json");
    byte[] identity = json.getBytes(StandardCharsets.UTF_8);
    return new ServedBody(identity, gzip(identity));
  }

  /** The length of the body in this coding, for {@code Content-Length}. */
  public int length(Coding coding) {
    return bytes(coding).length;
  }

  public void copyTo(OutputStream out, Coding coding) throws IOException {
    out.write(bytes(coding));
  }

  /** The JSON, decoded again. For tests and diagnostics; serving writes the bytes. */
  public String json() {
    return new String(identity, StandardCharsets.UTF_8);
  }

  private byte[] bytes(Coding coding) {
    return coding == Coding.GZIP ? gzip : identity;
  }

  private static byte[] gzip(byte[] bytes) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length / 4 + 64);
    try (GZIPOutputStream out = new GZIPOutputStream(buffer, 8192)) {
      out.write(bytes);
    } catch (IOException e) {
      // A ByteArrayOutputStream does not throw; this cannot happen.
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }
}

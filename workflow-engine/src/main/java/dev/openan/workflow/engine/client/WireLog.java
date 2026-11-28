/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Application-boundary evidence, not a TLS/HTTP packet capture. Never logs secrets verbatim. */
public final class WireLog {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("PROTOCOL");
  private static final ThreadLocal<Map<String, String>> CONTEXT = ThreadLocal.withInitial(Map::of);

  private WireLog() {}

  public static Map<String, String> context() {
    return CONTEXT.get();
  }

  public static <T> T call(Map<String, String> context, java.util.function.Supplier<T> action) {
    Map<String, String> previous = CONTEXT.get();
    CONTEXT.set(Map.copyOf(context));
    try {
      return action.get();
    } finally {
      CONTEXT.set(previous);
    }
  }

  /** Local-only correlation; never inserted into a protocol header or message metadata. */
  public static void inContext(Map<String, String> context, Runnable action) {
    Map<String, String> previous = CONTEXT.get();
    CONTEXT.set(Map.copyOf(context));
    try {
      action.run();
    } finally {
      CONTEXT.set(previous);
    }
  }

  static boolean setting(String key, boolean fallback) {
    String value = System.getProperty(key);
    if (value == null) value = System.getenv(key);
    return value == null ? fallback : Boolean.parseBoolean(value);
  }

  static int limit() {
    String value =
        System.getProperty(
            "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS",
            System.getenv("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS"));
    try {
      return Math.max(256, Integer.parseInt(value));
    } catch (RuntimeException ignored) {
      return 100_000;
    }
  }

  public static String redact(String text) {
    return SensitiveDataRedactor.redact(text);
  }

  static boolean sensitive(String name) {
    return SensitiveDataRedactor.sensitive(name);
  }

  public static Map<String, List<String>> safeHeaders(Map<String, List<String>> headers) {
    return SensitiveDataRedactor.safeHeaders(headers);
  }

  /** Actual serialized data only. SDK-decoded data must explicitly say decoded-data. */
  public static void record(
      String boundary,
      String direction,
      String requestId,
      String target,
      String method,
      Integer status,
      Map<String, List<String>> headers,
      String representation,
      String body,
      String visibility) {
    emit(
        null,
        boundary,
        direction,
        requestId,
        target,
        method,
        status,
        headers,
        representation,
        body,
        visibility,
        context());
  }

  static void emit(
      Consumer<Entry> observer,
      String boundary,
      String direction,
      String requestId,
      String target,
      String method,
      Integer status,
      Map<String, List<String>> headers,
      String representation,
      String body,
      String visibility,
      Map<String, String> context) {
    try {
      boolean commentFrame =
          observer == null && "raw-sse-frame".equals(representation) && isSseCommentFrame(body);
      if (commentFrame && !LOG.isTraceEnabled()) return;
      String safeBody = redact(body);
      if (!setting("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY", true)) {
        safeBody = "(body logging disabled)";
        visibility += "; body=disabled";
      } else if (safeBody.length() > limit()) {
        safeBody = safeBody.substring(0, limit()) + "\n(body truncated)";
        visibility += "; body=truncated";
      }
      Entry entry =
          new Entry(
              boundary,
              direction,
              requestId,
              redact(target),
              method,
              status,
              safeHeaders(headers),
              representation,
              safeBody,
              visibility,
              Map.copyOf(context));
      if (observer != null) {
        observer.accept(entry);
      } else if (commentFrame || LOG.isDebugEnabled()) {
        String formatted =
            WireLogFormatter.format(entry, setting("WORKFLOW_ENGINE_PROTOCOL_PRETTY", true));
        if (commentFrame) LOG.trace("{}", formatted);
        else LOG.debug("{}", formatted);
      }
    } catch (RuntimeException ignored) {
      // Logging must never change delivery, backpressure or application state.
      LOG.warn("Protocol observation failed; record dropped");
    }
  }

  static boolean isSseCommentFrame(String body) {
    return body != null && body.lines().allMatch(line -> line.isEmpty() || line.startsWith(":"));
  }

  /** Values are already redacted, including for test/custom observers. */
  public record Entry(
      String boundary,
      String direction,
      String requestId,
      String target,
      String method,
      Integer status,
      Map<String, List<String>> headers,
      String representation,
      String body,
      String visibility,
      Map<String, String> correlation) {}

  /** Bounded raw-byte collector. SSE emits each frame as it arrives, not at stream termination. */
  public static final class Body {
    private final Consumer<String[]> sink;
    private final boolean sse;
    private final boolean textual;
    private final int capacity = limit();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private long size;
    private boolean dropped;
    private boolean ended;
    private boolean pendingCr;
    private int lineBytes;

    public Body(boolean sse, boolean textual, Consumer<String[]> sink) {
      this.sse = sse;
      this.textual = textual;
      this.sink = sink;
    }

    public synchronized void accept(ByteBuffer input) {
      if (ended) return;
      ByteBuffer copy = input.asReadOnlyBuffer();
      while (copy.hasRemaining()) {
        int value = copy.get() & 255;
        if (sse && pendingCr && value != 10) {
          lineEnd();
          pendingCr = false;
        }
        size++;
        if (bytes.size() < capacity && !dropped) bytes.write(value);
        else {
          dropped = true;
          bytes.reset();
        }
        if (sse) {
          // CR, LF, and CRLF are line endings. A blank line terminates a frame.
          if (value == 13) pendingCr = true;
          else if (value == 10) {
            lineEnd();
            pendingCr = false;
          } else lineBytes++;
        }
      }
    }

    public synchronized void end(boolean interrupted) {
      if (ended) return;
      ended = true;
      if (pendingCr) {
        lineEnd();
        pendingCr = false;
      }
      if (size > 0 || !sse) flush(interrupted);
    }

    private void lineEnd() {
      if (lineBytes == 0) flush(false);
      lineBytes = 0;
    }

    private void flush(boolean interrupted) {
      String content =
          dropped
              ? "(body/frame dropped: capacity exceeded)"
              : !textual ? "(binary body omitted)" : bytes.toString(StandardCharsets.UTF_8);
      // Decode only complete frames/body, keeping UTF-8 code points intact across chunks.
      String visible =
          "observedBytes="
              + size
              + (dropped ? "; body=dropped-capacity" : "")
              + (interrupted ? "; stream=interrupted" : "");
      try {
        sink.accept(new String[] {content, visible});
      } catch (RuntimeException ignored) {
        LOG.warn("Protocol observation failed; record dropped");
      }
      bytes.reset();
      size = 0;
      dropped = false;
    }
  }
}

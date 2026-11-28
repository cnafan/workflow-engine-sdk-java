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

package dev.openan.workflow.engine.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in capture of per-task content for the execution history.
 *
 * <p>The default collection reports status only: no prompt, negotiation body or agent message text.
 * A host that needs the content of each step in the history turns it on explicitly, and then owns
 * the disclosure decision. {@code outputKeys} {@code null} means "every scalar or structured output
 * key", an empty set means "no outputs", any other set is a whitelist.
 *
 * @param summaryChars characters of per-task content kept in {@code result.summary}; {@code 0}
 *     disables text summaries
 * @param outputKeys output keys to report, or {@code null} for all of them
 */
public record ContentCapture(int summaryChars, Set<String> outputKeys) {

  /** Characters the center accepts in one top-level {@code summary} field. */
  public static final int SUMMARY_FIELD_LIMIT = 2000;

  /** Keeps a single event under the center's 64 KiB ingestion limit even with CJK text. */
  public static final int EVENT_BYTES_GUARD = 60 * 1024;

  /** The largest content budget a host may ask for, per task. */
  private static final int MAX_CONTENT_LIMIT = 12000;

  /**
   * An output value longer than this is bulk: it is dropped before any readable text is touched.
   */
  private static final int BULK_OUTPUT_LENGTH = 8192;

  /** What is left of a top-level summary once its event still does not fit. */
  private static final int SUMMARY_HINT_LENGTH = 200;

  /** Nested output values deeper than this are dropped rather than walked. */
  private static final int MAX_OUTPUT_DEPTH = 16;

  /**
   * Safety bound on trimming rounds. Each round removes one piece of content and the last round
   * removes nothing, so a handful per output key is enough; this only stops a defect in the
   * shrinking steps from spinning.
   */
  private static final int MAX_TRIM_ROUNDS = 1024;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Logger LOG = LoggerFactory.getLogger(ContentCapture.class);

  /** Status-only reporting; the contract default. */
  public static final ContentCapture DISABLED = new ContentCapture(0, Set.of());

  public ContentCapture {
    summaryChars = Math.max(0, Math.min(summaryChars, MAX_CONTENT_LIMIT));
    // Own the whitelist: a caller mutating its Set afterwards must not widen or narrow capture.
    outputKeys = outputKeys == null ? null : Set.copyOf(outputKeys);
  }

  /** A detached copy, so the stored whitelist cannot be changed through the accessor. */
  @Override
  public Set<String> outputKeys() {
    return outputKeys == null ? null : Set.copyOf(outputKeys);
  }

  /** Whether any task text is kept at all. */
  public boolean enabled() {
    return summaryChars > 0;
  }

  /**
   * The readable text of each task, and no structured outputs.
   *
   * <p>This is what automatic reporting uses. The center's contract requires the application to
   * name every output key it is willing to share, because no pattern can tell whether an arbitrary
   * business field is approved for disclosure. A host that wants structured replay opts in by
   * naming its keys, for example {@code new ContentCapture(ContentCapture.all().summaryChars(),
   * Set.of("conclusion"))}.
   */
  public static ContentCapture summaries() {
    return new ContentCapture(MAX_CONTENT_LIMIT, Set.of());
  }

  /** The readable text of each task with the given content budget. */
  public static ContentCapture summaries(int summaryChars) {
    return new ContentCapture(summaryChars, Set.of());
  }

  /** Everything the task produced, within one event's size budget. */
  public static ContentCapture all() {
    return new ContentCapture(MAX_CONTENT_LIMIT, null);
  }

  /** Every output key plus the given content budget. */
  public static ContentCapture all(int summaryChars) {
    return new ContentCapture(summaryChars, null);
  }

  // -----------------------------------------------------------------------------------------------
  // Turning engine values into reportable, disclosable content. These know nothing about
  // queues, files or HTTP; ExecutionReporter owns the delivery.
  // -----------------------------------------------------------------------------------------------

  /** The agent-visible text of an engine event; never the raw A2A envelope. */
  static String textOf(Map<String, Object> data) {
    for (String key : List.of("response", "text", "message")) {
      if (data.get(key) instanceof String value && !value.isBlank()) return value;
    }
    return "";
  }

  static String truncate(String text, int limit) {
    if (limit <= 0 || text.length() <= limit) return text;
    return text.substring(0, Math.max(0, limit - 1)) + "\u2026";
  }

  /** The readable content of a task request: its Task-T payload, else its text and data parts. */
  static String requestSummary(MessageContent request) {
    String taskT = displayText(request.metadata().get(A2ATExtension.TASK_T.uri()));
    if (!taskT.isBlank()) return taskT;
    List<String> parts = new ArrayList<>();
    for (var part : request.parts()) {
      String text =
          part instanceof TextPart t
              ? displayText(t.text())
              : part instanceof DataPart d ? displayText(d.data()) : "";
      if (!text.isBlank()) parts.add(text);
    }
    return String.join("\n\n", parts);
  }

  /** The Task-T payload of the last response message carrying one, else the plain response text. */
  static String responseSummary(Map<String, Object> data) {
    if (data.get("receivedMessages") instanceof List<?> messages) {
      for (int i = messages.size() - 1; i >= 0; i--) {
        if (!(messages.get(i) instanceof ReceivedMessage response)) continue;
        String taskT = displayText(response.taskMetadata().get(A2ATExtension.TASK_T.uri()));
        if (taskT.isBlank() && response.message() != null) {
          taskT = displayText(response.message().metadata().get(A2ATExtension.TASK_T.uri()));
        }
        if (taskT.isBlank()) {
          taskT =
              response.artifacts().stream()
                  .map(artifact -> displayText(artifact.metadata().get(A2ATExtension.TASK_T.uri())))
                  .filter(value -> !value.isBlank())
                  .distinct()
                  .collect(Collectors.joining("\n\n"));
        }
        if (!taskT.isBlank()) return taskT;
      }
    }
    return SensitiveDataRedactor.redact(textOf(data));
  }

  private static String displayText(Object value) {
    Object safe = safeOutput(value, 0);
    if (safe instanceof String text) return text;
    if (!(safe instanceof Map<?, ?>) && !(safe instanceof List<?>)) return "";
    try {
      return JSON.writeValueAsString(safe);
    } catch (IOException malformed) {
      return "";
    }
  }

  /**
   * The task's outputs this policy allows. Only JSON-shaped, non-sensitive values survive; {@link
   * #fitToLimit} trims the rest if the event grows too large.
   */
  Map<String, Object> collectOutputs(Map<String, Object> data) {
    Map<String, Object> outputs = new HashMap<>();
    Set<String> whitelist = outputKeys();
    if (whitelist != null && whitelist.isEmpty()) return outputs;
    if (!(data.get("outputs") instanceof List<?> items)) return outputs;
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> values)) continue;
      for (Map.Entry<?, ?> entry : values.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (whitelist != null && !whitelist.contains(key)) continue;
        if (SensitiveDataRedactor.sensitive(key)) continue;
        Object value = safeOutput(entry.getValue(), 0);
        if (value != null) outputs.put(key, value);
      }
    }
    return outputs;
  }

  /**
   * A JSON-shaped copy with secrets redacted, non-finite numbers and overly deep values removed.
   */
  private static Object safeOutput(Object value, int depth) {
    if (value instanceof String text) return SensitiveDataRedactor.redact(text);
    if (value instanceof Double number) return Double.isFinite(number) ? number : null;
    if (value instanceof Float number) return Float.isFinite(number) ? number : null;
    if (value instanceof Number || value instanceof Boolean) return value;
    if (depth >= MAX_OUTPUT_DEPTH) return null;
    if (value instanceof Map<?, ?> map) {
      var safe = new HashMap<String, Object>();
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key) || SensitiveDataRedactor.sensitive(key)) {
          continue;
        }
        Object entryValue = safeOutput(entry.getValue(), depth + 1);
        if (entryValue != null) safe.put(key, entryValue);
      }
      return safe;
    }
    if (value instanceof List<?> list) {
      var safe = new ArrayList<>();
      for (Object item : list) {
        Object entryValue = safeOutput(item, depth + 1);
        if (entryValue != null) safe.add(entryValue);
      }
      return safe;
    }
    return null;
  }

  // -----------------------------------------------------------------------------------------------
  // Keeping one event inside the center's ingestion limit.
  // -----------------------------------------------------------------------------------------------

  /**
   * Serializes an event, trimming its content in place until it fits {@link #EVENT_BYTES_GUARD}.
   *
   * <p>Identity and outcome fields are never touched: a task's step, task, attempt and outcome
   * always survive, so the center can still project the node. Content goes in order of how much is
   * lost by dropping it: one bulky output at a time, then the rest of the outputs, then the
   * readable summary. The event is therefore always deliverable even when a task reports far more
   * than one event may carry.
   *
   * @param event the whole event, whose {@code data} is trimmed
   */
  static byte[] fitToLimit(Map<String, Object> event) throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) event.get("data");
    byte[] payload = JSON.writeValueAsBytes(event);
    for (int round = 0; payload.length > EVENT_BYTES_GUARD && round < MAX_TRIM_ROUNDS; round++) {
      if (!shrinkPayload(data)) break;
      payload = JSON.writeValueAsBytes(event);
    }
    // Graded fallback: whatever the trimming loop could not remove, drop by decreasing value, so
    // the event is never left oversized for the center to reject and replace with a gap. A short
    // conclusion is shrunk before it is given up, because that is what an operator reads.
    for (Runnable step :
        List.<Runnable>of(
            () -> dropResultField(data, "outputs"),
            () -> truncateResultSummary(data, SUMMARY_HINT_LENGTH),
            () -> dropResultField(data, "summary"),
            () -> data.remove("result"),
            () -> truncateSummaryHint(data),
            () -> data.remove("summary"))) {
      if (payload.length <= EVENT_BYTES_GUARD) break;
      step.run();
      payload = JSON.writeValueAsBytes(event);
    }
    if (payload.length > EVENT_BYTES_GUARD) {
      LOG.warn(
          "A workflow observation event exceeds {} bytes even without its content; it will be"
              + " sent as it is and the center may reject it",
          EVENT_BYTES_GUARD);
    }
    return payload;
  }

  /** Removes one field of the result object, if the event still carries that result. */
  private static void dropResultField(Map<String, Object> data, String field) {
    if (data.get("result") instanceof Map<?, ?> result) result.remove(field);
  }

  /** Shortens the readable result summary, keeping its first {@code limit} characters. */
  private static void truncateResultSummary(Map<String, Object> data, int limit) {
    if (data.get("result") instanceof Map<?, ?> result
        && result.get("summary") instanceof String summary) {
      @SuppressWarnings("unchecked")
      Map<String, Object> writable = (Map<String, Object>) result;
      writable.put("summary", truncate(summary, limit));
    }
  }

  /** Shortens the top-level summary, which is the short hint above the result. */
  private static void truncateSummaryHint(Map<String, Object> data) {
    if (data.get("summary") instanceof String summary) {
      data.put("summary", truncate(summary, SUMMARY_HINT_LENGTH));
    }
  }

  /**
   * Removes one piece of content from an event's data.
   *
   * @return whether this call changed anything, so the caller can re-measure
   */
  private static boolean shrinkPayload(Map<String, Object> data) {
    if (data.get("result") instanceof Map<?, ?> rawResult) {
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) rawResult;
      if (dropLargestOutput(result)) return true;
      if (result.get("summary") instanceof String summary
          && summary.length() > SUMMARY_FIELD_LIMIT) {
        result.put("summary", truncate(summary, SUMMARY_FIELD_LIMIT));
        return true;
      }
      if (result.remove("outputs") != null) return true;
      if (result.remove("summary") != null) return true;
    }
    if (data.get("summary") instanceof String summary) {
      if (summary.length() > SUMMARY_HINT_LENGTH) {
        data.put("summary", truncate(summary, SUMMARY_HINT_LENGTH));
      } else {
        data.remove("summary");
      }
      return true;
    }
    return false;
  }

  /** Drops the single largest output value once it is bigger than a readable field. */
  private static boolean dropLargestOutput(Map<String, Object> result) {
    if (!(result.get("outputs") instanceof Map<?, ?> rawOutputs) || rawOutputs.isEmpty()) {
      return false;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> outputs = (Map<String, Object>) rawOutputs;
    String largestKey = null;
    int largest = 0;
    for (Map.Entry<String, Object> entry : outputs.entrySet()) {
      int size = String.valueOf(entry.getValue()).length();
      if (size > largest) {
        largest = size;
        largestKey = entry.getKey();
      }
    }
    if (largestKey == null || largest <= BULK_OUTPUT_LENGTH) return false;
    outputs.remove(largestKey);
    return true;
  }
}

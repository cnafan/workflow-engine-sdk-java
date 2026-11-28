/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.registry.HttpAuthProvider;
import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in workflow observation, with a persistent directory dedicated to one reporting source. */
public final class ExecutionReporter implements AutoCloseable {
  private static final int QUEUE_CAPACITY = 2048;
  private static final int RECEIVE_WINDOW = 1024;
  private static final Duration CLOSE_DELIVERY_BUDGET = Duration.ofSeconds(2);
  private static final Logger LOG = LoggerFactory.getLogger(ExecutionReporter.class);
  private static final String PREFIX = "dev.openan.workflow.";
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Events below this many free queue slots are dropped so that the two lifecycle events, which a
   * run can never report without, always find room.
   */
  private static final int LIFECYCLE_RESERVE = 64;

  /** Bytes of one batch sent to the center; the center's limit is 1 MiB. */
  private static final int MAX_BATCH_BYTES = 900 * 1024;

  /** Events in one batch; the center's per-request limit is 100. */
  private static final int MAX_BATCH_EVENTS = 50;

  /**
   * Retry delay, in seconds, for a refusal that is the host's to fix (credentials, permissions).
   */
  private static final long CREDENTIAL_RETRY_SECONDS = 30;

  /** Upper bound on a Retry-After the center asks for, in seconds. */
  private static final long MAX_RETRY_AFTER_SECONDS = 120;

  /** Ceiling on the pending events' total size on disk. */
  private static final long MAX_SPOOL_BYTES = 256L * 1024 * 1024;

  /** How often the sender looks for events to send, in milliseconds. */
  private static final long SEND_INTERVAL_MILLIS = 200;

  /** Outcomes kept in memory for {@link #awaitDelivery(String, Duration)}; oldest dropped first. */
  private static final int MAX_RECENT_DELIVERIES = 2048;

  /**
   * Longest a host's wait sleeps before looking again. The spool does not signal when it empties,
   * so both waits fall back to looking at this pace.
   */
  private static final long POLL_INTERVAL_MILLIS = 50;

  /** Time each background thread is given to finish what it is doing on {@link #close()}. */
  private static final long THREAD_SHUTDOWN_SECONDS = 5;

  /** A W3C traceparent the center will accept; anything else is left out rather than rejected. */
  private static final Pattern TRACE_PARENT =
      Pattern.compile("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");

  /** A W3C tracestate: printable ASCII, at most 512 characters. */
  private static final Pattern TRACE_STATE = Pattern.compile("[ -~]{1,512}");

  private static final Map<String, List<String>> INTERACTIONS =
      Map.of(
          "negotiation_request", List.of("negotiation", "requested"),
          "negotiation_resolved", List.of("negotiation", "resolved"),
          "negotiation_failed", List.of("negotiation", "failed"),
          "agent_request", List.of("agent_call", "sent"),
          "agent_response", List.of("agent_call", "received"));

  /**
   * Events that carry the agent's own answer text. They are reported as progress with a summary but
   * without an interaction label, because the phase is not what an operator needs to read.
   */
  private static final Set<String> CONTENT_EVENTS =
      Set.of("agent_status_update", "agent_artifact_update", "agent_message_event");

  /**
   * Conflicts that no retry can resolve for this run: the center already holds a different event
   * under the same sequence, the run is closed, or this host may not report it. Retrying would only
   * hold the spool hostage, so the run is set aside as rejected instead.
   */
  private static final Set<String> FINAL_CONFLICTS =
      Set.of(
          "EXECUTION_CLOSED",
          "OWNER_MISMATCH",
          "PSOP_FORBIDDEN",
          "SOURCE_MISMATCH",
          "EVENT_ID_CONFLICT",
          "SEQUENCE_OR_EVENT_ID_CONFLICT",
          "TERMINAL_SEQUENCE_CONFLICT",
          "START_REQUIRED",
          "REVISION_NOT_FOUND");

  private final URI endpoint;
  private final HttpAuthProvider auth;
  private final String source;
  private final EventSpool spool;
  private final ContentCapture capture;
  private final HttpClient http;

  /** Permits for events waiting to be written; a run's lifecycle events always keep room. */
  private final Semaphore slots = new Semaphore(QUEUE_CAPACITY);

  private final ScheduledThreadPoolExecutor writer = worker("workflow-observation-writer");
  private final ScheduledThreadPoolExecutor sender = worker("workflow-observation-http");

  /** When each execution may be sent again. Confined to the sender thread, so it needs no lock. */
  private final Map<Path, Long> retryAt = new HashMap<>();

  /** Guards {@link #deliveries} and {@link #lifecycleWriteFailed}: what hosts wait on. */
  private final Object deliveryLock = new Object();

  private final Map<String, DeliveryStatus> deliveries = new LinkedHashMap<>();
  private boolean lifecycleWriteFailed;

  /** Reports status only, with the default HTTP client. */
  public ExecutionReporter(URI endpoint, String token, String source, Path spool)
      throws IOException {
    this(endpoint, token, source, spool, ContentCapture.DISABLED);
  }

  /**
   * Reports status plus the whitelisted output keys.
   *
   * @param outputKeys the output keys to report; an empty set reports no outputs
   */
  public ExecutionReporter(
      URI endpoint, String token, String source, Path spool, Set<String> outputKeys)
      throws IOException {
    this(
        endpoint,
        token,
        source,
        spool,
        outputKeys,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
  }

  /** As {@link #ExecutionReporter(URI, String, String, Path, Set)}, with a caller-built client. */
  public ExecutionReporter(
      URI endpoint,
      String token,
      String source,
      Path spool,
      Set<String> outputKeys,
      HttpClient http)
      throws IOException {
    this(endpoint, token, source, spool, new ContentCapture(0, outputKeys), http);
  }

  /**
   * Reports under the given content policy.
   *
   * @param capture what task content to keep; {@link ContentCapture#DISABLED} for status only
   */
  public ExecutionReporter(
      URI endpoint, String token, String source, Path spool, ContentCapture capture)
      throws IOException {
    this(
        endpoint,
        token,
        source,
        spool,
        capture,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
  }

  /**
   * As {@link #ExecutionReporter(URI, String, String, Path, ContentCapture)}, with a caller-built
   * client.
   */
  public ExecutionReporter(
      URI endpoint,
      String token,
      String source,
      Path spool,
      ContentCapture capture,
      HttpClient http)
      throws IOException {
    this(endpoint, HttpAuthProvider.bearer(token), source, spool, capture, http);
  }

  /**
   * The constructor every other one ends in.
   *
   * @param auth supplies the Bearer for each batch and renews it once if the center refuses it
   * @throws IllegalArgumentException if the endpoint is not HTTPS outside localhost, or the client
   *     follows redirects (a redirected batch would leak its credential)
   * @throws IOException if the spool cannot be created or another process already owns it
   */
  public ExecutionReporter(
      URI endpoint,
      HttpAuthProvider auth,
      String source,
      Path spool,
      ContentCapture capture,
      HttpClient http)
      throws IOException {
    boolean local =
        "http".equals(endpoint.getScheme())
            && endpoint.getHost() != null
            && Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost());
    if (!"https".equals(endpoint.getScheme()) && !local) {
      throw new IllegalArgumentException("Execution reporting requires HTTPS outside localhost");
    }
    this.endpoint = endpoint.normalize();
    this.auth = Objects.requireNonNull(auth, "auth");
    this.source = source;
    // The capture owns its whitelist defensively, so the reporter can hold it as it is.
    this.capture = Objects.requireNonNull(capture, "capture");
    this.http = Objects.requireNonNull(http, "http");
    if (http.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException("Execution reporting requires redirects to be disabled");
    }
    this.spool =
        new EventSpool(spool, EventSpool.namespace(this.endpoint, source), MAX_SPOOL_BYTES);
    sender.scheduleWithFixedDelay(
        this::sendPending, 0, SEND_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
  }

  private static ScheduledThreadPoolExecutor worker(String name) {
    return new ScheduledThreadPoolExecutor(
        1,
        task -> {
          Thread thread = new Thread(task, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  Run begin(
      Workflow workflow,
      String executionId,
      String businessRef,
      String traceparent,
      String tracestate) {
    if (workflow.getRevision() == null || !workflow.getRevision().matches("[0-9a-f]{64}")) {
      rememberDelivery(executionId, DeliveryStatus.UNAVAILABLE);
      LOG.warn("Execution reporting skipped because the PSOP revision is unavailable");
      return null;
    }
    Run run = new Run(workflow, executionId, traceparent, tracestate);
    Map<String, Object> data = new HashMap<>();
    data.put("psop_id", workflow.getId());
    data.put("psop_revision", workflow.getRevision());
    if (businessRef != null && !businessRef.isBlank() && businessRef.length() <= 128) {
      data.put("business_ref", businessRef);
    }
    run.emit("execution.started", data);
    return run;
  }

  /** Sends what the spool holds, one execution at a time, giving each its own backoff. */
  private void sendPending() {
    try {
      for (Path execution : spool.pendingExecutions()) {
        if (retryAt.getOrDefault(execution, 0L) > System.nanoTime()) continue;
        try {
          if (spool.isRejected(execution.getFileName().toString())) {
            spool.discardPendingOf(execution);
            retryAt.remove(execution);
            continue;
          }
          long retry = sendBatch(execution);
          if (retry == 0) retryAt.remove(execution);
          else retryAt.put(execution, System.nanoTime() + TimeUnit.SECONDS.toNanos(retry));
        } catch (IOException error) {
          LOG.warn(
              "Could not read or acknowledge the execution observation spool for {} ({}: {}), will retry",
              execution.getFileName(),
              error.getClass().getSimpleName(),
              error.getMessage());
          retryAt.put(execution, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        } catch (UncheckedIOException error) {
          // The batch is still spooled; the connection dropped between batches.
          LOG.warn(
              "Could not send workflow observations for {} ({}: {}), will retry",
              execution.getFileName(),
              error.getCause().getClass().getSimpleName(),
              error.getCause().getMessage());
          retryAt.put(execution, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        } catch (RuntimeException error) {
          // A rejected credential or a malformed batch lands here: retry, but not in a tight loop.
          LOG.warn(
              "Could not send workflow observations for {} ({}: {}), will retry",
              execution.getFileName(),
              error.getClass().getSimpleName(),
              error.getMessage());
          retryAt.put(
              execution, System.nanoTime() + TimeUnit.SECONDS.toNanos(CREDENTIAL_RETRY_SECONDS));
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException error) {
      LOG.warn("The execution observation spool is temporarily unavailable");
    }
  }

  /**
   * Sends one execution's oldest events and applies the center's answer to the spool.
   *
   * @return the delay in seconds before this execution may be sent again; {@code 0} when its events
   *     were all taken
   */
  private long sendBatch(Path execution) throws IOException, InterruptedException {
    var pending = spool.readPending(execution, MAX_BATCH_EVENTS, MAX_BATCH_BYTES);
    List<Path> paths = pending.paths();
    List<JsonNode> events = new ArrayList<>();
    for (byte[] body : pending.bodies()) events.add(JSON.readTree(body));
    if (events.isEmpty()) return 0;
    String executionId = events.get(0).path("subject").asText();
    byte[] body = JSON.writeValueAsBytes(events);
    Map<String, String> headers = auth.headers(endpoint);
    var response = sendBatchRequest(headers, events.get(0), body);
    if (response.statusCode() == 401 && auth.onUnauthorized(endpoint, headers)) {
      response = sendBatchRequest(auth.headers(endpoint), events.get(0), body);
    }
    if (response.statusCode() == 200) {
      return acknowledge(execution, paths, events, executionId, parse(response.body()));
    }
    // The center answers every refusal with a problem document naming the reason.
    JsonNode problem = parse(response.body());
    String code = problem == null ? "" : problem.path("code").asText("");
    int index = problem == null ? -1 : problem.path("event_index").asInt(-1);
    switch (response.statusCode()) {
      case 409 -> {
        if ("SEQUENCE_WINDOW".equals(code)) {
          if (index < 0 || index >= events.size()) return retryAfter(response);
          long rejectedSeq = events.get(index).path("seq").asLong(0);
          return declareMissing(execution, events.get(0), problem, rejectedSeq - 1) ? 0 : 1;
        }
      }
      case 422, 413 -> {
        // A malformed event can never be accepted: replace it with a declared gap so the
        // rest of the run is not held hostage. Lifecycle events are never replaced.
        if (index >= 0
            && index < events.size()
            && !events.get(index).path("type").asText().startsWith(PREFIX + "execution.")) {
          var gap = ((ObjectNode) events.get(index)).deepCopy();
          gap.put("id", UUID.randomUUID().toString());
          gap.put("type", PREFIX + "execution.gap.declared.v1");
          gap.putObject("data").put("reason_code", "permanent_rejection");
          spool.persist(paths.get(index), JSON.writeValueAsBytes(gap), true);
          LOG.warn(
              "A workflow event the center rejected ({} seq={}) was replaced by a gap declaration",
              code,
              events.get(index).path("seq").asText());
          return 0;
        }
      }
      default -> {}
    }
    if (FINAL_CONFLICTS.contains(code)) {
      // No retry can make the center accept this run. Set it aside once, with the reason.
      setAside(execution, executionId, code, response.statusCode());
      return 0;
    }
    LOG.warn(
        "The center refused a workflow observation batch ({} execution={} status={} code={})",
        events.size(),
        executionId,
        response.statusCode(),
        code.isEmpty() ? "none" : code);
    // Credentials and permissions are a host configuration problem: back off, but do not spin.
    int status = response.statusCode();
    return status == 401 || status == 403 ? CREDENTIAL_RETRY_SECONDS : retryAfter(response);
  }

  private HttpResponse<byte[]> sendBatchRequest(
      Map<String, String> headers, JsonNode first, byte[] body) throws InterruptedException {
    var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5));
    headers.forEach(request::setHeader);
    request.setHeader("Content-Type", "application/cloudevents-batch+json");
    if (first.has("traceparent"))
      request.setHeader("traceparent", first.get("traceparent").asText());
    if (first.has("tracestate")) request.setHeader("tracestate", first.get("tracestate").asText());
    try {
      return http.send(
          request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
          HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException error) {
      // A pooled keep-alive connection can be closed by the server between batches. That is a
      // transport failure to retry promptly, not an unreadable spool, so it is rethrown unchecked
      // and the IOException branch below stays about the spool alone.
      throw new UncheckedIOException(error);
    }
  }

  /**
   * Applies the center's acknowledgement: takes the events it confirms and declares the gaps it
   * reports. The execution is recorded as delivered once its terminal event is among them.
   *
   * @return {@code 0} when the batch was handled, {@code 1} when the acknowledgement did not
   *     account for this batch and it should be sent again
   */
  private long acknowledge(
      Path execution, List<Path> paths, List<JsonNode> events, String executionId, JsonNode ack)
      throws IOException {
    if (ack == null || !executionId.equals(ack.path("execution_id").asText())) return 1;
    long contiguousThrough = ack.path("contiguous_through").asLong(-1);
    int accepted = ack.path("accepted").asInt(-1);
    int duplicates = ack.path("duplicates").asInt(-1);
    if (contiguousThrough < 0
        || accepted < 0
        || duplicates < 0
        || (long) accepted + duplicates != events.size()) return 1;

    long lastSent = events.get(events.size() - 1).path("seq").asLong(0);
    long observedThrough = ack.path("observed_through").asLong(lastSent);
    declareMissing(execution, events.get(0), ack, observedThrough);
    List<Path> taken = new ArrayList<>();
    boolean terminalAccepted = false;
    for (int index = 0; index < events.size(); index++) {
      JsonNode event = events.get(index);
      if ((PREFIX + "execution.finished.v1").equals(event.path("type").asText())) {
        // Keep the terminal and its stable ID until the center confirms the complete prefix.
        if (event.path("seq").asLong() > contiguousThrough) continue;
        terminalAccepted = true;
      }
      taken.add(paths.get(index));
    }
    spool.acknowledge(taken);
    if (terminalAccepted) {
      rememberDelivery(executionId, DeliveryStatus.ACKNOWLEDGED);
      LOG.info("Execution reporting confirmed by the center, execution_id={}", executionId);
    }
    return 0;
  }

  /**
   * Declares the events the center says it never received, as far as its receive window allows.
   *
   * @return whether any gap was written
   */
  private boolean declareMissing(
      Path execution, JsonNode example, JsonNode ack, long highestEligible) throws IOException {
    long contiguous = ack.path("contiguous_through").asLong(0);
    if (contiguous < 0 || contiguous >= Long.MAX_VALUE - RECEIVE_WINDOW) return false;
    long limit = Math.min(highestEligible, contiguous + RECEIVE_WINDOW);
    boolean staged = false;
    for (JsonNode range : ack.path("missing_ranges")) {
      if (!range.isArray() || range.size() != 2) continue;
      long low = range.path(0).asLong(0);
      long high = Math.min(range.path(1).asLong(0), limit);
      if (low <= 0) continue;
      for (long seq = Math.max(low, Math.max(2, contiguous + 1)); seq <= high; seq++) {
        if (spool.has(execution, seq)) continue;
        var gap = ((ObjectNode) example).deepCopy();
        gap.put("id", UUID.randomUUID().toString());
        gap.put("seq", Long.toString(seq));
        gap.put("type", PREFIX + "execution.gap.declared.v1");
        gap.putObject("data").put("reason_code", "spool_loss");
        spool.persist(
            execution.resolve(EventSpool.eventFileName(seq)), JSON.writeValueAsBytes(gap), true);
        staged = true;
      }
    }
    return staged;
  }

  /** Stops reporting an execution the center refused for good, and records the center's reason. */
  private void setAside(Path execution, String executionId, String code, int status)
      throws IOException {
    spool.reject(execution, "{\"status\":" + status + ",\"code\":\"" + code + "\"}");
    LOG.warn(
        "Execution {} was set aside as rejected ({} {}); its pending observations are kept under"
            + " the spool's rejected directory",
        executionId,
        status,
        code);
    rememberDelivery(executionId, DeliveryStatus.REJECTED);
  }

  /** Parses a response body once, tolerating an empty or non-JSON body. */
  private static JsonNode parse(byte[] body) {
    try {
      return body == null || body.length == 0 ? null : JSON.readTree(body);
    } catch (IOException error) {
      return null;
    }
  }

  /** Honors the center's Retry-After and bounds it, so a bad hint cannot stall reporting. */
  private static long retryAfter(HttpResponse<?> response) {
    String retry = response.headers().firstValue("Retry-After").orElse("1");
    return retry.matches("[0-9]{1,5}")
        ? Math.min(MAX_RETRY_AFTER_SECONDS, Integer.parseInt(retry))
        : 1;
  }

  /**
   * Waits for queued observations to be acknowledged and removed from the spool. Call after runs
   * have completed, before closing a short-lived host. Pending files remain eligible for retry.
   */
  public boolean awaitDelivery(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    // Two queues stand between an event and the center: the writer's, and the spool's. They are
    // checked together on every pass: seeing one empty says nothing about the other, and the
    // event a caller cares about may have just moved from the first into the second.
    while (!writerQueueIsEmpty() || !spool.isEmpty()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      TimeUnit.NANOSECONDS.sleep(
          Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS)));
    }
    return !lifecycleWriteFailed();
  }

  /** Whether no event is still waiting to be written; the spool only sees what reached disk. */
  private boolean writerQueueIsEmpty() {
    return slots.availablePermits() == QUEUE_CAPACITY;
  }

  private boolean lifecycleWriteFailed() {
    synchronized (deliveryLock) {
      return lifecycleWriteFailed;
    }
  }

  /** What became of an execution's report, as far as this host can tell. */
  public enum DeliveryStatus {
    /** The center confirmed the execution's last event. */
    ACKNOWLEDGED,
    /** Not confirmed yet within the time the host waited. */
    PENDING,
    /** The center refused the execution for good; it will not be sent again. */
    REJECTED,
    /** The host could not queue the execution's start or end, so it cannot be reported. */
    UNAVAILABLE
  }

  /**
   * Records an execution's outcome. An outcome already final is never softened: once rejected or
   * unavailable, a later acknowledgement from the queue's replay does not make it delivered.
   */
  private void rememberDelivery(String id, DeliveryStatus status) {
    synchronized (deliveryLock) {
      DeliveryStatus previous = deliveries.get(id);
      if (previous == DeliveryStatus.REJECTED
          || (previous == DeliveryStatus.UNAVAILABLE && status == DeliveryStatus.ACKNOWLEDGED)) {
        return;
      }
      deliveries.remove(id);
      deliveries.put(id, status);
      if (deliveries.size() > MAX_RECENT_DELIVERIES) {
        deliveries.remove(deliveries.keySet().iterator().next());
      }
      deliveryLock.notifyAll();
    }
  }

  /**
   * Waits only for this execution's terminal acknowledgement. Call after the workflow finishes. A
   * pending outcome leaves durable events available for replay with the same destination/source.
   *
   * @throws IllegalArgumentException if {@code executionId} is not a canonical UUID
   */
  public DeliveryStatus awaitDelivery(String executionId, Duration timeout)
      throws InterruptedException {
    UUID identifier = UUID.fromString(executionId);
    if (!identifier.toString().equals(executionId)) {
      throw new IllegalArgumentException("Expected a canonical execution UUID");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      // Only the outcome table is read under the lock. The spool's files are checked outside it:
      // they are the spool's own concern, and no writer needs this lock to touch them.
      DeliveryStatus status;
      synchronized (deliveryLock) {
        status = deliveries.get(executionId);
      }
      if (status == DeliveryStatus.REJECTED || status == DeliveryStatus.UNAVAILABLE) {
        return status;
      }
      if (spool.isRejected(executionId)) return DeliveryStatus.REJECTED;
      // Acknowledged is only final once nothing of the execution is left on disk.
      if (status == DeliveryStatus.ACKNOWLEDGED && !spool.isPending(executionId)) {
        return status;
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return DeliveryStatus.PENDING;
      synchronized (deliveryLock) {
        // A recorded outcome wakes this wait early. The spool emptying does not, so the wait is
        // capped and the loop re-checks the files. Object.wait takes milliseconds.
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MILLIS);
        deliveryLock.wait(
            Math.max(1, TimeUnit.NANOSECONDS.toMillis(Math.min(remaining, pollNanos))));
      }
    }
  }

  /** Stops the background threads, gives queued events their delivery budget, frees the spool. */
  @Override
  public void close() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(THREAD_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        writer.shutdownNow();
      }
      awaitDelivery(CLOSE_DELIVERY_BUDGET);
    } catch (InterruptedException error) {
      writer.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      sender.shutdownNow();
      boolean interrupted = Thread.interrupted();
      try {
        sender.awaitTermination(THREAD_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        interrupted = true;
      } finally {
        spool.close();
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * One execution's events, translated from engine events to the center's CloudEvents. Holds the
   * run's step and task identity and the content gathered so far; delivery itself belongs to the
   * enclosing reporter.
   */
  final class Run implements ObservationSink.Observation {
    private static final String TASK_T = A2ATExtension.TASK_T.uri();

    private final String id;
    private final String traceparent;
    private final String tracestate;
    private final Map<String, String> steps = new HashMap<>();
    private final Map<String, Map<String, Object>> tasks = new HashMap<>();

    /**
     * Text fragments per logical task, kept for the task's final summary. The event data is kept by
     * reference: deriving the text from it is writer work, so a large answer never costs the
     * execution anything.
     */
    private final Map<String, List<Map<String, Object>>> taskText = new HashMap<>();

    /** Final agent replies per logical task, as the event data they arrived in. */
    private final Map<String, List<Map<String, Object>>> agentReplies = new HashMap<>();

    /** Fragments kept per task; the summary is truncated anyway, so older ones add nothing. */
    private static final int MAX_TEXT_FRAGMENTS = 32;

    private long sequence;
    private volatile boolean degraded;
    private boolean terminal;

    private Run(Workflow workflow, String id, String traceparent, String tracestate) {
      this.id = id;
      this.traceparent =
          TRACE_PARENT.matcher(traceparent == null ? "" : traceparent).matches()
              ? traceparent
              : null;
      this.tracestate =
          this.traceparent != null
                  && tracestate != null
                  && TRACE_STATE.matcher(tracestate).matches()
              ? tracestate
              : null;
      for (int i = 0; i < workflow.getSteps().size(); i++) {
        steps.put(workflow.getSteps().get(i).getName(), "s" + i);
      }
    }

    @Override
    public synchronized void observe(String type, Map<String, Object> data) {
      if (terminal) return;
      String step = steps.get(data.get("step"));
      if (step != null && type.equals("step_start")) {
        emit("step.started", Map.of("step_ref", step));
      } else if (step != null && (type.equals("step_complete") || type.equals("error"))) {
        emit(
            "step.finished",
            Map.of("step_ref", step, "outcome", type.equals("error") ? "failed" : "succeeded"));
      }
      String taskId = data.get("taskId") instanceof String value ? value : null;
      if (type.equals("task_request")
          && step != null
          && data.get("subtask_index") instanceof Number index
          && data.get("taskId") instanceof String requestedId) {
        Map<String, Object> identity =
            Map.of(
                "step_ref",
                step,
                "task_ref",
                step + "/t" + index.intValue(),
                "task_id",
                requestedId,
                "attempt",
                1);
        tasks.put(requestedId, identity);
        emit("task.started", identity);
        return;
      }
      if (taskId == null || !tasks.containsKey(taskId)) return;
      Map<String, Object> event = new HashMap<>(tasks.get(taskId));
      if (INTERACTIONS.containsKey(type)) {
        progress(event, type, taskId, data);
      } else if (CONTENT_EVENTS.contains(type)) {
        // Intermediate answer text is worth a timeline entry of its own, without a phase label.
        if (capture.enabled() && !ContentCapture.textOf(data).isBlank()) {
          remember(type, taskId, data);
          emit(
              "task.progressed",
              event,
              deferred -> {
                String text = SensitiveDataRedactor.redact(ContentCapture.textOf(data));
                if (!text.isBlank()) {
                  deferred.put("summary", ContentCapture.truncate(text, summaryLimit()));
                }
              });
        }
      } else if (type.equals("task_response")) {
        finishTask(event, taskId, data);
      }
    }

    /** Reports a phase change, with the agent's text when content capture is on. */
    private void progress(
        Map<String, Object> event, String type, String taskId, Map<String, Object> data) {
      List<String> interaction = INTERACTIONS.get(type);
      event.put("interaction", Map.of("kind", interaction.get(0), "phase", interaction.get(1)));
      if (!capture.enabled()) {
        emit("task.progressed", event);
        return;
      }
      boolean carriesAnswer = type.equals("agent_response") || CONTENT_EVENTS.contains(type);
      if (carriesAnswer) remember(type, taskId, data);
      // The phase label alone cannot tell an operator what the agent said, and reading the
      // request body or the agent's messages costs time proportional to their size: defer it.
      MessageContent request =
          type.equals("agent_request") && data.get("content") instanceof MessageContent message
              ? message
              : null;
      emit(
          "task.progressed",
          event,
          deferred -> {
            if (request != null) {
              String input = ContentCapture.requestSummary(request);
              if (!input.isBlank()) {
                deferred.put("input_summary", ContentCapture.truncate(input, summaryLimit()));
              }
            }
            if (carriesAnswer) {
              String text = answerText(type, data);
              if (!text.isBlank()) {
                deferred.put("summary", ContentCapture.truncate(text, summaryLimit()));
              }
            }
          });
    }

    /**
     * Keeps the event an agent's answer arrived in, for the task's final summary.
     *
     * <p>Only the reference is kept: deriving the text happens on the writer thread.
     */
    private void remember(String type, String taskId, Map<String, Object> data) {
      if (!capture.enabled()) return;
      boolean reply = type.equals("agent_response");
      if (!reply && !CONTENT_EVENTS.contains(type)) return;
      List<Map<String, Object>> fragments =
          (reply ? agentReplies : taskText).computeIfAbsent(taskId, key -> new ArrayList<>());
      if (fragments.size() < MAX_TEXT_FRAGMENTS) fragments.add(data);
    }

    /** The agent-visible text an engine event carries, as this capture would report it. */
    private static String answerText(String type, Map<String, Object> data) {
      return type.equals("agent_response")
          ? ContentCapture.responseSummary(data)
          : SensitiveDataRedactor.redact(ContentCapture.textOf(data));
    }

    /** Reports the task's outcome; its content is assembled on the writer thread. */
    private void finishTask(Map<String, Object> event, String taskId, Map<String, Object> data) {
      boolean cancelled = "cancelled".equals(data.get("status"));
      event.put(
          "outcome",
          cancelled
              ? "cancelled"
              : Boolean.TRUE.equals(data.get("success")) ? "succeeded" : "failed");
      List<Map<String, Object>> replies = List.copyOf(agentReplies.getOrDefault(taskId, List.of()));
      List<Map<String, Object>> answers = List.copyOf(taskText.getOrDefault(taskId, List.of()));
      emit(
          "task.finished",
          event,
          deferred -> {
            Map<String, Object> result = new HashMap<>();
            Map<String, Object> outputs = capture.collectOutputs(data);
            if (!outputs.isEmpty()) result.put("outputs", outputs);
            String content = conclusion(data, replies, answers);
            if (!content.isEmpty()) {
              // result.summary carries the readable task content; the top-level summary stays
              // within the center's per-field cap.
              result.put("summary", ContentCapture.truncate(content, capture.summaryChars()));
              deferred.put(
                  "summary", ContentCapture.truncate(content, ContentCapture.SUMMARY_FIELD_LIMIT));
            }
            if (!result.isEmpty()) deferred.put("result", result);
          });
      taskText.remove(taskId);
      agentReplies.remove(taskId);
      tasks.remove(taskId);
    }

    /**
     * The task's conclusion, most authoritative first: its own response text, its agent's final
     * reply, its plain-text outputs, then the intermediate answers.
     */
    private String conclusion(
        Map<String, Object> data,
        List<Map<String, Object>> replies,
        List<Map<String, Object>> answers) {
      if (!capture.enabled()) return "";
      String text = SensitiveDataRedactor.redact(ContentCapture.textOf(data));
      if (!text.isBlank()) return text;
      String reply =
          replies.stream()
              .map(ContentCapture::responseSummary)
              .filter(value -> !value.isBlank())
              .collect(Collectors.joining("\n\n"));
      if (!reply.isBlank()) return ContentCapture.truncate(reply, capture.summaryChars());
      if (data.get("outputs") instanceof List<?> items) {
        String output =
            items.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank())
                .map(SensitiveDataRedactor::redact)
                .collect(Collectors.joining("\n\n"));
        if (!output.isBlank()) return ContentCapture.truncate(output, capture.summaryChars());
      }
      String answer =
          answers.stream()
              .map(entry -> SensitiveDataRedactor.redact(ContentCapture.textOf(entry)))
              .filter(value -> !value.isBlank())
              .collect(Collectors.joining("\n\n"));
      return ContentCapture.truncate(answer, capture.summaryChars());
    }

    /** The largest summary a single progress event may carry. */
    private int summaryLimit() {
      return Math.min(capture.summaryChars(), ContentCapture.SUMMARY_FIELD_LIMIT);
    }

    @Override
    public synchronized void finish(String outcome) {
      if (terminal) return;
      terminal = true;
      var data = new HashMap<String, Object>();
      data.put("outcome", outcome);
      if (degraded) data.put("capture_status", "degraded");
      emit("execution.finished", data);
    }

    /** Records that this run's report could not be queued or written, so it cannot be delivered. */
    private void markLifecycleFailure() {
      synchronized (deliveryLock) {
        lifecycleWriteFailed = true;
      }
      rememberDelivery(id, DeliveryStatus.UNAVAILABLE);
    }

    /**
     * Queues one event for the writer thread. Lifecycle events always find queue room; any other
     * event is dropped, and the run marked degraded, when only the reserve is left.
     */
    private void emit(String kind, Map<String, Object> data) {
      emit(kind, data, null);
    }

    /**
     * Queues one event, with a content step that runs on the writer thread.
     *
     * <p>The step fills the parts of the payload whose size the run does not control: outputs,
     * summaries, redacted text. Running it there keeps the execution's own thread free of work that
     * only reporting needs.
     */
    private void emit(
        String kind,
        Map<String, Object> data,
        java.util.function.Consumer<Map<String, Object>> content) {
      boolean lifecycle = kind.equals("execution.started") || kind.equals("execution.finished");
      boolean crowded = !lifecycle && slots.availablePermits() <= LIFECYCLE_RESERVE;
      if (crowded || !slots.tryAcquire()) {
        if (lifecycle) markLifecycleFailure();
        degraded = true;
        LOG.warn("The execution reporter queue is full");
        return;
      }
      long seq = sequence + 1;
      var event = new HashMap<String, Object>();
      event.put("specversion", "1.0");
      event.put("id", UUID.randomUUID().toString());
      event.put("source", source);
      event.put("type", PREFIX + kind + ".v1");
      event.put("subject", id);
      event.put("time", Instant.now().toString());
      event.put("datacontenttype", "application/json");
      event.put("seq", Long.toString(seq));
      event.put("data", data);
      if (traceparent != null) event.put("traceparent", traceparent);
      if (tracestate != null) event.put("tracestate", tracestate);
      try {
        writer.execute(
            () -> {
              try {
                if (degraded && kind.equals("execution.finished"))
                  data.put("capture_status", "degraded");
                if (content != null) {
                  try {
                    content.accept(data);
                  } catch (RuntimeException error) {
                    // One event's content is not worth the run, and never the execution's.
                    degraded = true;
                    LOG.warn("Could not capture an observation event's content", error);
                  }
                }
                byte[] payload = ContentCapture.fitToLimit(event);
                // Whether this execution was refused is the spool's to know, and it checks under
                // its own lock; nothing here needs the reporter's.
                spool.persistUnlessRejected(
                    spool.executionFolder(id).resolve(EventSpool.eventFileName(seq)),
                    id,
                    payload,
                    lifecycle);
              } catch (IOException error) {
                if (lifecycle) markLifecycleFailure();
                degraded = true;
                LOG.warn("Could not persist workflow observation event");
              } finally {
                slots.release();
              }
            });
        sequence = seq;
      } catch (RejectedExecutionException error) {
        if (lifecycle) markLifecycleFailure();
        slots.release();
        degraded = true;
      }
    }
  }
}

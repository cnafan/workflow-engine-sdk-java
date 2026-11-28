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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.registry.OrchestrationAccountAuth;
import dev.openan.workflow.engine.registry.TlsPolicy;
import dev.openan.workflow.engine.runner.ExecutionReporter.DeliveryStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionReporterDeliveryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SOURCE = "urn:openan:host:delivery-test";

  @Test
  void accountSessionRefreshReplaysTheSameEvents(@TempDir Path spool) throws Exception {
    var server = server();
    var logins = new AtomicInteger();
    var rejected = new ConcurrentLinkedQueue<JsonNode>();
    var accepted = new ConcurrentLinkedQueue<JsonNode>();
    server.createContext(
        "/api/v1/auth/token",
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            reply(
                exchange,
                200,
                Map.of(
                    "access_token",
                    "center-session-" + logins.incrementAndGet(),
                    "token_type",
                    "Bearer",
                    "expires_in",
                    60));
          }
        });
    server.createContext(
        "/api/v1/execution-events",
        exchange -> {
          try (exchange) {
            JsonNode batch = readBatch(exchange);
            if ("Bearer center-session-2"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              accepted.add(batch);
              acknowledge(exchange, batch);
            } else {
              rejected.add(batch);
              reply(exchange, 401, Map.of());
            }
          }
        });
    server.start();
    try {
      String center = "http://127.0.0.1:" + server.getAddress().getPort();
      var auth = new OrchestrationAccountAuth(center, "reporter", "synthetic", TlsPolicy.strict());
      try (var reporter =
          new ExecutionReporter(
              URI.create(center + "/api/v1/execution-events"),
              auth,
              SOURCE,
              spool,
              ContentCapture.DISABLED,
              HttpClient.newHttpClient())) {
        String run = UUID.randomUUID().toString();
        finish(reporter, run);
        assertEquals(
            DeliveryStatus.ACKNOWLEDGED, reporter.awaitDelivery(run, Duration.ofSeconds(5)));
        assertEquals(2, logins.get());
        assertEquals(1, rejected.size());
        assertEquals(rejected.element(), accepted.element());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void closeDrainsCurrentExecutionAfterAnEarlierLifecycleWriteFailure(@TempDir Path spool)
      throws Exception {
    String old = UUID.randomUUID().toString();
    String current = UUID.randomUUID().toString();
    var server = server();
    server.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            var batch = readBatch(exchange);
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            acknowledge(exchange, batch);
          }
        });
    server.start();
    try {
      var reporter = reporter(server, "/events", spool);
      try {
        try (var folders = Files.walk(spool)) {
          Path pending =
              folders
                  .filter(path -> path.getFileName().toString().equals("pending"))
                  .findFirst()
                  .orElseThrow();
          Files.createFile(pending.resolve(old));
        }
        finish(reporter, old);
        assertEquals(
            DeliveryStatus.UNAVAILABLE, reporter.awaitDelivery(old, Duration.ofSeconds(5)));
        finish(reporter, current);
      } finally {
        reporter.close();
      }
      assertEquals(DeliveryStatus.ACKNOWLEDGED, reporter.awaitDelivery(current, Duration.ZERO));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aRejectedPriorRunAndUnboundFilesCannotBlockCurrentAcknowledgement(@TempDir Path spool)
      throws Exception {
    String old = UUID.randomUUID().toString();
    String current = UUID.randomUUID().toString();
    Path oldRoot = Files.createDirectories(spool.resolve(UUID.randomUUID().toString()));
    Files.writeString(oldRoot.resolve("0000000000000001.json"), "{}");
    var rejectedCalls = new AtomicInteger();
    var unexpectedCalls = new AtomicInteger();
    var server = server();
    server.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            var batch = readBatch(exchange);
            String id = batch.get(0).path("subject").asText();
            if (old.equals(id)) {
              rejectedCalls.incrementAndGet();
              reply(exchange, 410, Map.of("code", "EXECUTION_CLOSED"));
            } else if (current.equals(id)) {
              acknowledge(exchange, batch);
            } else {
              unexpectedCalls.incrementAndGet();
              reply(exchange, 400, Map.of("code", "UNKNOWN_EXECUTION"));
            }
          }
        });
    server.start();
    try (var reporter = reporter(server, "/events", spool)) {
      finish(reporter, old);
      finish(reporter, current);
      assertEquals(DeliveryStatus.REJECTED, reporter.awaitDelivery(old, Duration.ofSeconds(5)));
      assertEquals(
          DeliveryStatus.ACKNOWLEDGED, reporter.awaitDelivery(current, Duration.ofSeconds(5)));
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));
      assertEquals(1, rejectedCalls.get());
      assertEquals(0, unexpectedCalls.get());
      assertTrue(Files.isRegularFile(oldRoot.resolve("0000000000000001.json")));
      try (var files = Files.walk(spool)) {
        assertTrue(files.anyMatch(path -> path.getFileName().toString().equals(old + ".json")));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void genericGoneAndIncompleteAcknowledgementLeaveEventsForReplay(@TempDir Path spool)
      throws Exception {
    String id = UUID.randomUUID().toString();
    var phase = new AtomicInteger();
    var calls = new AtomicInteger();
    var server = server();
    server.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            var batch = readBatch(exchange);
            calls.incrementAndGet();
            switch (phase.get()) {
              case 0 -> reply(exchange, 410, Map.of("code", "ROUTE_GONE"));
              case 1 ->
                  reply(
                      exchange,
                      200,
                      Map.of("execution_id", id, "accepted", batch.size(), "duplicates", 0));
              default -> acknowledge(exchange, batch);
            }
          }
        });
    server.start();
    try {
      try (var reporter = reporter(server, "/events", spool)) {
        finish(reporter, id);
        assertEquals(DeliveryStatus.PENDING, reporter.awaitDelivery(id, Duration.ofSeconds(1)));
        assertTrue(calls.get() > 0);
      }
      phase.set(1);
      int firstCalls = calls.get();
      try (var reporter = reporter(server, "/events", spool)) {
        assertEquals(DeliveryStatus.PENDING, reporter.awaitDelivery(id, Duration.ofSeconds(1)));
        assertTrue(calls.get() > firstCalls);
      }
      phase.set(2);
      try (var reporter = reporter(server, "/events", spool)) {
        assertEquals(
            DeliveryStatus.ACKNOWLEDGED, reporter.awaitDelivery(id, Duration.ofSeconds(5)));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void pendingFilesAreReplayedOnlyByTheSameDestination(@TempDir Path spool) throws Exception {
    String queued = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();
    var originAvailable = new AtomicBoolean(false);
    var otherReceived = new ConcurrentLinkedQueue<String>();
    var server = server();
    server.createContext(
        "/origin",
        exchange -> {
          try (exchange) {
            var batch = readBatch(exchange);
            if (originAvailable.get()) acknowledge(exchange, batch);
            else reply(exchange, 503, Map.of("code", "UNAVAILABLE"));
          }
        });
    server.createContext(
        "/different",
        exchange -> {
          try (exchange) {
            var batch = readBatch(exchange);
            otherReceived.add(batch.get(0).path("subject").asText());
            acknowledge(exchange, batch);
          }
        });
    server.start();
    try {
      try (var first = reporter(server, "/origin", spool)) {
        finish(first, queued);
        assertEquals(DeliveryStatus.PENDING, first.awaitDelivery(queued, Duration.ofMillis(200)));
      }
      try (var second = reporter(server, "/different", spool)) {
        finish(second, other);
        assertEquals(
            DeliveryStatus.ACKNOWLEDGED, second.awaitDelivery(other, Duration.ofSeconds(5)));
        assertFalse(otherReceived.contains(queued));
      }
      originAvailable.set(true);
      try (var restarted = reporter(server, "/origin", spool)) {
        assertEquals(
            DeliveryStatus.ACKNOWLEDGED, restarted.awaitDelivery(queued, Duration.ofSeconds(5)));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aMissingPrefixLargerThanTheReceiveWindowCanBeReconciledAfterRestart(@TempDir Path spool)
      throws Exception {
    String id = UUID.randomUUID().toString();
    var online = new AtomicBoolean(false);
    var conflicts = new AtomicInteger();
    var received = new TreeMap<Long, String>();
    var contiguous = new AtomicLong();
    var server = server();
    server.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            var batch = readBatch(exchange);
            if (!online.get()) {
              reply(exchange, 503, Map.of("code", "UNAVAILABLE"));
              return;
            }
            long watermark = contiguous.get();
            for (int index = 0; index < batch.size(); index++) {
              long seq = batch.get(index).path("seq").asLong();
              if (seq > watermark + 1024) {
                conflicts.incrementAndGet();
                reply(
                    exchange,
                    409,
                    Map.of(
                        "code",
                        "SEQUENCE_WINDOW",
                        "event_index",
                        index,
                        "contiguous_through",
                        Long.toString(watermark),
                        "missing_ranges",
                        List.of(
                            List.of(
                                Long.toString(watermark + 1),
                                Long.toString(Math.min(seq - 1, watermark + 1024))))));
                return;
              }
            }
            synchronized (received) {
              for (JsonNode event : batch) {
                received.put(event.path("seq").asLong(), event.path("type").asText());
              }
              while (received.containsKey(watermark + 1)) watermark++;
              contiguous.set(watermark);
            }
            var missing = new ArrayList<List<String>>();
            synchronized (received) {
              for (long seq = watermark + 1; seq < received.lastKey(); seq++) {
                if (!received.containsKey(seq)) {
                  String position = Long.toString(seq);
                  missing.add(List.of(position, position));
                }
              }
            }
            reply(
                exchange,
                200,
                Map.of(
                    "execution_id",
                    id,
                    "accepted",
                    batch.size(),
                    "duplicates",
                    0,
                    "contiguous_through",
                    Long.toString(watermark),
                    "missing_ranges",
                    missing));
          }
        });
    server.start();
    try {
      try (var first = reporter(server, "/events", spool)) {
        finish(first, id);
      }
      Path pending;
      try (var files = Files.walk(spool)) {
        pending =
            files
                .filter(path -> path.getFileName().toString().equals(id))
                .findFirst()
                .orElseThrow();
      }
      var oldTerminal = pending.resolve("0000000000000002.json");
      ObjectNode terminal = (ObjectNode) JSON.readTree(oldTerminal.toFile());
      terminal.put("seq", "1050");
      JSON.writeValue(pending.resolve("0000000000001050.json").toFile(), terminal);
      var retained = terminal.deepCopy();
      retained.put("seq", "1028");
      retained.put("id", UUID.randomUUID().toString());
      retained.put("type", "dev.openan.workflow.step.started.v1");
      retained.putObject("data").put("step_ref", "s0");
      JSON.writeValue(pending.resolve("0000000000001028.json").toFile(), retained);
      Files.delete(oldTerminal);

      online.set(true);
      try (var restarted = reporter(server, "/events", spool)) {
        assertEquals(
            DeliveryStatus.ACKNOWLEDGED, restarted.awaitDelivery(id, Duration.ofSeconds(40)));
        assertTrue(conflicts.get() > 0);
        synchronized (received) {
          assertEquals(1050, received.size());
          assertEquals("dev.openan.workflow.execution.started.v1", received.get(1L));
          assertEquals("dev.openan.workflow.execution.gap.declared.v1", received.get(1025L));
          assertEquals("dev.openan.workflow.step.started.v1", received.get(1028L));
          assertEquals("dev.openan.workflow.execution.finished.v1", received.get(1050L));
        }
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aConflictNoRetryCanResolveIsSetAsideOnce(@TempDir Path spool) throws Exception {
    // The center already holds a different event under this sequence: retrying can never succeed,
    // and doing it forever would hold the spool hostage.
    String id = UUID.randomUUID().toString();
    var attempts = new AtomicInteger();
    var server = server();
    server.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            readBatch(exchange);
            attempts.incrementAndGet();
            reply(exchange, 409, Map.of("code", "SEQUENCE_OR_EVENT_ID_CONFLICT", "event_index", 0));
          }
        });
    server.start();
    try (var reporter = reporter(server, "/events", spool)) {
      finish(reporter, id);
      assertEquals(DeliveryStatus.REJECTED, reporter.awaitDelivery(id, Duration.ofSeconds(5)));
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)), "the outbox must drain");
      assertEquals(1, attempts.get(), "one attempt, then the run is set aside");
      try (var files = Files.walk(spool)) {
        assertTrue(
            files.anyMatch(path -> path.getFileName().toString().equals(id + ".json")),
            "a receipt keeps the execution ID and the center's reason");
      }
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer server() throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serveConcurrently(http);
    return http;
  }

  /**
   * Serves every exchange on its own thread. The default executor handles one at a time, so the
   * reporter's keep-alive connection can otherwise hold up the batch that follows it.
   */
  private static void serveConcurrently(HttpServer http) {
    http.setExecutor(
        java.util.concurrent.Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "stub-center");
              thread.setDaemon(true);
              return thread;
            }));
  }

  private static ExecutionReporter reporter(HttpServer server, String path, Path spool)
      throws IOException {
    return new ExecutionReporter(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
        "test-key",
        SOURCE,
        spool);
  }

  private static void finish(ExecutionReporter reporter, String id) {
    var step =
        WorkflowStep.builder()
            .name("step")
            .subtasks(List.of(Task.builder().agent("A").skill("s").description("dummy").build()))
            .build();
    var workflow = Workflow.builder().name("flow").id("psop").steps(List.of(step)).build();
    workflow.setRevision("a".repeat(64));
    reporter.begin(workflow, id, null, null, null).finish("succeeded");
  }

  private static JsonNode readBatch(HttpExchange exchange) throws IOException {
    return JSON.readTree(exchange.getRequestBody().readAllBytes());
  }

  private static void acknowledge(HttpExchange exchange, JsonNode batch) throws IOException {
    reply(
        exchange,
        200,
        Map.of(
            "execution_id",
            batch.get(0).path("subject").asText(),
            "accepted",
            batch.size(),
            "duplicates",
            0,
            "contiguous_through",
            batch.get(batch.size() - 1).path("seq").asText()));
  }

  private static void reply(HttpExchange exchange, int status, Map<String, Object> body)
      throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}

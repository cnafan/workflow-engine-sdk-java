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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The execution history must be able to show what each step produced. These tests pin the opt-in
 * content shape: the per-task readable text travels in {@code result.summary}, the outputs in
 * {@code result.outputs}, and the status-only default never carries either.
 */
class ExecutionReporterContentTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void reportsPerTaskContentAndEveryOutputWhenCaptureIsOn(@TempDir Path spool) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool,
            ContentCapture.all())) {
      ExecutionReporter.Run run = beginRun(reporter);
      assertNotNull(run);

      run.observe("task_request", identity("diagnose", 0, "task-1"));
      var request =
          new MessageContent(
              MessageContent.text("generic wrapper").parts(),
              Map.of(
                  A2ATExtension.TASK_T.uri(),
                  Map.of("instruction", "Inspect City1 line", "apiKey", "synthetic-secret")),
              Set.of(A2ATExtension.TASK_T.uri()));
      run.observe("agent_request", with(identity("diagnose", 0, "task-1"), "content", request));
      run.observe(
          "agent_response",
          with(
              identity("diagnose", 0, "task-1"),
              "agent",
              "City1 Agent",
              "response",
              "optical risk on P499037"));
      // The engine reports the agent answer on its own event, so task_response carries no text
      // here: the reporter must remember what the agent already said.
      run.observe(
          "agent_response",
          with(
              identity("diagnose", 0, "task-1"),
              "agent",
              "City1 Agent",
              "response",
              "Task completed",
              "receivedMessages",
              List.of(
                  new ReceivedMessage(
                      null,
                      Map.of(
                          A2ATExtension.TASK_T.uri(), "Diagnosis: the fault is on the City1 side."),
                      List.of()))));
      run.observe(
          "task_response",
          with(
              identity("diagnose", 0, "task-1"),
              "success",
              true,
              "agent",
              "City1 Agent",
              "task",
              "SPN private line diagnosis",
              "outputs",
              List.of(
                  Map.of("conclusion", "fault on City1", "nested", Map.of("port", "P499037")))));
      run.observe("task_request", identity("merge", 0, "task-local"));
      run.observe(
          "task_response",
          with(
              identity("merge", 0, "task-local"),
              "success",
              true,
              "outputs",
              List.of("Aggregate: City1 is the site of the fault")));
      run.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));

      JsonNode finished = await(received, "task.finished.v1");
      JsonNode data = finished.get("data");
      assertEquals("succeeded", data.get("outcome").asText());
      assertTrue(data.get("result").get("summary").asText().contains("fault is on the City1 side"));
      assertTrue(
          data.get("result").get("summary").asText().contains("optical risk on P499037"),
          "every agent answer of the task must survive into the finished task");
      assertEquals("fault on City1", data.get("result").get("outputs").get("conclusion").asText());
      assertEquals("P499037", data.get("result").get("outputs").get("nested").get("port").asText());
      assertTrue(data.get("summary").asText().length() <= 2000);
      assertFalse(data.get("result").get("summary").asText().contains("Task completed"));
      assertEquals(
          "Aggregate: City1 is the site of the fault",
          await(received, "task.finished.v1").path("data").path("result").path("summary").asText());

      JsonNode sent =
          awaitMatching(
              received,
              event ->
                  "sent".equals(event.path("data").path("interaction").path("phase").asText()));
      assertTrue(sent.path("data").path("input_summary").asText().contains("Inspect City1 line"));
      assertFalse(sent.toString().contains("synthetic-secret"));
      assertFalse(sent.toString().contains("generic wrapper"));
      assertFalse(sent.path("data").has("content"));
      JsonNode progressed =
          awaitMatching(
              received,
              event ->
                  event.get("type").asText().endsWith("task.progressed.v1")
                      && "agent_call"
                          .equals(event.get("data").get("interaction").get("kind").asText()));
      assertTrue(progressed.get("data").get("summary").asText().contains("optical risk"));
    } finally {
      http.stop(0);
    }
  }

  @Test
  void statusOnlyDefaultKeepsContentOutOfTheRecord(@TempDir Path spool) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool)) {
      ExecutionReporter.Run run = beginRun(reporter);
      assertNotNull(run);
      run.observe("task_request", identity("diagnose", 0, "task-1"));
      run.observe(
          "agent_request",
          with(
              identity("diagnose", 0, "task-1"),
              "content",
              MessageContent.text("Inspect City1 line")));
      run.observe(
          "task_response",
          with(
              identity("diagnose", 0, "task-1"),
              "success",
              true,
              "response",
              "Diagnosis: the fault is on the City1 side.",
              "outputs",
              List.of(Map.of("conclusion", "fault on City1"))));
      run.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));

      JsonNode finished = await(received, "task.finished.v1");
      JsonNode data = finished.get("data");
      assertEquals("succeeded", data.get("outcome").asText());
      assertFalse(data.has("result"));
      assertFalse(data.has("summary"));
      assertFalse(await(received, "task.progressed.v1").path("data").has("input_summary"));
    } finally {
      http.stop(0);
    }
  }

  @Test
  void correlatesParallelAndSequentialAnswersFromSameAgent(@TempDir Path spool) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool,
            ContentCapture.all())) {
      String firstRunId = UUID.randomUUID().toString();
      String secondRunId = UUID.randomUUID().toString();
      ExecutionReporter.Run first = beginRun(reporter, firstRunId);
      ExecutionReporter.Run second = beginRun(reporter, secondRunId);
      first.observe("task_request", identity("diagnose", 0, "task-1"));
      first.observe("task_request", identity("diagnose", 1, "task-2"));
      second.observe("task_request", identity("diagnose", 0, "task-1"));
      first.observe(
          "agent_artifact_update", with(identity("diagnose", 0, "task-1"), "text", "City1 Los"));
      second.observe(
          "agent_status_update",
          with(identity("diagnose", 0, "task-1"), "text", "Other execution"));
      first.observe(
          "agent_status_update",
          with(identity("diagnose", 1, "task-2"), "text", "City2 congestion"));
      first.observe("agent_status_update", Map.of("agent", "City1 Agent", "text", "uncorrelated"));
      first.observe("task_response", with(identity("diagnose", 1, "task-2"), "success", true));
      first.observe("task_response", with(identity("diagnose", 0, "task-1"), "success", true));
      first.observe("task_request", identity("merge", 0, "task-3"));
      first.observe("task_response", with(identity("merge", 0, "task-3"), "success", true));
      second.observe("task_response", with(identity("diagnose", 0, "task-1"), "success", true));
      second.finish("succeeded");
      first.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));

      assertEquals(
          "City1 Los",
          taskEvent(received, firstRunId, "task-1", "task.progressed.v1").path("summary").asText());
      assertEquals(
          "City2 congestion",
          taskEvent(received, firstRunId, "task-2", "task.progressed.v1").path("summary").asText());
      assertEquals(
          "City1 Los",
          taskEvent(received, firstRunId, "task-1", "task.finished.v1").path("summary").asText());
      assertEquals(
          "City2 congestion",
          taskEvent(received, firstRunId, "task-2", "task.finished.v1").path("summary").asText());
      assertFalse(taskEvent(received, firstRunId, "task-3", "task.finished.v1").has("summary"));
      assertEquals(
          "Other execution",
          taskEvent(received, secondRunId, "task-1", "task.finished.v1").path("summary").asText());
      assertFalse(received.stream().anyMatch(event -> event.toString().contains("uncorrelated")));
    } finally {
      http.stop(0);
    }
  }

  @Test
  void redactsBeforeSpoolingExplicitlyAuthorizedSummaryAndOutputs(@TempDir Path spool)
      throws Exception {
    var endpoint = URI.create("http://127.0.0.1:1/events");
    try (var reporter =
        new ExecutionReporter(
            endpoint,
            "test-key",
            "urn:openan:host:content-test",
            spool,
            new ContentCapture(1000, Set.of("business", "token")))) {
      ExecutionReporter.Run run = beginRun(reporter);
      run.observe("task_request", identity("diagnose", 0, "task-1"));
      run.observe(
          "agent_response",
          with(identity("diagnose", 0, "task-1"), "response", "Fault on City2 Bearer alpha-123"));
      run.observe(
          "task_response",
          with(
              identity("diagnose", 0, "task-1"),
              "success",
              false,
              "error",
              "error-only-secret",
              "task",
              "prompt-only-secret",
              "outputs",
              List.of(
                  Map.of(
                      "token",
                      "top-level-secret",
                      "unlisted",
                      "unlisted-secret",
                      "business",
                      List.of(
                          Map.of(
                              "password",
                              "nested-secret",
                              "note",
                              "apiKey=formatted-secret",
                              "site",
                              "City2"))))));
      run.finish("failed");
    }
    try (var files = Files.walk(spool)) {
      var content = new StringBuilder();
      for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
        content.append(Files.readString(path));
      }
      assertTrue(content.toString().contains("Fault on City2 Bearer ***"));
      assertTrue(content.toString().contains("apiKey=***"));
      assertTrue(content.toString().contains("City2"));
      for (String value :
          List.of(
              "alpha-123",
              "error-only-secret",
              "prompt-only-secret",
              "top-level-secret",
              "unlisted-secret",
              "nested-secret",
              "formatted-secret")) {
        assertFalse(content.toString().contains(value), value);
      }
    }
  }

  private static JsonNode taskEvent(
      LinkedBlockingQueue<JsonNode> events, String executionId, String taskId, String eventName)
      throws InterruptedException {
    return awaitMatching(
            events,
            event ->
                event.path("subject").asText().equals(executionId)
                    && event.path("type").asText().endsWith(eventName)
                    && event.path("data").path("task_id").asText().equals(taskId))
        .path("data");
  }

  @Test
  void contentIsTruncatedToTheFieldBudget() throws Exception {
    String longText = "故".repeat(5000);
    String content = captureSummary(new ContentCapture(120, null), longText);
    assertNotNull(content);
    assertTrue(content.length() <= 120, "content budget must bound the payload");
    assertTrue(content.endsWith("\u2026"));
  }

  @Test
  void oversizedEventStillFitsOneIngestionBatch(@TempDir Path spool) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool,
            ContentCapture.all())) {
      ExecutionReporter.Run run = beginRun(reporter);
      assertNotNull(run);
      run.observe("task_request", identity("diagnose", 0, "task-1"));
      var payload = new LinkedHashMap<String, Object>(identity("diagnose", 0, "task-1"));
      payload.put("success", true);
      payload.put("response", "x".repeat(20000));
      var outputs = new LinkedHashMap<String, Object>();
      outputs.put("bulk_one", "y".repeat(25000));
      outputs.put("bulk_two", "z".repeat(25000));
      outputs.put("conclusion", "fault on City1");
      payload.put("outputs", List.of(outputs));
      run.observe("task_response", payload);
      run.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));

      JsonNode finished = await(received, "task.finished.v1");
      JsonNode data = finished.get("data");
      assertTrue(
          JSON.writeValueAsBytes(finished).length <= 64 * 1024,
          "one event must stay inside the center's 64 KiB ingestion limit");
      assertEquals("task-1", data.get("task_id").asText(), "identity must never be trimmed");
      assertEquals("s0", data.get("step_ref").asText());
      assertEquals("s0/t0", data.get("task_ref").asText());
      assertEquals(
          "fault on City1",
          data.get("result").get("outputs").get("conclusion").asText(),
          "the size guard drops bulk content before a small business result");
    } finally {
      http.stop(0);
    }
  }

  @Test
  void outputExtractionRunsOnTheWriterNotTheExecution(@TempDir Path spool) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool,
            ContentCapture.all())) {
      ExecutionReporter.Run run = beginRun(reporter);
      assertNotNull(run);
      var touchedBy = ConcurrentHashMap.<String>newKeySet();
      var outputs =
          new AbstractList<Object>() {
            private final List<Object> delegate = List.of(Map.of("conclusion", "fault on City1"));

            @Override
            public Iterator<Object> iterator() {
              touchedBy.add(Thread.currentThread().getName());
              return delegate.iterator();
            }

            @Override
            public Object get(int index) {
              touchedBy.add(Thread.currentThread().getName());
              return delegate.get(index);
            }

            @Override
            public int size() {
              return delegate.size();
            }
          };
      run.observe("task_request", identity("diagnose", 0, "task-1"));
      var payload = new LinkedHashMap<String, Object>(identity("diagnose", 0, "task-1"));
      payload.put("success", true);
      payload.put("outputs", outputs);
      run.observe("task_response", payload);
      run.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));

      JsonNode data = await(received, "task.finished.v1").get("data");
      assertEquals("fault on City1", data.get("result").get("outputs").get("conclusion").asText());
      assertEquals(
          Set.of("workflow-observation-writer"),
          touchedBy,
          "walking outputs is writer work; the execution thread must not pay for it");
    } finally {
      http.stop(0);
    }
  }

  @Test
  void aCancelledTaskIsReportedAsCancelled(@TempDir Path spool) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    String executionId = UUID.randomUUID().toString();
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool,
            new ContentCapture(0, null))) {
      ExecutionReporter.Run run = beginRun(reporter, executionId);
      assertNotNull(run);
      run.observe("task_request", identity("diagnose", 0, "task-1"));
      // What the engine reports for a task that was cancelled while it was working.
      run.observe(
          "task_response",
          with(
              identity("diagnose", 0, "task-1"),
              "success",
              false,
              "status",
              "cancelled",
              "outputs",
              List.of()));
      run.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));

      assertEquals(
          "cancelled",
          taskEvent(received, executionId, "task-1", "task.finished.v1").path("outcome").asText(),
          "a cancelled task is not passed off as a failed one");
    } finally {
      http.stop(0);
    }
  }

  private String captureSummary(ContentCapture capture, String text) throws Exception {
    var received = new LinkedBlockingQueue<JsonNode>();
    HttpServer http = stubCenter(received);
    http.start();
    Path spool = Files.createTempDirectory("content-budget");
    try (var reporter =
        new ExecutionReporter(
            URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:content-test",
            spool,
            capture)) {
      ExecutionReporter.Run run = beginRun(reporter);
      assertNotNull(run);
      run.observe("task_request", identity("diagnose", 0, "task-1"));
      run.observe(
          "task_response",
          with(identity("diagnose", 0, "task-1"), "success", true, "response", text));
      run.finish("succeeded");
      assertTrue(reporter.awaitDelivery(Duration.ofSeconds(10)));
      return await(received, "task.finished.v1").get("data").get("result").get("summary").asText();
    } finally {
      http.stop(0);
    }
  }

  private static ExecutionReporter.Run beginRun(ExecutionReporter reporter) {
    return beginRun(reporter, UUID.randomUUID().toString());
  }

  private static ExecutionReporter.Run beginRun(ExecutionReporter reporter, String executionId) {
    WorkflowStep step =
        WorkflowStep.builder()
            .name("diagnose")
            .layer(0)
            .subtasks(
                List.of(
                    Task.builder().agent("A").skill("s").description("do A").build(),
                    Task.builder().agent("A").skill("s").description("also A").build()))
            .next(List.of(JumpCondition.builder().step("merge").condition("").build()))
            .build();
    WorkflowStep merge =
        WorkflowStep.builder()
            .name("merge")
            .layer(1)
            .subtasks(List.of(Task.builder().agent("B").skill("s").description("do B").build()))
            .build();
    Workflow workflow = Workflow.builder().name("content").steps(List.of(step, merge)).build();
    workflow.setRevision("a".repeat(64));
    return reporter.begin(workflow, executionId, null, null, null);
  }

  private static Map<String, Object> identity(String step, int index, String taskId) {
    return Map.of("step", step, "subtask_index", index, "taskId", taskId, "agent", "City1 Agent");
  }

  private static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
    var data = new LinkedHashMap<String, Object>(base);
    for (int i = 0; i < pairs.length; i += 2) {
      data.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return data;
  }

  private static HttpServer stubCenter(LinkedBlockingQueue<JsonNode> received) throws Exception {
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            JsonNode batch = JSON.readTree(exchange.getRequestBody().readAllBytes());
            batch.forEach(received::add);
            byte[] ack =
                JSON.writeValueAsBytes(
                    Map.of(
                        "execution_id", batch.get(0).get("subject").asText(),
                        "accepted", batch.size(),
                        "duplicates", 0,
                        "contiguous_through", batch.get(batch.size() - 1).path("seq").asText()));
            exchange.sendResponseHeaders(200, ack.length);
            exchange.getResponseBody().write(ack);
          }
        });
    return http;
  }

  private static JsonNode await(LinkedBlockingQueue<JsonNode> received, String suffix)
      throws InterruptedException {
    return awaitMatching(received, event -> event.get("type").asText().endsWith(suffix));
  }

  private static JsonNode awaitMatching(
      LinkedBlockingQueue<JsonNode> received, Predicate<JsonNode> predicate)
      throws InterruptedException {
    List<JsonNode> skipped = new ArrayList<>();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        JsonNode event = received.poll(500, TimeUnit.MILLISECONDS);
        if (event == null) continue;
        if (predicate.test(event)) return event;
        skipped.add(event);
      }
    } finally {
      skipped.forEach(received::add);
    }
    throw new AssertionError("Expected observation event was not delivered");
  }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.registry.CenterExecutionReporting;
import dev.openan.workflow.engine.registry.HttpAuthProvider;
import dev.openan.workflow.engine.registry.LoadPsop;
import dev.openan.workflow.engine.registry.TlsPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What automatic reporting discloses, once a PSOP has authorized it.
 *
 * <p>The center's contract requires the application to name the structured output keys it is
 * willing to share; an automatic default must not guess. This drives a run through the reporter the
 * automatic path builds and reads the events it queued, so the guarantee is pinned at the boundary
 * a host actually gets, not at a policy object a test happened to construct.
 */
class AutomaticReportingContentTest {
  private static final String SOURCE = "urn:openan:host:identity-" + "d".repeat(64);
  private static final String CENTER = "http://127.0.0.1:15001";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void automaticReportingKeepsTheReadableAnswerAndNoStructuredOutputs(@TempDir Path spool)
      throws Exception {
    Workflow workflow = workflow();
    var reporting =
        new CenterExecutionReporting(CENTER, HttpAuthProvider.none(), TlsPolicy.insecure(), spool);
    try {
      ExecutionReporter reporter = reporting.forPsop(new LoadPsop.LoadedPsop(workflow, SOURCE));
      assertNotNull(reporter, "a PSOP naming a reporting source is reported");

      String executionId = UUID.randomUUID().toString();
      ExecutionReporter.Run run = reporter.begin(workflow, executionId, null, null, null);
      run.observe(
          "task_request", Map.of("step", "diagnose", "subtask_index", 0, "taskId", "task-1"));
      run.observe(
          "task_response",
          Map.of(
              "step", "diagnose",
              "taskId", "task-1",
              "success", true,
              "response", "fault on City1",
              "outputs",
                  List.of(
                      Map.of("privateKey", "secret-material", "customerPhone", "13800000000"))));
      run.finish("succeeded");

      JsonNode finished = awaitEvent(spool, executionId, "task.finished");
      JsonNode data = finished.path("data");
      assertTrue(
          data.path("result").path("summary").asText().contains("fault on City1"),
          "the readable answer is what an operator needs: " + finished);
      assertFalse(
          data.path("result").has("outputs"),
          "structured outputs need the application's explicit approval: " + finished);
      assertTrue(data.has("outcome"), "the task's outcome always survives: " + finished);
      assertTrue(data.has("task_id"), "the task identity always survives: " + finished);
    } finally {
      reporting.close();
    }
  }

  @Test
  void aHostThatNamesItsKeysGetsThemReported(@TempDir Path spool) throws Exception {
    // The opt-in path the contract requires: the application decides which outputs may be shared.
    Workflow workflow = workflow();
    var reporting =
        new CenterExecutionReporting(
            CENTER,
            HttpAuthProvider.none(),
            TlsPolicy.insecure(),
            spool,
            new ContentCapture(12000, Set.of("conclusion")));
    try {
      ExecutionReporter reporter = reporting.forPsop(new LoadPsop.LoadedPsop(workflow, SOURCE));
      assertNotNull(reporter);

      String executionId = UUID.randomUUID().toString();
      ExecutionReporter.Run run = reporter.begin(workflow, executionId, null, null, null);
      run.observe("task_request", Map.of("step", "diagnose", "subtask_index", 0, "taskId", "t1"));
      run.observe(
          "task_response",
          Map.of(
              "step",
              "diagnose",
              "taskId",
              "t1",
              "success",
              true,
              "outputs",
              List.of(Map.of("conclusion", "fault on City1", "secretNote", "not approved"))));
      run.finish("succeeded");

      JsonNode outputs =
          awaitEvent(spool, executionId, "task.finished")
              .path("data")
              .path("result")
              .path("outputs");
      assertTrue(outputs.has("conclusion"), "a named key is reported: " + outputs);
      assertFalse(outputs.has("secretNote"), "an unnamed key stays out: " + outputs);
    } finally {
      reporting.close();
    }
  }

  /**
   * Reads a queued event back from the spool. Nothing is sent, because this test's center address
   * has no server; the writer has still put each event on disk, which is what a host's history
   * ultimately reflects.
   */
  private static JsonNode awaitEvent(Path spool, String executionId, String kind) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      List<JsonNode> events = queuedEvents(spool);
      for (JsonNode event : events) {
        if (event.path("subject").asText().equals(executionId)
            && event.path("type").asText().endsWith("." + kind + ".v1")) {
          return event;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no " + kind + " event was queued for " + executionId);
  }

  private static List<JsonNode> queuedEvents(Path spool) throws Exception {
    List<JsonNode> events = new ArrayList<>();
    try (Stream<Path> files = Files.walk(spool)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
        events.add(JSON.readTree(Files.readAllBytes(file)));
      }
    }
    return events;
  }

  private static Workflow workflow() {
    Workflow workflow =
        Workflow.builder()
            .name("reported")
            .steps(List.of(WorkflowStep.builder().name("diagnose").build()))
            .build();
    workflow.setRevision("e".repeat(64));
    return workflow;
  }
}

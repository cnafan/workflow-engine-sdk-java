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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Reporting is a cross-cutting concern: whatever it does, the run's result and its event stream
 * must be exactly what a host would see without it.
 *
 * <p>Each case makes one reporting call fail and asserts the execution still completes and still
 * emits the same events, which is the guarantee a host relies on when it turns reporting on.
 */
class ObservationIsolationTest {

  @Test
  @org.junit.jupiter.api.Timeout(60)
  void aReportingFailureNeverChangesTheRunOrItsEvents() {
    List<String> baseline = run(null);
    assertTrue(baseline.contains("complete"), "the baseline run completed: " + baseline);

    for (String failing : List.of("begin", "observe", "finish")) {
      FailingSink sink = new FailingSink(failing);
      List<String> events = run(sink);
      assertEquals(
          baseline,
          events,
          "reporting failed in '" + failing + "' and changed the run's event stream");
      assertTrue(
          sink.reached(failing),
          "the failing '" + failing + "' call was made, so the guard was exercised");
    }
  }

  /** Runs one workflow, optionally with a reporter whose named call fails. */
  private static List<String> run(ObservationSink sink) {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    EventCallback callback =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(type);
          }
        };
    ExecutePsop.Builder builder =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .controlPoint(autoControlPoint())
            .engineClient(new StubWorkflowEngineClient("A"))
            .eventCallback(callback);
    if (sink != null) builder.observationSink(sink);

    ExecutionResult result = builder.execute().join();

    assertTrue(result.isSuccess(), "the run completed despite the reporting failure");
    return events;
  }

  private static Workflow linearWorkflow() {
    return Workflow.builder()
        .name("isolation")
        .steps(
            List.of(
                WorkflowStep.builder()
                    .name("s1")
                    .layer(0)
                    .subtasks(List.of(Task.builder().agent("A").description("do A").build()))
                    .next(List.of(JumpCondition.builder().step("endNode").condition("").build()))
                    .build()))
        .build();
  }

  private static ControlPoint autoControlPoint() {
    return new ControlPoint() {
      @Override
      public CompletableFuture<MessageContent> onTask(TaskRequest request) {
        return CompletableFuture.completedFuture(MessageContent.text(request.getInstruction()));
      }

      @Override
      public CompletableFuture<RouteDecision> onRoute(RouteRequest routeRequest) {
        return CompletableFuture.completedFuture(RouteDecision.allow("test"));
      }
    };
  }

  /** A reporter whose named call fails, standing in for any defect inside reporting. */
  private static final class FailingSink implements ObservationSink {
    private final Set<String> failing;
    private final Set<String> reached = ConcurrentHashMap.newKeySet();

    private FailingSink(String... failing) {
      this.failing = Set.of(failing);
    }

    /** Whether the named reporting call was actually made. */
    private boolean reached(String name) {
      return reached.contains(name);
    }

    @Override
    public Observation begin(
        Workflow workflow,
        String executionId,
        String businessRef,
        String traceparent,
        String tracestate) {
      reached.add("begin");
      if (failing.contains("begin")) throw new IllegalStateException("reporting begin failed");
      return new Observation() {
        @Override
        public void observe(String eventType, Map<String, Object> data) {
          reached.add("observe");
          if (failing.contains("observe")) {
            throw new IllegalStateException("reporting observe failed");
          }
        }

        @Override
        public void finish(String outcome) {
          reached.add("finish");
          if (failing.contains("finish"))
            throw new IllegalStateException("reporting finish failed");
        }
      };
    }
  }
}

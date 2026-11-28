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

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Every task that starts is reported as ended exactly once, with success, failure or cancellation.
 *
 * <p>Before this held only for tasks that produced a result: one that was cancelled while it was
 * working, as {@code ANY_SUCCESS} does to the tasks that did not win, never reported an end, and a
 * consumer was left with a task that never finished. Mirrors {@code test_task_ends.py} in the
 * Python SDK.
 */
class TaskEndTest {

  @Test
  void aTaskCancelledByAnySuccessStillReportsItsEnd() {
    Events events = new Events();
    ExecutionResult result =
        executor(events, StepType.ANY_SUCCESS, List.of(task("fast"), task("slow"))).run().join();

    assertTrue(result.isSuccess());
    events.assertEnd("fast", "success");
    events.assertEnd("slow", "cancelled");
    assertEquals(1, result.getHistory().size(), "only the task that produced a result is recorded");
  }

  @Test
  void aTaskCancelledWithTheRunStillReportsItsEnd() {
    Events events = new Events();
    var run = executor(events, StepType.ALL_SUCCESS, List.of(task("slow"))).run();

    assertTrue(events.started("slow"), "the task started");
    assertTrue(run.cancel(true));
    events.assertEnd("slow", "cancelled");
  }

  @Test
  void anySuccessWaitsForATaskAlreadyPublishingItsResult() throws Exception {
    var fast = new CompletableFuture<SendMessageResult>();
    var slow = new CompletableFuture<SendMessageResult>();
    var publishing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    Events events =
        new Events() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            if (EventType.TASK_STATUS_CHANGED.equals(type)
                && "slow".equals(data.get("agent"))
                && "success".equals(data.get("status"))) {
              publishing.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            }
            super.onEvent(type, data);
          }
        };
    var client =
        new StubWorkflowEngineClient("fast", "slow") {
          @Override
          public CompletableFuture<SendMessageResult> sendTask(
              String name, MessageContent content) {
            return "fast".equals(name) ? fast : slow;
          }
        };
    var control =
        ControlPoint.builder()
            .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("input")))
            .build();
    var run =
        new WorkflowExecutor(
                workflow(StepType.ANY_SUCCESS, List.of(task("fast"), task("slow"))),
                control,
                client,
                events,
                "",
                "zh")
            .run();
    var success =
        SendMessageResult.builder().taskState("TASK_STATE_COMPLETED").text("done").build();
    var slowCompletion = CompletableFuture.runAsync(() -> slow.complete(success));
    try {
      assertTrue(publishing.await(5, TimeUnit.SECONDS));
      fast.complete(success);
      assertFalse(run.isDone());
    } finally {
      release.countDown();
    }
    slowCompletion.get(5, TimeUnit.SECONDS);
    assertTrue(run.get(5, TimeUnit.SECONDS).isSuccess());
    events.assertEnd("fast", "success");
    events.assertEnd("slow", "success");
  }

  private static WorkflowExecutor executor(Events events, StepType stepType, List<Task> tasks) {
    return new WorkflowExecutor(
        workflow(stepType, tasks),
        hangingFor("slow"),
        new StubWorkflowEngineClient("fast", "slow"),
        events,
        "",
        "zh");
  }

  private static Workflow workflow(StepType stepType, List<Task> tasks) {
    return Workflow.builder()
        .name("ends")
        .steps(
            List.of(
                WorkflowStep.builder()
                    .name("only")
                    .stepType(stepType)
                    .layer(0)
                    .subtasks(tasks)
                    .build()))
        .build();
  }

  private static Task task(String agent) {
    return Task.builder().agent(agent).description("do " + agent).build();
  }

  /** Answers at once, except for one agent that is still working when it is cancelled. */
  private static ControlPoint hangingFor(String agent) {
    return new ControlPoint() {
      @Override
      public CompletableFuture<MessageContent> onTask(TaskRequest request) {
        return request.getAgentName().equals(agent)
            ? new CompletableFuture<MessageContent>()
            : CompletableFuture.completedFuture(MessageContent.text("done"));
      }

      @Override
      public CompletableFuture<RouteDecision> onRoute(RouteRequest request) {
        return CompletableFuture.completedFuture(RouteDecision.allow("test"));
      }
    };
  }

  /** What the engine reported, as a host receives it. */
  private static class Events extends EventCallback {
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final List<String> responses = Collections.synchronizedList(new ArrayList<>());
    private final List<String> changes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(String type, Map<String, Object> data) {
      String agent = String.valueOf(data.get("agent"));
      switch (type) {
        case EventType.TASK_REQUEST -> requests.add(agent);
        case EventType.TASK_STATUS_CHANGED -> changes.add(agent + ":" + data.get("status"));
        case EventType.TASK_RESPONSE -> responses.add(agent + ":" + data.get("status"));
        default -> {}
      }
    }

    /** Whether the agent's task started. */
    boolean started(String agent) {
      return requests.contains(agent);
    }

    /**
     * The agent's task ended exactly once, in the state given, after that state was announced.
     * Cancellation is reported from another thread, so the end is awaited before it is counted.
     */
    void assertEnd(String agent, String status) {
      String expected = agent + ":" + status;
      long deadline = System.nanoTime() + WAIT_NANOS;
      while (!responses.contains(expected) && System.nanoTime() < deadline) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      assertEquals(List.of(expected), only(responses, agent), "the task's ends");
      assertEquals(List.of(expected), only(changes, agent), "the task's status announcements");
    }

    private static List<String> only(List<String> entries, String agent) {
      return entries.stream().filter(entry -> entry.startsWith(agent + ":")).toList();
    }
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.client.RemoteA2AErrorException;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteA2AErrorFailureTest {
  private static String error(int status) {
    String canonical = status == 429 ? "RESOURCE_EXHAUSTED" : "INVALID_ARGUMENT";
    String reason = status == 429 ? "ACTIVE_TASK_LIMIT_EXCEEDED" : "INVALID_PARAMS";
    return "{\"error\":{\"code\":"
        + status
        + ",\"status\":\""
        + canonical
        + "\",\"message\":\"Remote agent rejected the request\",\"details\":[{"
        + "\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\""
        + reason
        + "\",\"domain\":\"example.invalid\"}]}}";
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429})
  void waitsForTheOtherAgentAndSkipsMergeWhileReportingCorrelatedFailure(int status)
      throws Exception {
    var started = new java.util.concurrent.CountDownLatch(2);
    var reported = new java.util.concurrent.CountDownLatch(1);
    var agent1 = new CompletableFuture<SendMessageResult>();
    var agent2 = new CompletableFuture<SendMessageResult>();
    var observed = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
    var client =
        new StubWorkflowEngineClient() {
          @Override
          public CompletableFuture<SendMessageResult> sendTask(
              String agentName,
              MessageContent content,
              dev.openan.workflow.engine.control.NegotiationStrategy negotiationStrategy) {
            started.countDown();
            return agentName.equals("agent1") ? agent1 : agent2;
          }
        };
    var merges = new AtomicInteger();
    var callbacks =
        ControlPoint.builder()
            .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("run task")))
            .onSelfTask(
                request -> {
                  merges.incrementAndGet();
                  return CompletableFuture.completedFuture(
                      TaskResult.success(List.of("must not merge")));
                })
            .build();
    var workflow =
        Workflow.builder()
            .name("parallel-failure")
            .steps(
                List.of(
                    remote("agent1"),
                    remote("agent2"),
                    WorkflowStep.builder()
                        .name("merge")
                        .stepType(StepType.SELF_LOOP)
                        .subtasks(
                            List.of(Task.builder().agent("host").description("aggregate").build()))
                        .next(List.of())
                        .build()))
            .build();
    var events =
        new dev.openan.workflow.engine.control.EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            if (type.equals(dev.openan.workflow.engine.control.EventType.TASK_RESPONSE)) {
              observed.add(data);
              if (Boolean.FALSE.equals(data.get("success"))) reported.countDown();
            }
            if (type.equals(dev.openan.workflow.engine.control.EventType.ERROR)) {
              assertNotNull(data.get("error"));
            }
          }
        };
    var execution =
        new WorkflowExecutor(workflow, callbacks, client, events, "request", "en").run();
    try {
      assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
      agent1.completeExceptionally(RemoteA2AErrorException.fromPayload(error(status)));
      assertTrue(reported.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertFalse(execution.isDone(), "The other in-flight task must be collected");
      assertFalse(agent2.isCancelled());
      agent2.complete(
          SendMessageResult.builder()
              .taskState("TASK_STATE_COMPLETED")
              .receivedMessages(
                  List.of(
                      new ReceivedMessage(
                          MessageContent.text("agent2 result"), Map.of(), List.of())))
              .build());
      var result = execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertFalse(result.isSuccess());
      assertEquals(2, result.getHistory().size());
      assertEquals(0, merges.get());
      assertFalse(result.getStepOutputs().containsKey("merge"));
      assertEquals(2, observed.size());
      for (var event : observed) {
        assertNotNull(event.get("executionId"));
        assertNotNull(event.get("taskId"));
      }
      assertEquals(observed.get(0).get("executionId"), observed.get(1).get("executionId"));
      assertNotEquals(observed.get(0).get("taskId"), observed.get(1).get("taskId"));
      var success =
          result.getHistory().stream()
              .filter(h -> h.get("agent").equals("agent2"))
              .findFirst()
              .orElseThrow();
      assertEquals("success", success.get("status"));
      assertEquals(List.of("agent2 result"), success.get("outputs"));
    } finally {
      agent1.cancel(true);
      agent2.cancel(true);
      execution.cancel(true);
    }
  }

  private static WorkflowStep remote(String name) {
    return WorkflowStep.builder()
        .name(name)
        .stepType(StepType.ALL_SUCCESS)
        .subtasks(List.of(Task.builder().agent(name).description("execute").build()))
        .next(List.of(new JumpCondition("merge", "")))
        .build();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429})
  void protocolFailureReachesWorkflowHistoryWithoutNegotiationOrSuccessfulOutputs(int status) {
    var remoteError = RemoteA2AErrorException.fromPayload(error(status));
    var calls = new AtomicInteger();
    var negotiations = new AtomicInteger();
    var client =
        new StubWorkflowEngineClient("remote-agent") {
          @Override
          public CompletableFuture<SendMessageResult> sendTask(
              String agentName,
              MessageContent content,
              dev.openan.workflow.engine.control.NegotiationStrategy negotiationStrategy) {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new CompletionException(remoteError));
          }
        };
    var step =
        WorkflowStep.builder()
            .name("remote")
            .stepType(StepType.ALL_SUCCESS)
            .subtasks(List.of(Task.builder().agent("remote-agent").description("execute").build()))
            .next(List.of())
            .build();
    var workflow = Workflow.builder().name("remote-failure").steps(List.of(step)).build();
    var callbacks =
        ControlPoint.builder()
            .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("execute")))
            .onNegotiation(
                request -> {
                  negotiations.incrementAndGet();
                  return CompletableFuture.failedFuture(new AssertionError("must not negotiate"));
                })
            .build();
    var result =
        new WorkflowExecutor(workflow, callbacks, client, null, "request", "en").run().join();
    assertFalse(result.isSuccess());
    assertEquals(1, calls.get());
    assertEquals(0, negotiations.get());
    var history = result.getHistory().get(0);
    assertEquals("failed", history.get("status"));
    assertEquals(
        status == 429 ? "a2a.active_task_limit_exceeded" : "a2a.invalid_params",
        history.get("errorCode"));
    assertEquals(List.of(), history.get("outputs"));
    assertTrue(history.get("error").toString().contains("rejected"));
    assertEquals(status, ((Map<?, ?>) history.get("errorDetails")).get("httpStatus"));
    assertNotNull(((Map<?, ?>) history.get("errorDetails")).get("details"));
  }

  // TC-ENG | knownSecretsAreRedactedAndUnrecognizedProviderErrorsRemainPrivate
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void knownSecretsAreRedactedAndUnrecognizedProviderErrorsRemainPrivate() {
    var remoteError =
        RemoteA2AErrorException.fromPayload(
            error(400)
                .replace(
                    "Remote agent rejected the request", "bad request password=private-value"));
    var result = FailureMapping.from(remoteError);
    assertFalse(result.toString().contains("private-value"));
    assertFalse(remoteError.getMessage().contains("private-value"));
    assertEquals(
        "IllegalStateException",
        FailureMapping.from(new IllegalStateException("raw private provider body")).getError());
  }
}

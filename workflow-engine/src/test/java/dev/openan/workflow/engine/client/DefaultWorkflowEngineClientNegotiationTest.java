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

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.openan.a2at.sdk.core.model.*;
import org.a2aproject.sdk.client.*;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;

class DefaultWorkflowEngineClientNegotiationTest {
  static A2AJavaClientRuntime runtime(Function<MessageSendParams, List<ClientEvent>> send) {
    AtomicReference<MessageSendParams> lastRequest = new AtomicReference<>();
    return new A2AJavaClientRuntime() {
      public Iterable<ClientEvent> sendMessage(
          AgentCard card,
          MessageSendParams params,
          ClientCallContext context,
          Consumer<ClientEvent> sink,
          Consumer<String> logs) {
        lastRequest.set(params);
        List<ClientEvent> events = send.apply(params);
        if (sink != null) events.forEach(sink);
        return events;
      }

      public org.a2aproject.sdk.spec.Task cancelTask(
          AgentCard card, String taskId, ClientCallContext context) {
        MessageSendParams request = lastRequest.get();
        if (request == null) throw new IllegalStateException("No task has been sent");
        return response(request, TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
            .getTask();
      }

      public void close() {}
    };
  }

  static AgentCard card() throws Exception {
    return card(true);
  }

  static AgentCard cardWithoutNegotiation() throws Exception {
    return card(false);
  }

  private static AgentCard card(boolean supportsNegotiation) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
                    {"name":"test","description":"test","version":"1",
                     "capabilities":{"streaming":true,"extensions":[%s]},
                     "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
                     "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
                     "url":"http://localhost:1","tenant":""}]}
                    """
                .formatted(
                    supportsNegotiation
                        ? "{\"uri\":\""
                            + A2ATExtension.NEGOTIATION_T.uri()
                            + "\",\"required\":false}"
                        : ""),
            AgentCard.class);
  }

  static MessageContent negotiationTask(String text) {
    return MessageContent.text(text).withExtension(A2ATExtension.NEGOTIATION_T.uri());
  }

  static MessageContent negotiation(String id, int round, NegotiationPerformative performative) {
    return negotiation(id, round, round, performative);
  }

  static MessageContent negotiation(
      String id, int round, int maxRounds, NegotiationPerformative performative) {
    return A2atMessages.from(
        new MetadataContent(
            "Negotiation-T/information-negotiation/propose/v1",
            "opaque",
            A2ATExtension.NEGOTIATION_T.uri(),
            new NegotiationContext(id, round, maxRounds, performative)),
        List.of(new TextPart("not interpreted")));
  }

  /** A taskless A2A-T response: a bare agent message with no task lifecycle attached. */
  static List<ClientEvent> messageResponse(MessageSendParams request, MessageContent content) {
    Message message =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .contextId(request.message().contextId())
            .role(Message.Role.ROLE_AGENT)
            .parts(content.parts())
            .metadata(content.metadata())
            .build();
    return List.of(new MessageEvent(message));
  }

  static TaskEvent response(MessageSendParams request, TaskState state, MessageContent content) {
    Message message =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(content.parts())
            .metadata(content.metadata())
            .build();
    return new TaskEvent(
        org.a2aproject.sdk.spec.Task.builder()
            .id("remote-task")
            .contextId(request.message().contextId())
            .status(new org.a2aproject.sdk.spec.TaskStatus(state, message, null))
            .history(List.of())
            .metadata(Map.of())
            .build());
  }

  // TC-ENG | publicClientContractExposesOnlyTheTaskOrientedSendName
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void publicClientContractExposesOnlyTheTaskOrientedSendName() {
    Set<String> methodNames =
        Arrays.stream(WorkflowEngineClient.class.getMethods())
            .map(java.lang.reflect.Method::getName)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(methodNames.contains("dispatch"));
    assertFalse(methodNames.contains("sendMessage"));
  }

  // TC-ENG | standaloneTaskUsesFreshContextAndConfiguredInteractionLoop
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskUsesFreshContextAndConfiguredInteractionLoop() throws Exception {
    List<String> contexts = new CopyOnWriteArrayList<>();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                params -> {
                  contexts.add(params.message().contextId());
                  return List.of(
                      response(
                          params,
                          TaskState.TASK_STATE_COMPLETED,
                          MessageContent.text("task-done")));
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());

      assertEquals(
          "TASK_STATE_COMPLETED",
          client.sendTask("test", MessageContent.text("first")).join().getTaskState());
      assertEquals(
          "TASK_STATE_COMPLETED",
          client.sendTask("test", MessageContent.text("second")).join().getTaskState());
    }
    assertEquals(2, contexts.size());
    assertNotEquals(contexts.get(0), contexts.get(1));
  }

  // TC-ENG | standaloneTaskAcceptsPerCallNegotiationStrategy
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskAcceptsPerCallNegotiationStrategy() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    AtomicInteger negotiations = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                params ->
                    List.of(
                        response(
                            params,
                            sends.incrementAndGet() == 1
                                ? TaskState.TASK_STATE_INPUT_REQUIRED
                                : TaskState.TASK_STATE_COMPLETED,
                            sends.get() == 1
                                ? negotiation("standalone", 1, NegotiationPerformative.PROPOSE)
                                : MessageContent.text("done")))),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());

      var result =
          client
              .sendTask(
                  "test",
                  negotiationTask("start"),
                  request -> {
                    negotiations.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation("standalone", 1, NegotiationPerformative.ACCEPT)));
                  })
              .join();

      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals(1, negotiations.get());
      assertEquals(2, sends.get());
    }
  }

  // TC-ENG | standaloneTaskCancelsRemoteTaskBeforeFailingWhenNegotiationCannotContinue
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskCancelsRemoteTaskBeforeFailingWhenNegotiationCannotContinue()
      throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    AtomicReference<MessageSendParams> sent = new AtomicReference<>();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            sent.set(params);
            return List.of(
                response(
                    params,
                    TaskState.TASK_STATE_INPUT_REQUIRED,
                    negotiation("standalone", 1, NegotiationPerformative.PROPOSE)));
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            cancellations.incrementAndGet();
            assertEquals("remote-task", taskId);
            return response(
                    sent.get(), TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
                .getTask();
          }

          public void close() {}
        };
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());

      assertThrows(
          CompletionException.class,
          () -> client.sendTask("test", negotiationTask("start")).join());
      assertEquals(1, cancellations.get());
    }
  }

  // TC-ENG | lateRemoteIdentityIsCanceledAfterLocalTimeout
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void lateRemoteIdentityIsCanceledAfterLocalTimeout() throws Exception {
    CountDownLatch canceled = new CountDownLatch(1);
    AtomicReference<MessageSendParams> sent = new AtomicReference<>();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            sent.set(params);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1_200));
            return List.of(
                response(
                    params,
                    TaskState.TASK_STATE_INPUT_REQUIRED,
                    negotiation("late", 1, NegotiationPerformative.PROPOSE)));
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            canceled.countDown();
            return response(
                    sent.get(), TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
                .getTask();
          }

          public void close() {}
        };
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build();
    try (var transport = new A2ATransport(List.of(card()), runtime, config)) {
      var client = new DefaultWorkflowEngineClient(transport, config);

      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", negotiationTask("start")).join());
      assertInstanceOf(TimeoutException.class, error.getCause());
      assertTrue(canceled.await(3, TimeUnit.SECONDS));
    }
  }

  // TC-ENG | standaloneTaskCancelsUnsupportedAuthRequiredTask
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskCancelsUnsupportedAuthRequiredTask() throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    AtomicReference<MessageSendParams> sent = new AtomicReference<>();
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            sent.set(params);
            return List.of(
                response(
                    params,
                    TaskState.TASK_STATE_AUTH_REQUIRED,
                    MessageContent.text("authentication required")));
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            cancellations.incrementAndGet();
            return response(
                    sent.get(), TaskState.TASK_STATE_CANCELED, MessageContent.text("canceled"))
                .getTask();
          }

          public void close() {}
        };
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());

      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", negotiationTask("start")).join());
      assertTrue(error.getCause().getMessage().contains("TASK_STATE_AUTH_REQUIRED"));
      assertEquals(1, cancellations.get());
    }
  }

  // TC-ENG | standaloneTaskValidatesTaskTExtensionOnlyWhenActivated
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskValidatesTaskTExtensionOnlyWhenActivated() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                params -> {
                  sends.incrementAndGet();
                  return List.of(
                      response(
                          params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")));
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());

      client.sendTask("test", MessageContent.text("plain task")).join();
      MessageContent taskT =
          A2atMessages.from(
              new MetadataContent(
                  "Task-T/example/v1", "structured task", A2ATExtension.TASK_T.uri()),
              List.of(new TextPart("structured task")));
      CompletionException error =
          assertThrows(CompletionException.class, () -> client.sendTask("test", taskT).join());
      assertTrue(error.getCause().getMessage().contains("does not declare Task-T"));
      assertEquals(1, sends.get());
    }
  }

  // TC-ENG | standaloneTaskRejectsNegotiationActivationNotDeclaredByTarget
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskRejectsNegotiationActivationNotDeclaredByTarget() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(cardWithoutNegotiation()),
            runtime(
                params -> {
                  sends.incrementAndGet();
                  return List.of(
                      response(
                          params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")));
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      MessageContent content =
          new MessageContent(
              List.of(new TextPart("negotiation-capable task")),
              Map.of(),
              Set.of(A2ATExtension.NEGOTIATION_T.uri()));

      CompletionException error =
          assertThrows(CompletionException.class, () -> client.sendTask("test", content).join());

      assertTrue(error.getCause().getMessage().contains("does not declare Negotiation-T"));
      assertEquals(0, sends.get());
    }
  }

  // TC-ENG | standaloneTaskPreservesStandardA2AErrorWithoutCancelingAnUncreatedTask
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneTaskPreservesStandardA2AErrorWithoutCancelingAnUncreatedTask() throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    RemoteA2AErrorException remoteError =
        RemoteA2AErrorException.fromPayload(
            """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Task limit reached",
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
            "reason":"TASK_LIMIT_REACHED","domain":"a2a-protocol.org"}]}}
            """);
    A2AJavaClientRuntime runtime =
        new A2AJavaClientRuntime() {
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              MessageSendParams params,
              ClientCallContext context,
              Consumer<ClientEvent> sink,
              Consumer<String> logs) {
            throw remoteError;
          }

          public org.a2aproject.sdk.spec.Task cancelTask(
              AgentCard card, String taskId, ClientCallContext context) {
            cancellations.incrementAndGet();
            throw new AssertionError("No remote task was created");
          }

          public void close() {}
        };
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());

      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", negotiationTask("start")).join());
      RemoteA2AErrorException actual = RemoteA2AErrorException.findIn(error);
      assertNotNull(actual);
      assertEquals(429, actual.getHttpStatus());
      assertEquals("TASK_LIMIT_REACHED", actual.getReason());
      assertEquals(0, cancellations.get());
    }
  }

  // TC-ENG | answersFinalRoundAndKeepsOriginalRemoteAssociation
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void answersFinalRoundAndKeepsOriginalRemoteAssociation() throws Exception {
    List<MessageSendParams> sent = new CopyOnWriteArrayList<>();
    var runtime =
        runtime(
            params -> {
              sent.add(params);
              return List.of(
                  response(
                      params,
                      sent.size() == 1
                          ? TaskState.TASK_STATE_INPUT_REQUIRED
                          : TaskState.TASK_STATE_COMPLETED,
                      sent.size() == 1
                          ? negotiation("city1", 3, NegotiationPerformative.PROPOSE)
                          : MessageContent.text("done")));
            });
    AtomicReference<NegotiationRequest> seen = new AtomicReference<>();
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation("city1", 3, NegotiationPerformative.ACCEPT)));
                  })
              .build());
      assertEquals(
          "TASK_STATE_COMPLETED",
          client.sendTask("test", negotiationTask("start")).join().getTaskState());
    }
    assertEquals(2, sent.size());
    assertNull(sent.get(0).message().taskId());
    assertEquals("remote-task", sent.get(1).message().taskId());
    assertEquals(sent.get(0).message().contextId(), sent.get(1).message().contextId());
    assertTrue(seen.get().previousExchanges().isEmpty());
    assertFalse(seen.get().remainingWait().isZero());
    assertEquals(negotiationTask("start"), seen.get().originalSubmission());
  }

  // TC-ENG | terminalStateNeverRestartsOldNegotiation
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void terminalStateNeverRestartsOldNegotiation() throws Exception {
    AtomicInteger callbacks = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_COMPLETED,
                            negotiation("old", 1, NegotiationPerformative.PROPOSE)))),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  q -> {
                    callbacks.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                  })
              .build());
      client.sendTask("test", negotiationTask("start")).join();
    }
    assertEquals(0, callbacks.get());
  }

  // TC-ENG | rejectsWrongContextNewProposeAndMissingCallbackWithoutSendingAgain
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void rejectsWrongContextNewProposeAndMissingCallbackWithoutSendingAgain() throws Exception {
    for (NegotiationReply reply :
        List.of(
            new NegotiationReply.Send(negotiation("other-city", 1, NegotiationPerformative.ACCEPT)),
            new NegotiationReply.Send(negotiation("city1", 1, NegotiationPerformative.PROPOSE)),
            new NegotiationReply.Stop("manual.required", "Need human"))) {
      AtomicInteger sends = new AtomicInteger();
      try (var transport =
          new A2ATransport(
              List.of(card()),
              runtime(
                  p -> {
                    sends.incrementAndGet();
                    return List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            negotiation("city1", 1, NegotiationPerformative.PROPOSE)));
                  }),
              WorkflowEngineClientConfig.builder().build())) {
        var client =
            new DefaultWorkflowEngineClient(
                transport,
                WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
        client.setControlPoint(
            ControlPoint.builder()
                .onNegotiation(q -> CompletableFuture.completedFuture(reply))
                .build());
        assertThrows(
            CompletionException.class,
            () -> client.sendTask("test", negotiationTask("start")).join());
      }
      assertEquals(1, sends.get());
    }
  }

  // TC-ENG | plainInputRequiredIsExplicitlyUnsupported
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void plainInputRequiredIsExplicitlyUnsupported() throws Exception {
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            MessageContent.text("question")))),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      assertThrows(
          CompletionException.class,
          () -> client.sendTask("test", negotiationTask("start")).join());
    }
  }

  // TC-ENG | tasklessProposeEntersNegotiationAndFollowUpOmitsTaskId
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tasklessProposeEntersNegotiationAndFollowUpOmitsTaskId() throws Exception {
    List<MessageSendParams> sent = new CopyOnWriteArrayList<>();
    AtomicInteger negotiations = new AtomicInteger();
    var runtime =
        runtime(
            params -> {
              sent.add(params);
              return sent.size() == 1
                  ? messageResponse(
                      params, negotiation("pre-task", 1, 5, NegotiationPerformative.PROPOSE))
                  : messageResponse(params, MessageContent.text("诊断结果"));
            });
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    negotiations.incrementAndGet();
                    var context = A2atMessages.contextOf(request.received());
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation(
                                context.id(),
                                context.round(),
                                context.maxRounds(),
                                NegotiationPerformative.ACCEPT)));
                  })
              .build());
      SendMessageResult result = client.sendTask("test", negotiationTask("start")).join();
      assertEquals("", result.getTaskState());
      assertEquals("诊断结果", result.getText());
    }
    assertEquals(2, sent.size());
    assertNull(sent.get(0).message().taskId());
    assertNull(sent.get(1).message().taskId());
    assertEquals(1, negotiations.get());
    assertEquals(sent.get(0).message().contextId(), sent.get(1).message().contextId());
  }

  // TC-ENG | tasklessRoundLoopAdvancesRoundsAndCompletesWithTaskAnchoredResult
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tasklessRoundLoopAdvancesRoundsAndCompletesWithTaskAnchoredResult() throws Exception {
    List<MessageSendParams> sent = new CopyOnWriteArrayList<>();
    List<Integer> historySizes = new CopyOnWriteArrayList<>();
    var runtime =
        runtime(
            params -> {
              sent.add(params);
              return switch (sent.size()) {
                case 1 ->
                    messageResponse(
                        params, negotiation("pre", 1, 5, NegotiationPerformative.PROPOSE));
                case 2 ->
                    messageResponse(
                        params, negotiation("pre", 2, 5, NegotiationPerformative.PROPOSE));
                default ->
                    List.of(
                        response(
                            params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")));
              };
            });
    try (var transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    historySizes.add(request.previousExchanges().size());
                    var context = A2atMessages.contextOf(request.received());
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation(
                                context.id(),
                                context.round(),
                                context.maxRounds(),
                                NegotiationPerformative.ACCEPT)));
                  })
              .build());
      SendMessageResult result = client.sendTask("test", negotiationTask("start")).join();
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    }
    assertEquals(3, sent.size());
    assertNull(sent.get(1).message().taskId());
    assertNull(sent.get(2).message().taskId());
    assertEquals(List.of(0, 1), historySizes);
  }

  // TC-ENG | tasklessProposeWithoutHostActivationFailsLoudly
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tasklessProposeWithoutHostActivationFailsLoudly() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p -> {
                  sends.incrementAndGet();
                  return messageResponse(
                      p, negotiation("pre", 1, 5, NegotiationPerformative.PROPOSE));
                }),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", MessageContent.text("plain")).join());
      assertTrue(error.getCause().getMessage().contains("not activated by the host"));
      assertEquals(1, sends.get());
    }
  }

  // TC-ENG | tasklessProposeWithMissingNegotiationContextFailsLoudly
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tasklessProposeWithMissingNegotiationContextFailsLoudly() throws Exception {
    MessageContent malformed =
        new MessageContent(
            List.of(new TextPart("propose without context")),
            Map.of(A2ATExtension.NEGOTIATION_T.uri(), "unstructured"),
            Set.of(A2ATExtension.NEGOTIATION_T.uri()));
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(p -> messageResponse(p, malformed)),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", negotiationTask("start")).join());
      assertTrue(error.getCause().getMessage().contains("negotiationContext"));
    }
  }

  // TC-ENG | duplicateTasklessProposeFailsExplicitlyWithoutRepeatingTheCallback
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void duplicateTasklessProposeFailsExplicitlyWithoutRepeatingTheCallback() throws Exception {
    AtomicInteger negotiations = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p -> messageResponse(p, negotiation("pre", 1, 5, NegotiationPerformative.PROPOSE))),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    negotiations.incrementAndGet();
                    var context = A2atMessages.contextOf(request.received());
                    return CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation(
                                context.id(),
                                context.round(),
                                context.maxRounds(),
                                NegotiationPerformative.ACCEPT)));
                  })
              .build());
      CompletionException error =
          assertThrows(
              CompletionException.class,
              () -> client.sendTask("test", negotiationTask("start")).join());
      assertTrue(error.getCause().getMessage().contains("Duplicate taskless"));
      assertEquals(1, negotiations.get());
    }
  }

  // TC-ENG | tasklessMessageWithoutNegotiationMetadataRemainsNormalResponse
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tasklessMessageWithoutNegotiationMetadataRemainsNormalResponse() throws Exception {
    AtomicInteger negotiations = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(p -> messageResponse(p, MessageContent.text("plain final"))),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  q -> {
                    negotiations.incrementAndGet();
                    return CompletableFuture.failedFuture(new AssertionError());
                  })
              .build());
      SendMessageResult result = client.sendTask("test", negotiationTask("start")).join();
      assertEquals("", result.getTaskState());
      assertEquals("plain final", result.getText());
      assertEquals(0, negotiations.get());
    }
  }

  // TC-ENG | abortAcknowledgementIsNotDiagnosisSuccessAndPreservesRemoteState
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void abortAcknowledgementIsNotDiagnosisSuccessAndPreservesRemoteState() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(
                            p,
                            sends.incrementAndGet() == 1
                                ? TaskState.TASK_STATE_INPUT_REQUIRED
                                : TaskState.TASK_STATE_COMPLETED,
                            negotiation("city1", 1, NegotiationPerformative.PROPOSE)))),
            WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  q ->
                      CompletableFuture.completedFuture(
                          new NegotiationReply.Send(
                              negotiation("city1", 1, NegotiationPerformative.ABORT))))
              .build());
      var result = client.sendTask("test", negotiationTask("start")).join();
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals("negotiation.aborted", result.getFailureCode());
    }
    assertEquals(2, sends.get());
  }

  // TC-ENG | workingAckAndDuplicateProposeAreObservedWithoutRepeatedSubmissionOrCallback
  //
  // 输入: 重复/二次提交
  // 预期输出: 幂等行为：不产生重复副作用
  // 断言点: 调用次数 + 结果一致性
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workingAckAndDuplicateProposeAreObservedWithoutRepeatedSubmissionOrCallback()
      throws Exception {
    for (TaskState secondState :
        List.of(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_INPUT_REQUIRED)) {
      AtomicInteger sends = new AtomicInteger();
      AtomicInteger polls = new AtomicInteger();
      AtomicInteger callbacks = new AtomicInteger();
      AtomicReference<MessageSendParams> last = new AtomicReference<>();
      A2AJavaClientRuntime runtime =
          new A2AJavaClientRuntime() {
            public Iterable<ClientEvent> sendMessage(
                AgentCard card,
                MessageSendParams params,
                ClientCallContext context,
                Consumer<ClientEvent> sink,
                Consumer<String> logs) {
              last.set(params);
              return List.of(
                  response(
                      params,
                      sends.incrementAndGet() == 1
                          ? TaskState.TASK_STATE_INPUT_REQUIRED
                          : secondState,
                      negotiation("city1", 1, NegotiationPerformative.PROPOSE)));
            }

            public org.a2aproject.sdk.spec.Task getTask(
                AgentCard card, String taskId, ClientCallContext context) {
              polls.incrementAndGet();
              assertEquals("remote-task", taskId);
              assertNotNull(WireLog.context().get("attempt"));
              return response(
                      last.get(), TaskState.TASK_STATE_COMPLETED, MessageContent.text("diagnosis"))
                  .getTask();
            }

            public void close() {}
          };
      try (var transport =
          new A2ATransport(
              List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
        var client =
            new DefaultWorkflowEngineClient(
                transport,
                WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
        client.setControlPoint(
            ControlPoint.builder()
                .onNegotiation(
                    q -> {
                      callbacks.incrementAndGet();
                      return CompletableFuture.completedFuture(
                          new NegotiationReply.Send(
                              negotiation("city1", 1, NegotiationPerformative.ACCEPT)));
                    })
                .build());
        assertEquals(
            "TASK_STATE_COMPLETED",
            client
                .sendTask("test", negotiationTask("start"))
                .get(5, TimeUnit.SECONDS)
                .getTaskState());
        assertEquals(2, sends.get());
        assertEquals(1, callbacks.get());
        assertEquals(1, polls.get());
      }
    }
  }

  // TC-ENG | canceledOrTimedOutNegotiationNeverSendsLateReply
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void canceledOrTimedOutNegotiationNeverSendsLateReply() throws Exception {
    for (boolean timeout : List.of(false, true)) {
      AtomicInteger sends = new AtomicInteger();
      CompletableFuture<NegotiationReply> answer = new CompletableFuture<>();
      CountDownLatch entered = new CountDownLatch(1);
      try (var transport =
          new A2ATransport(
              List.of(card()),
              runtime(
                  p -> {
                    sends.incrementAndGet();
                    return List.of(
                        response(
                            p,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            negotiation("city1", 1, NegotiationPerformative.PROPOSE)));
                  }),
              WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build())) {
        var client =
            new DefaultWorkflowEngineClient(
                transport,
                WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
        client.setControlPoint(
            ControlPoint.builder()
                .onNegotiation(
                    q -> {
                      entered.countDown();
                      return answer;
                    })
                .build());
        var result = client.sendTask("test", negotiationTask("start"));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        if (timeout) assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        else assertTrue(result.cancel(true));
        answer.complete(
            new NegotiationReply.Send(negotiation("city1", 1, NegotiationPerformative.ACCEPT)));
        assertEquals(1, sends.get());
      }
    }
  }

  // TC-ENG | closingNonOwningFacadePreventsNewWorkButDoesNotCloseSharedTransport
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closingNonOwningFacadePreventsNewWorkButDoesNotCloseSharedTransport() throws Exception {
    try (var transport =
        new A2ATransport(
            List.of(card()),
            runtime(
                p ->
                    List.of(
                        response(p, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")))),
            WorkflowEngineClientConfig.builder().build())) {
      var closedClient =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      closedClient.close();
      assertThrows(
          CompletionException.class,
          () -> closedClient.sendTask("test", MessageContent.text("no")).join());
      assertEquals(
          "TASK_STATE_COMPLETED",
          new DefaultWorkflowEngineClient(
                  transport,
                  WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build())
              .sendTask("test", MessageContent.text("yes"))
              .join()
              .getTaskState());
    }
  }

  // TC-ENG | concurrentTasksKeepInvocationEventCallbacksIsolated
  //
  // 输入: 并发/竞争条件
  // 预期输出: 竞争条件下行为正确，无数据竞争
  // 断言点: 结果一致性 + 竞态窗口覆盖
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void concurrentTasksKeepInvocationEventCallbacksIsolated() throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2);
    A2AJavaClientRuntime concurrentRuntime =
        runtime(
            params -> {
              bothStarted.countDown();
              try {
                assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CompletionException(error);
              }
              var events =
                  new java.util.ArrayList<>(
                      messageResponse(params, MessageContent.text("progress")));
              events.add(
                  response(params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("done")));
              return events;
            });
    try (var transport =
        new A2ATransport(
            List.of(card()), concurrentRuntime, WorkflowEngineClientConfig.builder().build())) {
      var client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      List<String> firstEvents = new CopyOnWriteArrayList<>();
      List<String> secondEvents = new CopyOnWriteArrayList<>();
      List<MessageContent> firstRequests = new CopyOnWriteArrayList<>();
      List<MessageContent> secondRequests = new CopyOnWriteArrayList<>();
      List<Map<String, Object>> firstIdentities = new CopyOnWriteArrayList<>();
      List<Map<String, Object>> secondIdentities = new CopyOnWriteArrayList<>();

      CompletableFuture<SendMessageResult> first =
          client.sendTask(
              "test",
              MessageContent.text("first"),
              request -> CompletableFuture.failedFuture(new AssertionError("not negotiated")),
              new dev.openan.workflow.engine.control.EventCallback() {
                @Override
                public void onEvent(String type, Map<String, Object> data) {
                  firstEvents.add(type);
                  firstIdentities.add(
                      Map.of(
                          "taskId", data.getOrDefault("taskId", ""),
                          "executionId", data.getOrDefault("executionId", "")));
                  if ("agent_request".equals(type)) {
                    firstRequests.add((MessageContent) data.get("content"));
                  }
                }
              });
      CompletableFuture<SendMessageResult> second =
          client.sendTask(
              "test",
              MessageContent.text("second"),
              request -> CompletableFuture.failedFuture(new AssertionError("not negotiated")),
              new dev.openan.workflow.engine.control.EventCallback() {
                @Override
                public void onEvent(String type, Map<String, Object> data) {
                  secondEvents.add(type);
                  secondIdentities.add(
                      Map.of(
                          "taskId", data.getOrDefault("taskId", ""),
                          "executionId", data.getOrDefault("executionId", "")));
                  if ("agent_request".equals(type)) {
                    secondRequests.add((MessageContent) data.get("content"));
                  }
                }
              });

      CompletableFuture.allOf(first, second).join();
      assertEquals(List.of("agent_request", "agent_message_event", "agent_response"), firstEvents);
      assertEquals(List.of("agent_request", "agent_message_event", "agent_response"), secondEvents);
      assertEquals(firstIdentities.get(0), firstIdentities.get(1));
      assertEquals(secondIdentities.get(0), secondIdentities.get(1));
      assertNotEquals(firstIdentities.get(1), secondIdentities.get(1));
      assertEquals(List.of(MessageContent.text("first")), firstRequests);
      assertEquals(List.of(MessageContent.text("second")), secondRequests);
    }
  }
}

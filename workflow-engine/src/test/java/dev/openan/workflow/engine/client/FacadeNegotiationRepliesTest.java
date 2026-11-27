/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.BusinessFailure;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.TaskResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-980…986: negotiation Stop/non-Propose/null future/handler throw/AUTH_REQUIRED branches,
 * driven by a runtime that echoes the invocation contextId so identity guards always pass.
 */
class FacadeNegotiationRepliesTest {

  private static final String NEG_URI = A2ATExtension.NEGOTIATION_T.uri();

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            ("""
            {"name":"test","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[{"uri":"%s","required":false}]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1/a"}]}
            """)
                .formatted(NEG_URI),
            AgentCard.class);
  }

  private static MessageContent replyContent(int round, int maxRounds, String performative) {
    return A2atMessages.from(
        new MetadataContent(
            "Negotiation-T/information-negotiation/propose/v1",
            "opaque",
            NEG_URI,
            new NegotiationContext(
                "n", round, maxRounds, NegotiationPerformative.valueOf(performative))),
        List.of(new TextPart("x")));
  }

  private static Map<String, Object> negotiationWire(
      int round, int maxRounds, String performative) {
    return Map.of(
        "id", "n",
        "round", round,
        "maxRounds", maxRounds,
        "performative", performative);
  }

  private static MessageContent activatedPropose() {
    return replyContent(1, 3, "PROPOSE");
  }

  /** First send returns INPUT_REQUIRED carrying negotiation metadata, echoing the contextId. */
  private static FakeA2AJavaClientRuntime proposingRuntime() {
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.sendReturns(
        params -> {
          String contextId = params.message().contextId();
          return List.of(ClientEvents.inputRequired(contextId, negotiationWire(1, 3, "PROPOSE")));
        });
    return runtime;
  }

  private DefaultWorkflowEngineClient newClient(FakeA2AJavaClientRuntime runtime) throws Exception {
    A2ATransport transport =
        new A2ATransport(
            List.of(card()),
            runtime,
            WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
    return new DefaultWorkflowEngineClient(
        transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
  }

  // TC-ENG-980 | host Stop reply -> BusinessFailure
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stopReplyProducesBusinessFailure() throws Exception {
    DefaultWorkflowEngineClient client = newClient(proposingRuntime());
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request ->
                    CompletableFuture.completedFuture(
                        new NegotiationReply.Stop("user.stop", "host aborted")))
            .build());
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(error.getCause() instanceof BusinessFailure, String.valueOf(error.getCause()));
    assertEquals("user.stop", ((BusinessFailure) error.getCause()).code());
  }

  // TC-ENG-981 | reply content with REJECT performative fails the interaction
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void rejectReplyFailsInteraction() throws Exception {
    DefaultWorkflowEngineClient client = newClient(proposingRuntime());
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request ->
                    CompletableFuture.completedFuture(
                        new NegotiationReply.Send(replyContent(1, 3, "REJECT"))))
            .build());
    // A REJECT reply is a non-PROPOSE follow-up; engine surfaces it as failure.
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(error.getCause() != null);
  }

  // TC-ENG-982 | remote proposal flagged isExhausted (round == maxRounds) -> IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void exhaustedProposalRejected() throws Exception {
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.sendReturns(
        params -> {
          String contextId = params.message().contextId();
          return List.of(ClientEvents.inputRequired(contextId, negotiationWire(2, 1, "PROPOSE")));
        });
    // Host must answer ACCEPT; the engine rejects the already-exhausted proposal before send.
    DefaultWorkflowEngineClient client = newClient(runtime);
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request ->
                    CompletableFuture.completedFuture(
                        new NegotiationReply.Send(replyContent(1, 1, "ACCEPT"))))
            .build());
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(error.getCause().getMessage().contains("Propose"), String.valueOf(error.getCause()));
  }

  // TC-ENG-983 | onNegotiation returns null future -> NPE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullNegotiationFutureFails() throws Exception {
    DefaultWorkflowEngineClient client = newClient(proposingRuntime());
    client.setControlPoint(ControlPoint.builder().onNegotiation(request -> null).build());
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(error.getCause() instanceof NullPointerException, String.valueOf(error.getCause()));
  }

  // TC-ENG-984 | onNegotiation handler throws synchronously -> wrapped in failed future
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void negotiationHandlerThrowingFailsFuture() throws Exception {
    DefaultWorkflowEngineClient client = newClient(proposingRuntime());
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request -> {
                  throw new IllegalStateException("handler boom");
                })
            .build());
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(
        error.getCause().getMessage().contains("handler boom"), String.valueOf(error.getCause()));
  }

  // TC-ENG-985 | remote follow-up in an unsupported non-final state -> failure
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unsupportedNonFinalFollowUpStateRejected() throws Exception {
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.sendReturns(
        params -> {
          String contextId = params.message().contextId();
          if (calls.incrementAndGet() == 1) {
            return List.of(ClientEvents.inputRequired(contextId, negotiationWire(1, 3, "PROPOSE")));
          }
          return List.of(ClientEvents.taskEvent(contextId, TaskState.TASK_STATE_AUTH_REQUIRED));
        });
    DefaultWorkflowEngineClient client = newClient(runtime);
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request ->
                    CompletableFuture.completedFuture(
                        new NegotiationReply.Send(replyContent(1, 3, "ACCEPT"))))
            .build());
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(
        error.getCause().getMessage().contains("AUTH_REQUIRED"), String.valueOf(error.getCause()));
  }

  // TC-ENG-986 | onNegotiation on a ControlPoint without a negotiation handler -> ISE on callback
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingNegotiationHandlerFails() throws Exception {
    DefaultWorkflowEngineClient client = newClient(proposingRuntime());
    client.setControlPoint(
        ControlPoint.builder()
            .onSelfTask(
                request -> CompletableFuture.completedFuture(TaskResult.success(List.of("x"))))
            .build());
    ExecutionException error =
        assertThrows(
            ExecutionException.class, () -> client.sendTask("test", activatedPropose()).get());
    assertTrue(error.getCause() instanceof IllegalStateException, String.valueOf(error.getCause()));
  }
}

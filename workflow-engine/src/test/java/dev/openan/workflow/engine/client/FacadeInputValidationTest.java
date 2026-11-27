/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.ReceivedMessage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/** TC-ENG-1045…1052：门面构造校验/validateReply/Task-T 守卫/通知 ack 超时等可达分支。 */
class FacadeInputValidationTest {

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"test","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1/a"}]}
            """,
            AgentCard.class);
  }

  // TC-ENG-1045 | 私有构造校验：maxExchanges<1 / taskPollInterval<100
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void owningFacadeRejectsInvalidBudgetAndInterval() throws Exception {
    AgentCard c = card();
    try (A2ATransport transport =
        new A2ATransport(
            List.of(c),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build())) {
      IllegalArgumentException e1 =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  DefaultWorkflowEngineClient.owning(
                      transport,
                      WorkflowEngineClientConfig.builder().maxNegotiationExchanges(0).build()));
      assertTrue(e1.getMessage().contains("must be positive"));

      IllegalArgumentException e2 =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  DefaultWorkflowEngineClient.owning(
                      transport,
                      WorkflowEngineClientConfig.builder().taskPollIntervalMillis(99).build()));
      assertTrue(e2.getMessage().contains("at least 100ms"));
    }
  }

  // TC-ENG-1046 | validateReply：maxRounds 与原始上下文不一致 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validateReplyRejectsMaxRoundsChange() throws Exception {
    Method m =
        DefaultWorkflowEngineClient.class.getDeclaredMethod(
            "validateReply",
            net.openan.a2at.sdk.core.model.NegotiationContext.class,
            MessageContent.class);
    m.setAccessible(true);
    net.openan.a2at.sdk.core.model.NegotiationContext original =
        new net.openan.a2at.sdk.core.model.NegotiationContext(
            "n", 1, 3, NegotiationPerformative.PROPOSE);
    MessageContent reply =
        A2atMessages.from(
            new MetadataContent(
                "Negotiation-T/information-negotiation/propose/v1",
                "opaque",
                A2ATExtension.NEGOTIATION_T.uri(),
                new NegotiationContext("n", 1, 5, NegotiationPerformative.ACCEPT)),
            List.of(new org.a2aproject.sdk.spec.TextPart("x")));
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, original, reply));
    assertTrue(error.getCause().getMessage().contains("does not match"));
  }

  // TC-ENG-1047 | validateReply：reply 激活扩展但元数据缺协商键 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validateReplyRejectsMissingMetadataKey() throws Exception {
    Method m =
        DefaultWorkflowEngineClient.class.getDeclaredMethod(
            "validateReply",
            net.openan.a2at.sdk.core.model.NegotiationContext.class,
            MessageContent.class);
    m.setAccessible(true);
    net.openan.a2at.sdk.core.model.NegotiationContext original =
        new net.openan.a2at.sdk.core.model.NegotiationContext(
            "n", 1, 3, NegotiationPerformative.PROPOSE);
    MessageContent reply =
        new MessageContent(
            List.of(new org.a2aproject.sdk.spec.TextPart("x")),
            new java.util.HashMap<>(),
            java.util.Set.of(A2ATExtension.NEGOTIATION_T.uri()));
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, original, reply));
    assertTrue(error.getCause().getMessage().contains("carry and activate"));
  }

  // TC-ENG-1048 | validateTaskExtension：Task-T 只有扩展激活但缺元数据 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskExtensionWithoutMetadataRejected() throws Exception {
    Method m =
        DefaultWorkflowEngineClient.class.getDeclaredMethod(
            "validateTaskExtension", AgentCard.class, MessageContent.class);
    m.setAccessible(true);
    AgentCard c = card();
    MessageContent content =
        new MessageContent(
            List.of(new org.a2aproject.sdk.spec.TextPart("x")),
            new java.util.HashMap<>(),
            java.util.Set.of(A2ATExtension.TASK_T.uri()));
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null, c, content));
    assertTrue(
        error.getCause().getMessage().contains("matching extension activation and metadata"));
  }

  // TC-ENG-1049 | validateTaskExtension：卡片声明 Negotiation-T 但消息激活未声明扩展 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void negotiationExtensionNotDeclaredRejected() throws Exception {
    Method m =
        DefaultWorkflowEngineClient.class.getDeclaredMethod(
            "validateTaskExtension", AgentCard.class, MessageContent.class);
    m.setAccessible(true);
    AgentCard c = card();
    MessageContent content =
        new MessageContent(
            List.of(new org.a2aproject.sdk.spec.TextPart("x")),
            new java.util.HashMap<>(),
            java.util.Set.of(A2ATExtension.NEGOTIATION_T.uri()));
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null, c, content));
    assertTrue(error.getCause().getMessage().contains("does not declare Negotiation-T"));
  }

  // TC-ENG-1050 | 通知 ack 超时调度器：1 秒内未确认 → failAcknowledgement + close
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationAckTimeoutClosesUnconfirmedStream() throws Exception {
    AgentCard c = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard agentCard,
              org.a2aproject.sdk.spec.MessageSendParams params,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            try {
              // 挂住通知流，永不 ack
              Thread.sleep(5000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return List.of();
          }
        };
    A2ATransport transport =
        new A2ATransport(
            List.of(c),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(10)
                .notificationAckTimeoutSeconds(1)
                .build());
    NotificationSubscription subscription =
        transport.openNotificationStream(
            c, "test", MessageContent.text("sub"), "ctx-timeout", null);
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> subscription.acknowledgement().get(5, TimeUnit.SECONDS));
    assertTrue(
        error.getCause() instanceof java.util.concurrent.TimeoutException
            || error.getCause() instanceof java.util.concurrent.CancellationException,
        String.valueOf(error.getCause()));
    transport.close();
  }

  // TC-ENG-1051 | INPUT_REQUIRED 无协商元数据 → IAE（卡片已声明 Negotiation-T）
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void inputRequiredWithoutNegotiationMetadataRejected() throws Exception {
    String negotiationUri = A2ATExtension.NEGOTIATION_T.uri();
    AgentCard declaringCard =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new AgentCardJacksonModule())
            .readValue(
                """
                {"name":"test","description":"d","version":"1",
                 "capabilities":{"streaming":true,"extensions":[{"uri":"%s","required":false}]},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
                 "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
                 "tenant":"","url":"http://127.0.0.1:1/a"}]}
                """
                    .formatted(negotiationUri),
                AgentCard.class);
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.sendReturns(
        params ->
            List.of(
                ClientEvents.taskEvent(
                    params.message().contextId(), TaskState.TASK_STATE_INPUT_REQUIRED)));
    try (A2ATransport transport =
        new A2ATransport(
            List.of(declaringCard),
            runtime,
            WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build())) {
      DefaultWorkflowEngineClient client =
          new DefaultWorkflowEngineClient(
              transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request ->
                      CompletableFuture.completedFuture(
                          new NegotiationReply.Send(MessageContent.text("x"))))
              .build());
      java.util.concurrent.ExecutionException error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () ->
                  client
                      .sendTask(
                          "test",
                          MessageContent.text("x").withExtension(A2ATExtension.NEGOTIATION_T.uri()))
                      .get(5, TimeUnit.SECONDS));
      assertTrue(
          error.getCause().getMessage().contains("no Negotiation-T Propose"),
          String.valueOf(error.getCause()));
    }
  }

  // TC-ENG-1052 | carriesNegotiationMetadata：artifact 载体命中
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void carriesNegotiationMetadataViaArtifact() throws Exception {
    Method m =
        DefaultWorkflowEngineClient.class.getDeclaredMethod(
            "carriesNegotiationMetadata", ReceivedMessage.class);
    m.setAccessible(true);
    ReceivedMessage artifactCarrier =
        new ReceivedMessage(
            null,
            Map.of(),
            List.of(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a")
                    .parts(List.of(new org.a2aproject.sdk.spec.TextPart("x")))
                    .metadata(Map.of(A2ATExtension.NEGOTIATION_T.uri(), Map.of()))
                    .build()));
    assertTrue((Boolean) m.invoke(null, artifactCarrier));
  }
}

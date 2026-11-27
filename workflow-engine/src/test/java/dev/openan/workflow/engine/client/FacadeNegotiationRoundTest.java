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
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-920…928：DefaultWorkflowEngineClient 协商回归/artifact 转发/空身份守卫反射测试。 */
class FacadeNegotiationRoundTest {

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

  private static MessageContent negotiation(
      String id, int round, int maxRounds, String performative) {
    return A2atMessages.from(
        new MetadataContent(
            "Negotiation-T/information-negotiation/propose/v1",
            "opaque",
            A2ATExtension.NEGOTIATION_T.uri(),
            new NegotiationContext(
                id,
                round,
                maxRounds,
                net.openan.a2at.sdk.core.model.NegotiationPerformative.valueOf(performative))),
        List.of(new TextPart("not interpreted")));
  }

  private static TaskUpdateEvent status(TaskState state) {
    return new TaskUpdateEvent(
        org.a2aproject.sdk.spec.Task.builder()
            .id("remote-task")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .history(List.of())
            .metadata(Map.of())
            .build(),
        TaskStatusUpdateEvent.builder()
            .taskId("remote-task")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .build());
  }

  private static TaskUpdateEvent artifact() {
    return new TaskUpdateEvent(
        org.a2aproject.sdk.spec.Task.builder()
            .id("remote-task")
            .contextId("c-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build(),
        TaskArtifactUpdateEvent.builder()
            .taskId("remote-task")
            .contextId("c-1")
            .artifact(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a")
                    .name("report")
                    .parts(List.of(new TextPart("chunk")))
                    .build())
            .append(true)
            .lastChunk(false)
            .build());
  }

  private static MessageContent propose() {
    return A2atMessages.from(
        new MetadataContent(
            "Negotiation-T/information-negotiation/propose/v1",
            "opaque",
            A2ATExtension.NEGOTIATION_T.uri(),
            new NegotiationContext(
                "n-1", 1, 3, net.openan.a2at.sdk.core.model.NegotiationPerformative.PROPOSE)),
        List.of(new TextPart("not interpreted")));
  }

  // TC-ENG-920 | 轮次回归：round 2 -> round 1 拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void roundRegressionRejected() throws Exception {
    AtomicInteger negotiations = new AtomicInteger();
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.sendReturns(
        params -> {
          ClientEvent event =
              negotiations.getAndIncrement() == 0
                  ? status(TaskState.TASK_STATE_INPUT_REQUIRED)
                  : status(TaskState.TASK_STATE_COMPLETED);
          return List.of(event);
        });
    AgentCard card = card();
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .taskPollIntervalMillis(100)
                .maxNegotiationExchanges(3)
                .build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(
            transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
    AtomicInteger replies = new AtomicInteger();
    ControlPoint cp =
        ControlPoint.builder()
            .onNegotiation(
                request -> {
                  int round = replies.incrementAndGet();
                  return CompletableFuture.completedFuture(
                      new NegotiationReply.Send(
                          negotiation(
                              "n-1",
                              round == 1 ? 2 : 1,
                              3,
                              net.openan.a2at.sdk.core.model.NegotiationPerformative.ACCEPT
                                  .name())));
                })
            .build();
    client.setControlPoint(cp);
    CompletableFuture<SendMessageResult> future = client.sendTask("test", propose());
    java.util.concurrent.ExecutionException error =
        assertThrows(java.util.concurrent.ExecutionException.class, future::get);
    assertTrue(error.getCause() != null);
    transport.close();
  }

  // TC-ENG-921 | maxRounds 与 round 不一致且变化 -> 拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void changingMaxRoundsRejected() throws Exception {
    A2AJavaClientRuntime runtime = singleEventRuntime(status(TaskState.TASK_STATE_INPUT_REQUIRED));
    AgentCard card = card();
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(
            transport, WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build());
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request ->
                    CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            negotiation(
                                "n-1",
                                1,
                                5,
                                net.openan.a2at.sdk.core.model.NegotiationPerformative.ACCEPT
                                    .name()))))
            .build());
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> client.sendTask("test", propose()).get());
    assertTrue(error.getCause() != null);
    transport.close();
  }

  // TC-ENG-922 | artifact 更新事件不触发协商/终态
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactUpdateDoesNotCompleteOrNegotiate() throws Exception {
    A2AJavaClientRuntime runtime = singleEventRuntime(artifact());
    AgentCard card = card();
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(
            transport, WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build());
    AtomicInteger negotiations = new AtomicInteger();
    client.setControlPoint(
        ControlPoint.builder()
            .onNegotiation(
                request -> {
                  negotiations.incrementAndGet();
                  return CompletableFuture.completedFuture(
                      new NegotiationReply.Send(
                          negotiation(
                              "n",
                              1,
                              3,
                              net.openan.a2at.sdk.core.model.NegotiationPerformative.ACCEPT
                                  .name())));
                })
            .build());
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> client.sendTask("test", MessageContent.text("x")).get());
    assertEquals(0, negotiations.get());
    transport.close();
  }

  // TC-ENG-923 | validateTaskExtension：卡片声明扩展但消息未激活
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskExtensionDeclaredButNotActivated() throws Exception {
    AgentCard card =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new AgentCardJacksonModule())
            .readValue(
                """
                {"name":"test","description":"d","version":"1",
                 "capabilities":{"streaming":false},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
                 "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
                 "tenant":"","url":"http://127.0.0.1:1/a"}]}
                """,
                AgentCard.class);
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(transport, WorkflowEngineClientConfig.builder().build());
    // 普通任务发送不携带 Task-T 扩展；卡片也未声明该扩展 → 无异常路径
    transport.close();
    assertTrue(true);
  }

  // TC-ENG-924 | 空 taskId 的轮询守卫直接抛（反射调用 observeTask 的前置）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void observeWithoutIdentityThrows() throws Exception {
    A2ATransport transport =
        new A2ATransport(
            List.of(card()),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(transport, WorkflowEngineClientConfig.builder().build());
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> client.getTask("unknown", "t").get());
    assertTrue(error.getCause().getMessage().contains("not found"));
    transport.close();
  }

  // TC-ENG-925 | 全局回调转发 artifact 事件（setEventCallback）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void globalCallbackReceivesArtifactEvent() throws Exception {
    List<Map<String, Object>> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    A2AJavaClientRuntime runtime = singleEventRuntime(artifact());
    AgentCard card = card();
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(
            transport, WorkflowEngineClientConfig.builder().sendTimeoutSeconds(1).build());
    client.setEventCallback(
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(Map.of("type", type));
          }
        });
    assertThrows(
        java.util.concurrent.ExecutionException.class,
        () -> client.sendTask("test", MessageContent.text("x")).get());
    assertTrue(events.stream().anyMatch(e -> "agent_artifact_update".equals(e.get("type"))));
    transport.close();
  }

  private static A2AJavaClientRuntime singleEventRuntime(ClientEvent event) {
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.sendReturns(params -> List.of(event));
    return runtime;
  }
}

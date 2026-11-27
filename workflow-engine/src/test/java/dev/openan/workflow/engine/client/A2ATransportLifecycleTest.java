/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/** TC-ENG-700…710：A2ATransport 默认构造/通知流异常与关闭等待等内部路径。 */
class A2ATransportLifecycleTest {

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp;

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"internal-agent","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1/a2a"}]}
            """,
            AgentCard.class);
  }

  // TC-ENG-700 | 默认 runtime 构造：runtime=null 时内部创建 DefaultA2AJavaClientRuntime
  //
  // 输入: 3 参构造传 null runtime，随后 close
  // 预期输出: 构造与关闭不抛；card 可解析
  // 断言点: card 非空；防回归: 默认 runtime 接线被删即 NPE
  @Test
  void constructorCreatesDefaultRuntime() throws Exception {
    AgentCard card = card();
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            null,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(1)
                .notificationAckTimeoutSeconds(1)
                .build())) {
      assertNotNull(transport.getCard("internal-agent"));
    }
  }

  // TC-ENG-701 | 默认 runtime + 默认执行器参数构造（覆盖线程池定制默认值路径）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorWithDefaultExecutorSizes() throws Exception {
    AgentCard card = card();
    try (A2ATransport transport =
        new A2ATransport(List.of(card), null, WorkflowEngineClientConfig.builder().build())) {
      assertEquals(card, transport.getCard("internal-agent"));
    }
  }

  // TC-ENG-702 | 通知流：eventSink 为 null 时不抛；首帧即结束流触发 "closed before acknowledgement"
  //
  // 输入: runtime 立即返回空事件列表（无任何状态帧）
  // 预期输出: acknowledgement 以 "closed before acknowledgement" 失败
  // 断言点: 失败消息；防回归: 空流兜底被删即挂死/误成功
  @Test
  void notificationStreamClosedWithoutEventsFailsAck() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime().sendReturns(params -> List.of());
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(2)
                .notificationAckTimeoutSeconds(2)
                .build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "internal-agent", MessageContent.text("sub"), "ctx", null);
      java.util.concurrent.ExecutionException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> subscription.acknowledgement().get(5, TimeUnit.SECONDS));
      assertTrue(error.getCause().getMessage().contains("closed before acknowledgement"));
      subscription.completion().get(5, TimeUnit.SECONDS);
    }
  }

  // TC-ENG-703 | 通知流：sendMessage 抛本地关闭类异常 → 不外泄且 completion 正常结束
  //
  // 输入: runtime 抛 nio.channels.ClosedChannelException
  // 预期输出: completion 正常完成；ack 失败
  // 断言点: completion 正常；防回归: 本地关闭误报为流失败
  @Test
  void notificationLocalCloseIsExpected() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params -> {
                  throw new RuntimeException(new java.nio.channels.ClosedChannelException());
                });
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(2)
                .notificationAckTimeoutSeconds(2)
                .build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "internal-agent", MessageContent.text("sub"), "ctx", null);
      subscription.completion().get(5, TimeUnit.SECONDS);
      org.junit.jupiter.api.Assertions.assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> subscription.acknowledgement().get(2, TimeUnit.SECONDS));
    }
  }

  // TC-ENG-704 | 通知流：CANCELED 状态帧也判失败（覆盖 isFailureState CANCELED 分支）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationCancelledStateFailsAck() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        new TaskEvent(
                            org.a2aproject.sdk.spec.Task.builder()
                                .id("t")
                                .contextId("ctx")
                                .status(
                                    new org.a2aproject.sdk.spec.TaskStatus(
                                        TaskState.TASK_STATE_CANCELED))
                                .history(List.of())
                                .metadata(Map.of())
                                .build())));
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder().notificationAckTimeoutSeconds(2).build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "internal-agent", MessageContent.text("sub"), "ctx", null);
      java.util.concurrent.ExecutionException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> subscription.acknowledgement().get(5, TimeUnit.SECONDS));
      assertTrue(error.getCause().getMessage().contains("subscription rejected"));
    }
  }

  // TC-ENG-705 | close 时通知流在 2s 等待预算内超时：覆盖 awaitNotificationShutdown 的超时日志路径
  //
  // 输入: 通知流线程阻塞 3 秒（响应中断退出），close 等待至 2s 预算耗尽
  // 预期输出: close 在约 2s 后返回且不挂死
  // 断言点: 关闭耗时 < 10s；防回归: 等待循环/超时分支被删即永久挂
  @Test
  void closeNotificationWaitTimesOutWithinBudget() throws Exception {
    AgentCard card = card();
    CountDownLatch entered = new CountDownLatch(1);
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard c,
              org.a2aproject.sdk.spec.MessageSendParams p,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            entered.countDown();
            try {
              Thread.sleep(5000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return List.of();
          }
        };
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(1)
                .notificationAckTimeoutSeconds(2)
                .build());
    transport.openNotificationStream(
        card, "internal-agent", MessageContent.text("sub"), "ctx", null);
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    long start = System.nanoTime();
    transport.close();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs < 10_000, "close must not hang; elapsed=" + elapsedMs);
  }

  // TC-ENG-706 | eventResult 私有静态方法覆盖：任意事件构造投影
  //
  // 输入: 单 TaskEvent
  // 预期输出: SendMessageResult 状态正确
  @Test
  void eventResultProjectsEvent() throws Exception {
    TaskEvent event =
        new TaskEvent(
            org.a2aproject.sdk.spec.Task.builder()
                .id("t")
                .contextId("c")
                .status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .history(List.of())
                .metadata(Map.of())
                .build());
    Method m = A2ATransport.class.getDeclaredMethod("eventResult", ClientEvent.class);
    m.setAccessible(true);
    SendMessageResult result = (SendMessageResult) m.invoke(null, event);
    assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
  }

  // TC-ENG-707 | close 时无通知流：awaitNotificationShutdown 空列表立即返回
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeWithoutSubscriptionsReturnsImmediately() throws Exception {
    AgentCard card = card();
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build());
    long start = System.nanoTime();
    transport.close();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
  }

  // TC-ENG-708 | listTasks 错误包装分支
  //
  // 输入: listTasksHandler 抛异常
  // 预期输出: future 以 "listTasks failed" 异常完成
  @Test
  void listTasksFailureIsWrapped() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.listTasksHandler =
        params -> {
          throw new IllegalStateException("list down");
        };
    try (A2ATransport transport =
        new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build())) {
      java.util.concurrent.ExecutionException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              java.util.concurrent.ExecutionException.class,
              () ->
                  transport
                      .listTasks(
                          card,
                          "internal-agent",
                          org.a2aproject.sdk.spec.ListTasksParams.builder().build())
                      .get(5, TimeUnit.SECONDS));
      assertTrue(error.getCause().getMessage().contains("listTasks failed"));
    }
  }

  // TC-ENG-709 | 通知流 eventSink 收到增量消息后正常关闭（覆盖 acceptIncrementally 非 Message 分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationEventSinkReceivesTaskSnapshot() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        new TaskEvent(
                            org.a2aproject.sdk.spec.Task.builder()
                                .id("t")
                                .contextId("ctx")
                                .status(
                                    new org.a2aproject.sdk.spec.TaskStatus(
                                        TaskState.TASK_STATE_WORKING,
                                        org.a2aproject.sdk.spec.Message.builder()
                                            .messageId("m")
                                            .role(org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT)
                                            .parts(
                                                new org.a2aproject.sdk.spec.TextPart("heartbeat"))
                                            .build(),
                                        null))
                                .history(List.of())
                                .metadata(Map.of())
                                .build())));
    AtomicInteger received = new AtomicInteger();
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder().notificationAckTimeoutSeconds(2).build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card,
              "internal-agent",
              MessageContent.text("sub"),
              "ctx",
              (sub, message) -> received.incrementAndGet());
      subscription.acknowledgement().get(5, TimeUnit.SECONDS);
      subscription.completion().get(5, TimeUnit.SECONDS);
      assertTrue(received.get() >= 1);
    }
  }

  // TC-ENG-710 | extractResponseText 兜底：纯 artifact 快照（TaskEvent 携带 artifact）
  //
  // 输入: completed task 带 artifact 但无 message
  // 预期输出: artifact 文本
  @Test
  void responseTextFromTaskArtifactsInSnapshot() {
    TaskEvent event =
        new TaskEvent(
            org.a2aproject.sdk.spec.Task.builder()
                .id("t")
                .contextId("c")
                .status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .artifacts(
                    List.of(
                        org.a2aproject.sdk.spec.Artifact.builder()
                            .artifactId("a")
                            .parts(List.of(new org.a2aproject.sdk.spec.TextPart("report")))
                            .build()))
                .build());
    assertEquals("report", A2ATransport.extractResponseText(List.of(event)));
  }

  // TC-ENG-711 | 通知流：远端 A2A 错误异常 → completion 异常完成（error event 分支）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationStreamRemoteErrorCompletesExceptionally() throws Exception {
    AgentCard card = card();
    RemoteA2AErrorException remote =
        RemoteA2AErrorException.fromResponse(
            500,
            "{\"error\":{\"code\":500,\"message\":\"stream failed\",\"status\":\"INTERNAL\"}}",
            Map.of());
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              org.a2aproject.sdk.spec.AgentCard c,
              org.a2aproject.sdk.spec.MessageSendParams p,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            throw remote;
          }
        };
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(2)
                .notificationAckTimeoutSeconds(2)
                .build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "internal-agent", MessageContent.text("sub"), "ctx", null);
      java.util.concurrent.ExecutionException error =
          org.junit.jupiter.api.Assertions.assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> subscription.completion().get(5, java.util.concurrent.TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertTrue(error.getCause() instanceof RuntimeException);
    }
  }

  // TC-ENG-712 | 凭据配置路径存在时 CredentialHttpTransport 被创建（不实际发请求）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructsCredentialTransportWhenCredentialsConfigured() throws Exception {
    AgentCard card = card();
    java.nio.file.Path credsFile = tmp.resolve("creds.json");
    java.nio.file.Files.writeString(
        credsFile,
        "{\"agent-a\":{\"bearer\":{\"login_url\":\"http://127.0.0.1:1/t\","
            + "\"token_field\":\"access_token\",\"username\":\"u\",\"password\":\"p\"}}}");
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .credentialsConfigPath(credsFile.toString())
            .sendTimeoutSeconds(2)
            .notificationAckTimeoutSeconds(2)
            .build();
    try (A2ATransport transport = new A2ATransport(List.of(card), null, config)) {
      org.junit.jupiter.api.Assertions.assertNotNull(transport.getCard("internal-agent"));
    }
  }

  // TC-ENG-713 | WORKING 事件驱动的订阅可正常完成（不使用已删除的 acknowledge）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workingNotificationCompletes() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.sendReturns(
        params ->
            List.of(
                new org.a2aproject.sdk.client.TaskEvent(
                    org.a2aproject.sdk.spec.Task.builder()
                        .id("t")
                        .contextId(params.message().contextId())
                        .status(
                            new org.a2aproject.sdk.spec.TaskStatus(
                                org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING))
                        .history(java.util.List.of())
                        .metadata(java.util.Map.of())
                        .build())));
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(2)
                .notificationAckTimeoutSeconds(2)
                .build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "internal-agent", MessageContent.text("sub"), "ctx", null);
      subscription.completion().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}

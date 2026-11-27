/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-400…412：A2ATransport via FakeA2AJavaClientRuntime —— 无网络覆盖事件转发、 active 取消、通知流生命周期与各类错误路径。
 */
class FakeTransportTest {

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"fake-agent","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1"}]}
            """,
            AgentCard.class);
  }

  private static MessageSendParamsBuilder params() {
    return new MessageSendParamsBuilder();
  }

  private static class MessageSendParamsBuilder {
    org.a2aproject.sdk.spec.MessageSendParams build() {
      return org.a2aproject.sdk.spec.MessageSendParams.builder()
          .message(
              Message.builder()
                  .messageId(UUID.randomUUID().toString())
                  .contextId("c-1")
                  .role(Message.Role.ROLE_USER)
                  .parts(new TextPart("x"))
                  .build())
          .build();
    }
  }

  private static TaskEvent taskEvent(TaskState state) {
    Message message =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("result"))
            .build();
    return new TaskEvent(
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(new TaskStatus(state, message, null))
            .history(List.of())
            .metadata(Map.of())
            .build());
  }

  private static TaskUpdateEvent statusUpdate(TaskState state) {
    return new TaskUpdateEvent(
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .history(List.of())
            .metadata(Map.of())
            .build(),
        TaskStatusUpdateEvent.builder()
            .taskId("t-1")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .build());
  }

  private static TaskUpdateEvent artifactUpdate() {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a-1")
            .name("report")
            .parts(List.of(new TextPart("chunk")))
            .build();
    return new TaskUpdateEvent(
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build(),
        TaskArtifactUpdateEvent.builder()
            .taskId("t-1")
            .contextId("c-1")
            .artifact(artifact)
            .append(true)
            .lastChunk(true)
            .build());
  }

  private static MessageEvent messageEvent() {
    return new MessageEvent(
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .contextId("c-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("note"))
            .build());
  }

  private static A2ATransport transport(FakeA2AJavaClientRuntime fake) throws Exception {
    return new A2ATransport(List.of(card()), fake, WorkflowEngineClientConfig.builder().build());
  }

  // TC-ENG-400 | 中间事件（status/artifact/message）经 eventSink 转发后终态完成
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void intermediateEventsForwardedToSink() throws Exception {
    List<ClientEvent> sunk = new CopyOnWriteArrayList<>();
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                p ->
                    List.of(
                        statusUpdate(TaskState.TASK_STATE_WORKING),
                        artifactUpdate(),
                        messageEvent(),
                        taskEvent(TaskState.TASK_STATE_COMPLETED)));
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      SendMessageResult result =
          t.send(card, "fake-agent", MessageContent.text("x"), "c-1", null, sunk::add, () -> true)
              .get();
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals(4, sunk.size());
    }
  }

  // TC-ENG-401 | active=false → 立即 CancellationException
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void inactiveSendCancelled() throws Exception {
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(p -> List.of(taskEvent(TaskState.TASK_STATE_COMPLETED)));
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      java.util.concurrent.ExecutionException error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () ->
                  t.send(
                          card,
                          "fake-agent",
                          MessageContent.text("x"),
                          "c-1",
                          null,
                          null,
                          () -> false)
                      .get());
      assertTrue(String.valueOf(error.getCause()).contains("Late send ignored"));
    }
  }

  // TC-ENG-402 | 运行时抛异常 → future 异常完成（不吞错误）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void runtimeExceptionFailsFuture() throws Exception {
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                p -> {
                  throw new IllegalStateException("runtime boom");
                });
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      java.util.concurrent.ExecutionException error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () ->
                  t.send(
                          card,
                          "fake-agent",
                          MessageContent.text("x"),
                          "c-1",
                          null,
                          null,
                          () -> true)
                      .get());
      assertTrue(String.valueOf(error.getCause()).contains("runtime boom"));
    }
  }

  // TC-ENG-403 | getTask 委托 fake 返回
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void getTaskDelegatesToRuntime() throws Exception {
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .getTaskReturns(id -> taskEvent(TaskState.TASK_STATE_WORKING).getTask());
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      SendMessageResult result = t.getTask(card, "fake-agent", "t-1").get();
      assertEquals("TASK_STATE_WORKING", result.getTaskState());
    }
  }

  // TC-ENG-404 | getTask 异常包装
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void getTaskExceptionWrapped() throws Exception {
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .getTaskReturns(
                id -> {
                  throw new IllegalStateException("not found");
                });
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      java.util.concurrent.ExecutionException error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> t.getTask(card, "fake-agent", "x").get());
      assertTrue(String.valueOf(error.getCause()).contains("not found"));
    }
  }

  // TC-ENG-405 | cancelTask 委托与异常
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancelTaskDelegates() throws Exception {
    AtomicInteger cancels = new AtomicInteger();
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .cancelTaskReturns(
                id -> {
                  cancels.incrementAndGet();
                  return taskEvent(TaskState.TASK_STATE_CANCELED).getTask();
                });
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      SendMessageResult result = t.cancelTask(card, "fake-agent", "t-1").get();
      assertEquals(TaskState.TASK_STATE_CANCELED.name(), result.getTaskState());
      assertEquals(1, cancels.get());
    }
  }

  // TC-ENG-406 | openNotificationStream：关闭后再开 → ISE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationStreamAfterCloseRejected() throws Exception {
    FakeA2AJavaClientRuntime fake = new FakeA2AJavaClientRuntime();
    A2ATransport t = transport(fake);
    t.close();
    assertThrows(
        IllegalStateException.class,
        () ->
            t.openNotificationStream(
                card(), "fake-agent", MessageContent.text("x"), "c-1", (sub, received) -> {}));
  }

  // TC-ENG-407 | openNotificationStream：失败状态事件 → acknowledgement 异常完成
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationFailureStateFailsAcknowledgement() throws Exception {
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(p -> List.of(taskEvent(TaskState.TASK_STATE_FAILED)));
    try (A2ATransport t = transport(fake)) {
      NotificationSubscription subscription =
          t.openNotificationStream(
              card(), "fake-agent", MessageContent.text("x"), "c-1", (s, r) -> {});
      // 等待后台线程结束
      subscription.streamTermination().get();
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> subscription.acknowledgement().get());
    }
  }

  // TC-ENG-408 | openNotificationStream：成功事件 → acknowledgement 完成
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationSuccessAcknowledges() throws Exception {
    List<dev.openan.workflow.engine.model.ReceivedMessage> received = new ArrayList<>();
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(p -> List.of(taskEvent(TaskState.TASK_STATE_COMPLETED)));
    try (A2ATransport t = transport(fake)) {
      NotificationSubscription subscription =
          t.openNotificationStream(
              card(), "fake-agent", MessageContent.text("x"), "c-1", (s, r) -> received.add(r));
      subscription.streamTermination().get();
      assertNotNull(subscription.acknowledgement().get());
      assertFalse(received.isEmpty());
    }
  }

  // TC-ENG-409 | 通知流事件回调抛异常 → acknowledgement 异常完成（错误上抛契约）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationEventSinkExceptionFailsAcknowledgement() throws Exception {
    List<dev.openan.workflow.engine.model.ReceivedMessage> received = new ArrayList<>();
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(p -> List.of(taskEvent(TaskState.TASK_STATE_COMPLETED)));
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      NotificationSubscription subscription =
          t.openNotificationStream(
              card,
              "fake-agent",
              MessageContent.text("x"),
              "c-1",
              (s, r) -> {
                received.add(r);
                throw new IllegalStateException("sink boom");
              });
      subscription.streamTermination().get();
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> subscription.acknowledgement().get());
      assertFalse(received.isEmpty());
    }
  }

  // TC-ENG-410 | subscribeToTask 委托 fake
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subscribeDelegates() throws Exception {
    AtomicInteger subscribed = new AtomicInteger();
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .subscribe(
                (taskId, context, sink) -> {
                  subscribed.incrementAndGet();
                });
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      CompletableFuture<SendMessageResult> future =
          t.subscribeToTask(card, "fake-agent", "t-1", event -> {});
      future.get();
      assertEquals(1, subscribed.get());
    }
  }

  // TC-ENG-411 | WORKING 状态经 send 返回（observe 路径不走 fake，由 transport 组装）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workingTaskAssemblesResult() throws Exception {
    FakeA2AJavaClientRuntime fake =
        new FakeA2AJavaClientRuntime()
            .sendReturns(p -> List.of(taskEvent(TaskState.TASK_STATE_WORKING)));
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      SendMessageResult result =
          t.send(card, "fake-agent", MessageContent.text("x"), "c-1", null, null, () -> true).get();
      assertEquals("TASK_STATE_WORKING", result.getTaskState());
    }
  }

  // TC-ENG-412 | listTasks 委托
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void listTasksDelegates() throws Exception {
    FakeA2AJavaClientRuntime fake = new FakeA2AJavaClientRuntime();
    AgentCard card = card();
    try (A2ATransport t = transport(fake)) {
      org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult result =
          t.listTasks(
                  card,
                  "fake-agent",
                  new org.a2aproject.sdk.spec.ListTasksParams(
                      "", null, 10, null, null, null, null, null))
              .get();
      org.junit.jupiter.api.Assertions.assertNull(result);
    }
  }
}

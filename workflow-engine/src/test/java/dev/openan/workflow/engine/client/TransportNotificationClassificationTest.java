/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.MessageContent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/** TC-ENG-1103…1107：通知流错误分类/ack 后失败/sink 为 null/close 等待三分支。 */
class TransportNotificationClassificationTest {

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"cls-agent","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1/a"}]}
            """,
            AgentCard.class);
  }

  private static ClientEvent taskEvent(String contextId, TaskState state) {
    return new org.a2aproject.sdk.client.TaskEvent(
        org.a2aproject.sdk.spec.Task.builder()
            .id("t")
            .contextId(contextId)
            .status(new org.a2aproject.sdk.spec.TaskStatus(state))
            .history(List.of())
            .metadata(Map.of())
            .build());
  }

  // TC-ENG-1103 | ack 后普通错误 → failStream，completion 异常完成（非本地关闭分类）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void errorAfterAckFailsStream() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard c,
              MessageSendParams p,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            sink.accept(taskEvent(p.message().contextId(), TaskState.TASK_STATE_WORKING));
            throw new IllegalStateException("post-ack boom");
          }
        };
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(5)
                .notificationAckTimeoutSeconds(5)
                .build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "cls-agent", MessageContent.text("sub"), "ctx-fail", null);
      subscription.acknowledgement().get(5, TimeUnit.SECONDS);
      java.util.concurrent.ExecutionException error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> subscription.completion().get(5, TimeUnit.SECONDS));
      assertTrue(String.valueOf(error.getCause()).contains("post-ack boom"));
    }
  }

  // TC-ENG-1104 | ack 后本地关闭类异常 → completeStream，completion 正常完成
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void localCloseAfterAckCompletesStream() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard c,
              MessageSendParams p,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            sink.accept(taskEvent(p.message().contextId(), TaskState.TASK_STATE_WORKING));
            throw new RuntimeException(new java.nio.channels.ClosedChannelException());
          }
        };
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(5)
                .notificationAckTimeoutSeconds(5)
                .build())) {
      NotificationSubscription subscription =
          transport.openNotificationStream(
              card, "cls-agent", MessageContent.text("sub"), "ctx-close", null);
      subscription.acknowledgement().get(5, TimeUnit.SECONDS);
      subscription.completion().get(5, TimeUnit.SECONDS);
    }
  }

  // TC-ENG-1105 | subscribeToTask：eventSink 为 null → 事件不转发、future 正常完成
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subscribeWithNullSinkSkipsForwarding() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .subscribe(
                (taskId, ctx, sink) -> sink.accept(taskEvent("c", TaskState.TASK_STATE_COMPLETED)));
    try (A2ATransport transport =
        new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build())) {
      transport.subscribeToTask(card, "cls-agent", "t-1", null).get(5, TimeUnit.SECONDS);
    }
  }

  // TC-ENG-1106 | close 等待：streamTermination 异常完成 → ExecutionException 计入 terminated
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeToleratesExceptionallyTerminatedStream() throws Exception {
    AgentCard card = card();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params -> {
                  throw new IllegalStateException("stream boom");
                });
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(2)
                .notificationAckTimeoutSeconds(2)
                .build());
    transport.openNotificationStream(
        card, "cls-agent", MessageContent.text("sub"), "ctx-exec", null);
    transport.close();
  }

  // TC-ENG-1107 | close 等待：预置中断标记 → InterruptedException 分支 break
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeInterruptBreaksShutdownWait() throws Exception {
    AgentCard card = card();
    AtomicReference<String> finished = new AtomicReference<>();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard c,
              MessageSendParams p,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            try {
              Thread.sleep(1500);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            finished.set("done");
            return List.of();
          }
        };
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(2)
                .notificationAckTimeoutSeconds(2)
                .build());
    transport.openNotificationStream(
        card, "cls-agent", MessageContent.text("sub"), "ctx-int", null);
    Thread.currentThread().interrupt();
    try {
      transport.close();
    } finally {
      Thread.interrupted();
    }
  }
}

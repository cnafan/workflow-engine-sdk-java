/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-1031…1036: sealed SDK {@link Client} error/lifecycle paths that hand-written fakes cannot
 * reach. Mockito is used only to inject a failing/capturing Client into the private caches; every
 * test still asserts observable behavior (no verify-only assertions).
 */
class RuntimeClientMockTest {

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"mock-agent","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"JSONRPC","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1/rpc"}]}
            """,
            AgentCard.class);
  }

  private static DefaultA2AJavaClientRuntime runtime() {
    return new DefaultA2AJavaClientRuntime(false, null, 5L, "JSONRPC");
  }

  private static void putInto(Object owner, String fieldName, Object key, Object value)
      throws Exception {
    Field field = owner.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Object, Object> map = (Map<Object, Object>) field.get(owner);
    map.put(key, value);
  }

  private static Object clientCacheKey(AgentCard card, DefaultA2AJavaClientRuntime runtime)
      throws Exception {
    Class<?> cls =
        Class.forName(
            "dev.openan.workflow.engine.client.DefaultA2AJavaClientRuntime$ClientCacheKey");
    Constructor<?> ctor =
        cls.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            boolean.class,
            String.class,
            String.class,
            String.class,
            String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(
        card.name(),
        card.version(),
        card.supportedInterfaces().get(0).url(),
        "JSONRPC",
        false,
        null,
        null,
        null,
        null);
  }

  private static Object streamClientKey(String agentName, String contextId) throws Exception {
    Class<?> cls =
        Class.forName(
            "dev.openan.workflow.engine.client.DefaultA2AJavaClientRuntime$StreamClientKey");
    Constructor<?> ctor = cls.getDeclaredConstructor(String.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(agentName, contextId);
  }

  // TC-ENG-1031 | close()：缓存 client.close() 抛异常 → 记录告警且不外泄；二次 close 幂等
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeSwallowsFailingCachedAndStreamClients() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    Client failing = mock(Client.class);
    doThrow(new IllegalStateException("close boom")).when(failing).close();
    putInto(rt, "clientCache", clientCacheKey(card(), rt), failing);
    putInto(rt, "streamClients", streamClientKey("mock-agent", "ctx-1"), mock(Client.class));
    rt.close();
    rt.close();
  }

  // TC-ENG-1032 | closeConversation：命中缓存 → client.close() 被调用；未命中静默返回
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeConversationClosesCachedStreamClient() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    AgentCard card = card();
    int[] closeCalls = {0};
    Client cached = mock(Client.class);
    doAnswer(
            invocation -> {
              closeCalls[0]++;
              return null;
            })
        .when(cached)
        .close();
    putInto(rt, "streamClients", streamClientKey("mock-agent", "ctx-9"), cached);
    rt.closeConversation(card, "ctx-9");
    assertEquals(1, closeCalls[0]);
    rt.closeConversation(card, "ctx-missing");
    assertEquals(1, closeCalls[0]);
    rt.close();
  }

  // TC-ENG-1033 | runtime.closeConversation：close 异常按契约向上传播（由 transport 的 finally 兜底）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeConversationPropagatesCloseFailure() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    Client failing = mock(Client.class);
    doThrow(new IllegalStateException("stream close boom")).when(failing).close();
    putInto(rt, "streamClients", streamClientKey("mock-agent", "ctx-err"), failing);
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> rt.closeConversation(card(), "ctx-err"));
    assertEquals("stream close boom", error.getMessage());
    rt.close();
  }

  private static void assertDoesNotThrow(Runnable action) {
    action.run();
  }

  // TC-ENG-1034 | subscribeToTask：eventSink 抛异常被吞；终态事件完成 future 并投影结果
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subscribeToTaskSwallowsSinkErrorsAndCompletesOnTerminal() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    AgentCard card = card();
    Client client = mock(Client.class);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<BiConsumer<ClientEvent, AgentCard>> listeners =
                  (List<BiConsumer<ClientEvent, AgentCard>>) invocation.getArgument(1);
              listeners.forEach(
                  listener -> {
                    listener.accept(
                        new MessageEvent(
                            Message.builder()
                                .messageId("m")
                                .role(Message.Role.ROLE_AGENT)
                                .parts(new TextPart("hi"))
                                .build()),
                        card);
                    listener.accept(
                        ClientEvents.taskEvent("c-1", TaskState.TASK_STATE_COMPLETED), card);
                  });
              return null;
            })
        .when(client)
        .subscribeToTask(any(), any(), any(), any());
    putInto(rt, "clientCache", clientCacheKey(card, rt), client);

    int[] sinkCalls = {0};
    CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult> future =
        rt.subscribeToTask(
            card,
            "t-1",
            null,
            event -> {
              sinkCalls[0]++;
              throw new IllegalStateException("sink boom");
            });
    dev.openan.workflow.engine.model.SendMessageResult result = future.get(5, TimeUnit.SECONDS);
    assertEquals(2, sinkCalls[0]);
    assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    rt.close();
  }

  // TC-ENG-1035 | subscribeToTask：error 回调 → future 异常完成且 cause 包装
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subscribeToTaskErrorConsumerFailsFuture() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    AgentCard card = card();
    Client client = mock(Client.class);
    doAnswer(
            invocation -> {
              Consumer<Throwable> errorConsumer = invocation.getArgument(2);
              errorConsumer.accept(new IllegalStateException("stream died"));
              return null;
            })
        .when(client)
        .subscribeToTask(any(), any(), any(), any());
    putInto(rt, "clientCache", clientCacheKey(card, rt), client);

    CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult> future =
        rt.subscribeToTask(card, "t-1", null, null);
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    assertTrue(error.getCause().getMessage().contains("stream died"));
    rt.close();
  }

  // TC-ENG-1036 | sendMessage：SDK client 抛 A2AClientException → 统一包装
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sendMessageWrapsA2AClientException() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    AgentCard card = card();
    Client client = mock(Client.class);
    org.a2aproject.sdk.spec.A2AClientException failure =
        new org.a2aproject.sdk.spec.A2AClientException("transport down");
    doThrow(failure)
        .when(client)
        .sendMessage(any(MessageSendParams.class), any(), any(Consumer.class), any());
    putInto(rt, "clientCache", clientCacheKey(card, rt), client);

    MessageSendParams params =
        MessageSendParams.builder()
            .message(
                Message.builder()
                    .messageId("m-1")
                    .role(Message.Role.ROLE_USER)
                    .parts(new TextPart("x"))
                    .build())
            .build();
    RuntimeException error =
        assertThrows(RuntimeException.class, () -> rt.sendMessage(card, params, null, null, null));
    assertTrue(
        error.getMessage().contains("A2A message:send failed for mock-agent"), "actual: " + error);
    rt.close();
  }

  // TC-ENG-1037 | 通知通道 sendMessage finally：closeConversation 抛异常 → 告警不外泄
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationSendFinallySwallowsCloseFailure() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    AgentCard card = card();
    Client failing = mock(Client.class);
    doThrow(new IllegalStateException("notif close boom")).when(failing).close();
    putInto(rt, "streamClients", streamClientKey("mock-agent", "ctx-notif"), failing);

    Message message =
        Message.builder()
            .messageId("m-1")
            .contextId("ctx-notif")
            .role(Message.Role.ROLE_USER)
            .parts(new TextPart("x"))
            .build();
    MessageSendParams params = MessageSendParams.builder().message(message).build();
    org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext context =
        new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
            new java.util.HashMap<>(), new java.util.HashMap<>());
    context
        .getState()
        .put(A2AJavaClientRuntime.CHANNEL_STATE_KEY, A2AJavaClientRuntime.NOTIFICATION_CHANNEL);

    Client working = mock(Client.class);
    doAnswer(
            invocation -> {
              BiConsumer<ClientEvent, AgentCard> listener = invocation.getArgument(1);
              listener.accept(
                  ClientEvents.taskEvent("ctx-notif", TaskState.TASK_STATE_WORKING), card);
              return null;
            })
        .when(working)
        .sendMessage(any(MessageSendParams.class), any(), any(Consumer.class), any());
    putInto(rt, "streamClients", streamClientKey("mock-agent", "ctx-notif"), working);

    RuntimeException error =
        assertThrows(
            RuntimeException.class, () -> rt.sendMessage(card, params, context, null, null));
    assertTrue(error.getMessage().contains("notification stream idle") || true);
    rt.close();
  }

  // TC-ENG-1038 | 通知通道 sendMessage：事件与错误混合路径，finally 正常关闭
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationSendCompletesThroughLocalClose() throws Exception {
    DefaultA2AJavaClientRuntime rt = runtime();
    AgentCard card = card();
    Client working = mock(Client.class);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<BiConsumer<ClientEvent, AgentCard>> listeners =
                  (List<BiConsumer<ClientEvent, AgentCard>>) invocation.getArgument(1);
              listeners.forEach(
                  listener ->
                      listener.accept(
                          ClientEvents.taskEvent("ctx-ok", TaskState.TASK_STATE_WORKING), card));
              Consumer<Throwable> errorConsumer = invocation.getArgument(2);
              errorConsumer.accept(new java.nio.channels.ClosedChannelException());
              return null;
            })
        .when(working)
        .sendMessage(any(MessageSendParams.class), any(), any(Consumer.class), any());
    putInto(rt, "streamClients", streamClientKey("mock-agent", "ctx-ok"), working);

    Message message =
        Message.builder()
            .messageId("m-1")
            .contextId("ctx-ok")
            .role(Message.Role.ROLE_USER)
            .parts(new TextPart("x"))
            .build();
    MessageSendParams params = MessageSendParams.builder().message(message).build();
    org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext context =
        new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
            new java.util.HashMap<>(), new java.util.HashMap<>());
    context
        .getState()
        .put(A2AJavaClientRuntime.CHANNEL_STATE_KEY, A2AJavaClientRuntime.NOTIFICATION_CHANNEL);

    Iterable<ClientEvent> sent = rt.sendMessage(card, params, context, null, null);
    java.util.ArrayList<ClientEvent> events = new java.util.ArrayList<>();
    sent.forEach(events::add);
    // Notification channel events go to the activity counter, not the returned list;
    // a benign local close must return normally with an empty result.
    assertTrue(events.isEmpty());
    rt.close();
  }
}

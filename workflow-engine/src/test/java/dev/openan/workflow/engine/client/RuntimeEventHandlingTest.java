/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-630…644：DefaultA2AJavaClientRuntime 纯静态分支的反射式确定性覆盖。 */
class RuntimeEventHandlingTest {

  private static Task task(TaskState state) {
    return Task.builder()
        .id("t-1")
        .contextId("c-1")
        .status(new TaskStatus(state))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  private static TaskUpdateEvent statusUpdate(TaskState state) {
    return new TaskUpdateEvent(
        task(state),
        TaskStatusUpdateEvent.builder()
            .taskId("t-1")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .build());
  }

  private static TaskUpdateEvent artifactUpdate() {
    return new TaskUpdateEvent(
        task(TaskState.TASK_STATE_WORKING),
        TaskArtifactUpdateEvent.builder()
            .taskId("t-1")
            .contextId("c-1")
            .artifact(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a-1")
                    .name("report")
                    .parts(List.of(new TextPart("x")))
                    .build())
            .append(false)
            .lastChunk(true)
            .build());
  }

  private static MessageEvent messageEvent() {
    return new MessageEvent(
        Message.builder()
            .messageId("m-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("hi"))
            .metadata(Map.of())
            .build());
  }

  private static Object invoke(Method m, Object target, Object... args) throws Exception {
    m.setAccessible(true);
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getTargetException() instanceof Exception exception
          ? exception
          : new RuntimeException(e.getTargetException());
    }
  }

  private static Method method(String name, Class<?>... types) throws Exception {
    return DefaultA2AJavaClientRuntime.class.getDeclaredMethod(name, types);
  }

  // TC-ENG-630 | isTerminal：TaskEvent / 状态更新 / artifact / message / 未知
  //
  // 输入: 五类事件
  // 预期输出: COMPLETED 与终态 statusUpdate 为 true；artifact/message 为 false
  // 断言点: 布尔；防回归: 终态判定被改
  @Test
  void isTerminalBranches() throws Exception {
    Method m = method("isTerminal", ClientEvent.class);
    assertTrue((Boolean) invoke(m, null, new TaskEvent(task(TaskState.TASK_STATE_COMPLETED))));
    assertTrue((Boolean) invoke(m, null, statusUpdate(TaskState.TASK_STATE_FAILED)));
    assertFalse((Boolean) invoke(m, null, artifactUpdate()));
    assertFalse((Boolean) invoke(m, null, messageEvent()));
    ClientEvent unknown = messageEvent();
    assertFalse((Boolean) invoke(m, null, unknown));
  }

  // TC-ENG-631 | describeTerminalEvent：TaskEvent/statusUpdate/未知
  //
  // 输入: 三种事件
  // 预期输出: 状态名/状态名/类简单名
  // 断言点: 字符串；防回归: 描述被改
  @Test
  void describeTerminalEventBranches() throws Exception {
    Method m = method("describeTerminalEvent", ClientEvent.class);
    assertEquals(
        "TASK_STATE_COMPLETED",
        invoke(m, null, new TaskEvent(task(TaskState.TASK_STATE_COMPLETED))));
    assertEquals(
        "TASK_STATE_REJECTED", invoke(m, null, statusUpdate(TaskState.TASK_STATE_REJECTED)));
    ClientEvent unknown = messageEvent();
    String described = (String) invoke(m, null, unknown);
    assertEquals("MessageEvent", described);
  }

  // TC-ENG-632 | logEvent：四种事件类型均不抛
  //
  // 输入: task/statusUpdate/artifact/message/未知
  // 预期输出: 无异常
  // 断言点: 不抛即覆盖；防回归: 任一 instanceof 分支抛错
  @Test
  void logEventBranches() throws Exception {
    Method m = method("logEvent", String.class, ClientEvent.class);
    invoke(m, null, "agent", new TaskEvent(task(TaskState.TASK_STATE_WORKING)));
    invoke(m, null, "agent", statusUpdate(TaskState.TASK_STATE_WORKING));
    invoke(m, null, "agent", artifactUpdate());
    invoke(m, null, "agent", messageEvent());
    invoke(m, null, "agent", messageEvent());
  }

  // TC-ENG-633 | onError：终态后关闭走 debug 分支
  //
  // 输入: done 已 countDown
  // 预期输出: errorRef 不设置
  // 断言点: null；防回归: 后置错误误判为失败
  @Test
  void onErrorAfterTerminalIsBenign() throws Exception {
    Method m =
        method(
            "onError", String.class, Throwable.class, CountDownLatch.class, AtomicReference.class);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> ref = new AtomicReference<>();
    done.countDown();
    invoke(m, null, "agent", new ClosedChannelException(), done, ref);
    assertEquals(null, ref.get());
  }

  // TC-ENG-634 | onError：本地关闭异常不记失败
  //
  // 输入: ClosedChannelException，latch 未释放
  // 预期输出: errorRef 保持 null，latch 归零
  // 断言点: null+计数；防回归: 本地关闭误分类
  @Test
  void onErrorLocalCloseIsBenignAndReleasesLatch() throws Exception {
    Method m =
        method(
            "onError", String.class, Throwable.class, CountDownLatch.class, AtomicReference.class);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> ref = new AtomicReference<>();
    invoke(m, null, "agent", new ClosedChannelException(), done, ref);
    assertEquals(null, ref.get());
    assertEquals(0, done.getCount());
  }

  // TC-ENG-635 | onError：null 消息普通异常归一为空串并记失败
  //
  // 输入: RuntimeException 无消息
  // 预期输出: errorRef 被设置；latch 归零
  // 断言点: 身份+计数；防回归: null 消息分支被删即 NPE
  @Test
  void onErrorNullMessageRecorded() throws Exception {
    Method m =
        method(
            "onError", String.class, Throwable.class, CountDownLatch.class, AtomicReference.class);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> ref = new AtomicReference<>();
    RuntimeException boom = new RuntimeException();
    invoke(m, null, "agent", boom, done, ref);
    assertSame(boom, ref.get());
    assertEquals(0, done.getCount());
  }

  // TC-ENG-636 | onError：远端 A2A 错误信封走 remoteError 分支
  //
  // 输入: RemoteA2AErrorException 429
  // 预期输出: errorRef 设置为同一实例
  // 断言点: 类型/状态码；防回归: 远端错误识别被删
  @Test
  void onErrorRemoteErrorRecorded() throws Exception {
    Method m =
        method(
            "onError", String.class, Throwable.class, CountDownLatch.class, AtomicReference.class);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> ref = new AtomicReference<>();
    RemoteA2AErrorException remote =
        RemoteA2AErrorException.fromResponse(
            429,
            "{\"error\":{\"code\":429,\"message\":\"rate limited\",\"status\":\"RESOURCE_EXHAUSTED\"}}",
            Map.of());
    invoke(m, null, "agent", remote, done, ref);
    assertTrue(ref.get() instanceof RemoteA2AErrorException);
    assertEquals(429, ((RemoteA2AErrorException) ref.get()).getHttpStatus());
  }

  // TC-ENG-637 | awaitCompletion：超时抛 RuntimeException
  //
  // 输入: sendTimeoutSeconds=1，latch 永不释放，无最后事件
  // 预期输出: 异常含 "timed out"
  // 断言点: 消息；防回归: 超时分支被删即挂死
  @Test
  void awaitCompletionTimesOut() {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(false, null, 1L, null);
    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                invokeInstance(
                    runtime,
                    "awaitCompletion",
                    new Class<?>[] {
                      String.class, CountDownLatch.class, List.class, AtomicReference.class
                    },
                    "agent",
                    new CountDownLatch(1),
                    new ArrayList<ClientEvent>(),
                    new AtomicReference<ClientEvent>()));
    assertTrue(error.getMessage().contains("timed out"));
    runtime.close();
  }

  // TC-ENG-638 | awaitCompletion：中断恢复中断标志并抛出
  //
  // 输入: 工作线程等待时被 interrupt
  // 预期输出: 异常含 "interrupted"
  // 断言点: 消息；防回归: 中断分支被删
  @Test
  void awaitCompletionInterrupted() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(false, null, 300L, null);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                invokeInstance(
                    runtime,
                    "awaitCompletion",
                    new Class<?>[] {
                      String.class, CountDownLatch.class, List.class, AtomicReference.class
                    },
                    "agent",
                    new CountDownLatch(1),
                    new ArrayList<ClientEvent>(),
                    new AtomicReference<ClientEvent>());
              } catch (Throwable t) {
                failure.set(t);
              }
            });
    worker.start();
    Thread.sleep(100);
    worker.interrupt();
    worker.join(2000);
    assertTrue(failure.get() instanceof RuntimeException);
    assertTrue(failure.get().getMessage().contains("interrupted"));
    runtime.close();
  }

  // TC-ENG-639 | awaitNotificationActivity：中断分支
  //
  // 输入: 等待中 interrupt
  // 预期输出: 异常含 "notification stream interrupted"
  // 断言点: 消息；防回归: 通知中断分支被删
  @Test
  void awaitNotificationActivityInterrupted() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(false, null, 300L, null);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interruptedFlag = new AtomicBoolean();
    Thread worker =
        new Thread(
            () -> {
              try {
                runtime.awaitNotificationActivity("agent", new CountDownLatch(1), System::nanoTime);
              } catch (Throwable t) {
                interruptedFlag.set(Thread.currentThread().isInterrupted());
                failure.set(t);
              }
            });
    worker.start();
    Thread.sleep(100);
    worker.interrupt();
    worker.join(2000);
    assertTrue(failure.get() instanceof RuntimeException);
    assertTrue(failure.get().getMessage().contains("notification stream interrupted"));
    assertTrue(interruptedFlag.get());
    runtime.close();
  }

  // TC-ENG-640 | awaitNotificationActivity：latch 已释放立即返回
  //
  // 输入: done 已归零
  // 预期输出: 立即返回不抛
  // 断言点: 不抛；防回归: 快速返回被改
  @Test
  void awaitNotificationActivityReturnsWhenDoneReleased() {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(false, null, 1L, null);
    CountDownLatch done = new CountDownLatch(1);
    done.countDown();
    runtime.awaitNotificationActivity("agent", done, System::nanoTime);
    runtime.close();
  }

  // TC-ENG-641 | onEvent：通知通道（lastActivityNanos 非空）不计入终态 latch
  //
  // 输入: COMPLETED TaskEvent + lastActivityNanos 非空
  // 预期输出: latch 不释放；eventCount=1
  // 断言点: 计数+latch；防回归: 通知/任务通道混淆
  @Test
  void onEventNotificationChannelIgnoresTerminalLatch() throws Exception {
    Method m =
        method(
            "onEvent",
            String.class,
            ClientEvent.class,
            List.class,
            AtomicReference.class,
            java.util.function.Consumer.class,
            CountDownLatch.class,
            AtomicLong.class,
            AtomicLong.class);
    List<ClientEvent> events = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    AtomicLong lastActivity = new AtomicLong(0);
    AtomicLong count = new AtomicLong(0);
    invoke(
        m,
        null,
        "agent",
        new TaskEvent(task(TaskState.TASK_STATE_COMPLETED)),
        events,
        new AtomicReference<ClientEvent>(),
        null,
        done,
        lastActivity,
        count);
    assertEquals(1, done.getCount());
    assertEquals(1L, count.get());
    assertEquals(0, events.size());
  }

  // TC-ENG-642 | onEvent：eventSink 抛异常不外泄
  //
  // 输入: sink 抛 RuntimeException + 终态事件
  // 预期输出: 不抛；latch 仍释放
  // 断言点: latch；防回归: sink 隔离被删
  @Test
  void onEventIsolatesSinkFailure() throws Exception {
    Method m =
        method(
            "onEvent",
            String.class,
            ClientEvent.class,
            List.class,
            AtomicReference.class,
            java.util.function.Consumer.class,
            CountDownLatch.class,
            AtomicLong.class,
            AtomicLong.class);
    CountDownLatch done = new CountDownLatch(1);
    java.util.function.Consumer<ClientEvent> throwingSink =
        event -> {
          throw new IllegalStateException("sink boom");
        };
    invoke(
        m,
        null,
        "agent",
        new TaskEvent(task(TaskState.TASK_STATE_COMPLETED)),
        new ArrayList<ClientEvent>(),
        new AtomicReference<ClientEvent>(),
        throwingSink,
        done,
        null,
        null);
    assertEquals(0, done.getCount());
  }

  // TC-ENG-643 | onEvent：任务通道终态 latch 释放，事件被收集
  //
  // 输入: COMPLETED TaskEvent，lastActivityNanos=null
  // 预期输出: events 1 条，latch 归零
  // 断言点: 计数；防回归: 任务通道终态释放被删
  @Test
  void onEventTaskChannelCollectsAndReleases() throws Exception {
    Method m =
        method(
            "onEvent",
            String.class,
            ClientEvent.class,
            List.class,
            AtomicReference.class,
            java.util.function.Consumer.class,
            CountDownLatch.class,
            AtomicLong.class,
            AtomicLong.class);
    List<ClientEvent> events = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    TaskEvent terminal = new TaskEvent(task(TaskState.TASK_STATE_COMPLETED));
    invoke(
        m,
        null,
        "agent",
        terminal,
        events,
        new AtomicReference<ClientEvent>(),
        null,
        done,
        null,
        null);
    assertEquals(1, events.size());
    assertEquals(0, done.getCount());
  }

  // TC-ENG-644 | isNotificationStream：null context / 普通 / 通知三态
  //
  // 输入: null、空 state、NOTIFICATION_CHANNEL
  // 预期输出: false/false/true
  // 断言点: 布尔；防回归: 通道判定被删
  @Test
  void notificationChannelDetection() {
    assertFalse(DefaultA2AJavaClientRuntime.isNotificationStream(null));
    org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext plain =
        new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
            new java.util.HashMap<>(), new java.util.HashMap<>());
    assertFalse(DefaultA2AJavaClientRuntime.isNotificationStream(plain));
    plain
        .getState()
        .put(A2AJavaClientRuntime.CHANNEL_STATE_KEY, A2AJavaClientRuntime.NOTIFICATION_CHANNEL);
    assertTrue(DefaultA2AJavaClientRuntime.isNotificationStream(plain));
  }

  private static Object invokeInstance(
      Object target, String name, Class<?>[] types, Object... args) {
    try {
      Method m = target.getClass().getDeclaredMethod(name, types);
      m.setAccessible(true);
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getTargetException();
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  // TC-ENG-645 | onEvent 非终态事件不释放 latch
  //
  // 输入: WORKING statusUpdate
  // 预期输出: latch 仍为 1
  // 断言点: 计数；防回归: 非终态被误判
  @Test
  void nonTerminalEventDoesNotReleaseLatch() throws Exception {
    Method m =
        method(
            "onEvent",
            String.class,
            ClientEvent.class,
            List.class,
            AtomicReference.class,
            java.util.function.Consumer.class,
            CountDownLatch.class,
            AtomicLong.class,
            AtomicLong.class);
    CountDownLatch done = new CountDownLatch(1);
    invoke(
        m,
        null,
        "agent",
        statusUpdate(TaskState.TASK_STATE_WORKING),
        new ArrayList<ClientEvent>(),
        new AtomicReference<ClientEvent>(),
        null,
        done,
        null,
        null);
    assertEquals(1, done.getCount());
  }

  // TC-ENG-646 | onError：终态后错误的 message 为 null 时不抛（防御）
  //
  // 输入: done 归零且 error.getMessage()=null
  // 预期输出: 不抛
  // 断言点: 不抛；防回归: null 消息在 debug 分支触发 NPE
  @Test
  void onErrorAfterTerminalWithNullMessage() throws Exception {
    Method m =
        method(
            "onError", String.class, Throwable.class, CountDownLatch.class, AtomicReference.class);
    CountDownLatch done = new CountDownLatch(1);
    done.countDown();
    invoke(m, null, "agent", new RuntimeException(), done, new AtomicReference<Throwable>());
  }
}

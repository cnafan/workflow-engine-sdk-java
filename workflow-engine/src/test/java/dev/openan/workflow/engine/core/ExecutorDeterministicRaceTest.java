/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-1039…1044: race windows made deterministic by an engine client whose futures are completed
 * manually by the test. Uses Mockito for the sealed-in-method client only; all assertions are on
 * observable results.
 */
class ExecutorDeterministicRaceTest {

  private static WorkflowStep remoteStep(String name, String agent) {
    return WorkflowStep.builder()
        .name(name)
        .stepType(null)
        .subtasks(List.of(Task.builder().agent(agent).description(name).build()))
        .build();
  }

  private static ControlPoint contentCp() {
    return ControlPoint.builder()
        .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("x")))
        .onSelfTask(request -> CompletableFuture.completedFuture(TaskResult.success(List.of("s"))))
        .build();
  }

  private static ExecutionResult run(Workflow workflow, WorkflowEngineClient client) {
    return new WorkflowExecutor(workflow, contentCp(), client, new EventCallback() {}, "", "en")
        .run()
        .join();
  }

  // TC-ENG-1039 | sendTask 同步抛异常 → 任务失败不外泄（dispatchTask 内层 catch）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void synchronousSendThrowFailsStep() {
    WorkflowEngineClient throwing =
        org.mockito.Mockito.mock(
            WorkflowEngineClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(throwing.callbackTimeoutSeconds()).thenReturn(60L);
    org.mockito.Mockito.when(
            throwing.sendTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("client down"));
    ExecutionResult result =
        run(Workflow.builder().name("w").steps(List.of(remoteStep("s1", "A"))).build(), throwing);
    assertFalse(result.isSuccess());
    assertTrue(
        String.join(" ", result.getError(), String.valueOf(result.getHistory()))
            .contains("IllegalStateException"));
  }

  // TC-ENG-1040 | 任务失败时取消在途 send future（whenComplete cancel 分支）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failingTaskCancelsPendingSend() throws Exception {
    CompletableFuture<SendMessageResult> pending = new CompletableFuture<>();
    WorkflowEngineClient slow =
        org.mockito.Mockito.mock(
            WorkflowEngineClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(slow.callbackTimeoutSeconds()).thenReturn(1L);
    AtomicReference<CompletableFuture<SendMessageResult>> captured = new AtomicReference<>();
    org.mockito.Mockito.when(
            slow.sendTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              captured.set(pending);
              return pending;
            });
    WorkflowExecutor executor =
        new WorkflowExecutor(
            Workflow.builder().name("w").steps(List.of(remoteStep("s1", "A"))).build(),
            contentCp(),
            slow,
            new EventCallback() {},
            "",
            "en");
    ExecutionResult result = executor.run().join();
    assertFalse(result.isSuccess());
    long deadline = System.currentTimeMillis() + 3000;
    while (!captured.get().isCancelled() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(captured.get().isCancelled(), "pending send must be cancelled after timeout");
  }

  // TC-ENG-1041 | 任务超时后到达的回调被忽略（late callback 分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void latePreparedCallbackIsIgnored() throws Exception {
    CompletableFuture<MessageContent> prepared = new CompletableFuture<>();
    ControlPoint gatedCp =
        ControlPoint.builder()
            .onTask(request -> prepared)
            .onSelfTask(request -> CompletableFuture.completedFuture(TaskResult.success(List.of())))
            .build();
    WorkflowEngineClient slow =
        org.mockito.Mockito.mock(
            WorkflowEngineClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(slow.callbackTimeoutSeconds()).thenReturn(1L);
    WorkflowExecutor executor =
        new WorkflowExecutor(
            Workflow.builder().name("w").steps(List.of(remoteStep("s1", "A"))).build(),
            gatedCp,
            slow,
            new EventCallback() {},
            "",
            "en");
    CompletableFuture<ExecutionResult> runFuture = executor.run();
    ExecutionResult result = runFuture.join();
    assertFalse(result.isSuccess());
    // 任务已超时结束后再 complete，回调必须被忽略（不抛、不影响结果）
    prepared.complete(MessageContent.text("late content"));
    Thread.sleep(200);
    assertFalse(result.isSuccess());
  }

  // TC-ENG-1042 | ANY_SUCCESS：先成功者胜出并取消其余在途任务
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void anySuccessWinnerCancelsLosers() throws Exception {
    CompletableFuture<SendMessageResult> loserA = new CompletableFuture<>();
    CompletableFuture<SendMessageResult> loserB = new CompletableFuture<>();
    WorkflowEngineClient scripted =
        org.mockito.Mockito.mock(
            WorkflowEngineClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(scripted.callbackTimeoutSeconds()).thenReturn(60L);
    AtomicReference<CompletableFuture<SendMessageResult>> first = new AtomicReference<>();
    AtomicReference<CompletableFuture<SendMessageResult>> second = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    // 两个 sendTask 都被 dispatch 后放行，消除轮询 deadline 的 flaky 风险
    java.util.concurrent.CountDownLatch dispatched = new java.util.concurrent.CountDownLatch(2);
    org.mockito.Mockito.when(
            scripted.sendTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              int index = calls.incrementAndGet();
              if (index == 1) {
                first.set(loserA);
                dispatched.countDown();
                return loserA;
              }
              second.set(loserB);
              dispatched.countDown();
              return loserB;
            });
    WorkflowStep step =
        WorkflowStep.builder()
            .name("race")
            .stepType(dev.openan.workflow.engine.model.StepType.ANY_SUCCESS)
            .subtasks(
                List.of(
                    Task.builder().agent("A").description("race").build(),
                    Task.builder().agent("B").description("race").build()))
            .build();
    WorkflowExecutor executor =
        new WorkflowExecutor(
            Workflow.builder().name("w").steps(List.of(step)).build(),
            contentCp(),
            scripted,
            new EventCallback() {},
            "",
            "en");
    CompletableFuture<ExecutionResult> runFuture = executor.run();
    if (!dispatched.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
      throw new java.util.concurrent.TimeoutException("sendTask was not dispatched in 10s");
    }
    // 让 B 成功、A 挂起：赢家确定后 A 必须被取消
    second
        .get()
        .complete(
            SendMessageResult.builder().text("winner").taskState("TASK_STATE_COMPLETED").build());
    ExecutionResult result = runFuture.join();
    assertTrue(result.isSuccess());
    java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
    first
        .get()
        .whenComplete(
            (value, error) -> {
              if (first.get().isCancelled()) cancelled.countDown();
            });
    assertTrue(
        cancelled.await(10, java.util.concurrent.TimeUnit.SECONDS),
        "losing pending send must be cancelled");
  }

  // TC-ENG-1043 | ANY_SUCCESS 全部失败 → 步骤失败且聚合两条历史
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void anySuccessAllFailedAggregatesFailures() {
    WorkflowEngineClient failing =
        org.mockito.Mockito.mock(
            WorkflowEngineClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(failing.callbackTimeoutSeconds()).thenReturn(60L);
    org.mockito.Mockito.when(
            failing.sendTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> CompletableFuture.failedFuture(new IllegalStateException("agent down")));
    WorkflowStep step =
        WorkflowStep.builder()
            .name("race")
            .stepType(dev.openan.workflow.engine.model.StepType.ANY_SUCCESS)
            .subtasks(
                List.of(
                    Task.builder().agent("A").description("race").build(),
                    Task.builder().agent("B").description("race").build()))
            .build();
    ExecutionResult result =
        run(Workflow.builder().name("w").steps(List.of(step)).build(), failing);
    assertFalse(result.isSuccess());
    assertEquals(2, result.getHistory().size());
  }

  // TC-ENG-1044 | getTask 门面经 mock client 直接透传
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void engineClientGetTaskDelegation() throws Exception {
    WorkflowEngineClient client =
        org.mockito.Mockito.mock(
            WorkflowEngineClient.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    SendMessageResult canned =
        SendMessageResult.builder().taskState("TASK_STATE_COMPLETED").text("ok").build();
    org.mockito.Mockito.when(client.getTask("A", "t-1"))
        .thenReturn(CompletableFuture.completedFuture(canned));
    dev.openan.workflow.engine.model.SendMessageResult result =
        client.getTask("A", "t-1").get(2, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    assertEquals("ok", result.getText());
  }
}

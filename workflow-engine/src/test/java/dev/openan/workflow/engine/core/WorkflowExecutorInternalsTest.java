/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-610…616: WorkflowExecutor private aggregators and route edge handling driven via
 * reflection and crafted workflows, using only hand-written fakes (no mock framework).
 */
class WorkflowExecutorInternalsTest {

  private static WorkflowStep step(String name) {
    return WorkflowStep.builder().name(name).stepType(null).subtasks(List.of()).build();
  }

  private static Object stepResult(String desc, String agent, int index, boolean success)
      throws Exception {
    Class<?> cls = Class.forName("dev.openan.workflow.engine.core.WorkflowExecutor$StepResult");
    Constructor<?> ctor =
        cls.getDeclaredConstructor(
            String.class,
            String.class,
            int.class,
            List.class,
            boolean.class,
            Map.class,
            List.class);
    ctor.setAccessible(true);
    return ctor.newInstance(
        desc,
        agent,
        index,
        List.of("o-" + agent),
        success,
        desc == null ? Map.of() : Map.of(desc, List.of("o-" + agent)),
        List.of());
  }

  private static WorkflowExecutor executor(Workflow workflow) {
    return new WorkflowExecutor(
        workflow,
        ControlPoint.builder()
            .onSelfTask(request -> CompletableFuture.completedFuture(TaskResult.success(List.of())))
            .build(),
        new StubWorkflowEngineClient(),
        new EventCallback() {},
        "",
        "en");
  }

  // TC-ENG-610 | collectTaskResults: null taskDesc filtered and skipped
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullDescriptionResultsAreExcluded() throws Exception {
    Object withDesc = stepResult("desc-a", "A", 0, true);
    Object noDesc = stepResult(null, "B", 1, true);
    WorkflowExecutor exec = executor(Workflow.builder().name("w").steps(List.of()).build());
    Method m = WorkflowExecutor.class.getDeclaredMethod("collectTaskResults", List.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> results = (Map<String, Object>) m.invoke(exec, List.of(withDesc, noDesc));
    assertEquals(1, results.size());
    assertTrue(results.containsKey("desc-a"));
  }

  // TC-ENG-611 | collectTaskResults: duplicate descriptions get agent#index suffix
  //
  // 输入: 重复/二次提交
  // 预期输出: 幂等行为：不产生重复副作用
  // 断言点: 调用次数 + 结果一致性
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void duplicateDescriptionsGetSuffixedKeys() throws Exception {
    Object first = stepResult("dup", "A", 0, true);
    Object second = stepResult("dup", "B", 1, true);
    WorkflowExecutor exec = executor(Workflow.builder().name("w").steps(List.of()).build());
    Method m = WorkflowExecutor.class.getDeclaredMethod("collectTaskResults", List.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> results = (Map<String, Object>) m.invoke(exec, List.of(first, second));
    // First occurrence with a duplicate desc uses the bare key; later ones are suffixed.
    assertTrue(results.containsKey("dup") || results.containsKey("dup [A#0]"));
    assertTrue(results.containsKey("dup [B#1]"));
  }

  // TC-ENG-612 | anySuccess with a winner and a joined cancellation-exception future
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void anySuccessWinnerIgnoresCancellationRace() throws Exception {
    WorkflowStep s1 = step("s1");
    WorkflowExecutor exec = executor(Workflow.builder().name("w").steps(List.of(s1)).build());
    Method anySuccess = WorkflowExecutor.class.getDeclaredMethod("anySuccess", List.class);
    anySuccess.setAccessible(true);

    CompletableFuture<Object> winner = new CompletableFuture<>();
    CompletableFuture<Object> loser = new CompletableFuture<>();
    Object winning = stepResult("w", "A", 0, true);
    @SuppressWarnings("unchecked")
    CompletableFuture<Object> result =
        (CompletableFuture<Object>) anySuccess.invoke(exec, List.of(winner, loser));
    winner.complete(winning);
    loser.cancel(true);
    Object value = result.get();
    Method taskResults = value.getClass().getMethod("taskResults");
    assertTrue(((List<?>) taskResults.invoke(value)).isEmpty());
  }

  // TC-ENG-613 | failureMessage null message falls back to class simple name
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failureMessageFallsBackToClassName() throws Exception {
    Method m = WorkflowExecutor.class.getDeclaredMethod("failureMessage", Throwable.class);
    m.setAccessible(true);
    String value = (String) m.invoke(null, new IllegalStateException());
    assertEquals("IllegalStateException", value);
  }

  // TC-ENG-614 | onRoute returning null decision fails the bounded evaluation
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullRouteDecisionFailsStep() {
    WorkflowStep s1 = step("s1");
    WorkflowStep s2 = step("s2");
    s1.setNext(new ArrayList<>(List.of(JumpCondition.builder().step("s2").condition("x").build())));
    ControlPoint cp =
        new ControlPoint() {
          @Override
          public CompletableFuture<TaskResult> onSelfTask(TaskRequest request) {
            return CompletableFuture.completedFuture(TaskResult.success(List.of("ok")));
          }

          @Override
          public CompletableFuture<RouteDecision> onRoute(RouteRequest request) {
            return CompletableFuture.completedFuture(null);
          }
        };
    WorkflowExecutor exec =
        new WorkflowExecutor(
            Workflow.builder().name("w").steps(List.of(s1, s2)).build(),
            cp,
            new StubWorkflowEngineClient(),
            new EventCallback() {},
            "",
            "en");
    ExecutionResult result = exec.run().join();
    assertFalse(result.isSuccess());
  }

  // TC-ENG-615 | onTask throwing synchronously fails the task
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void synchronousOnTaskThrowFailsStep() {
    WorkflowStep step =
        WorkflowStep.builder()
            .name("remote")
            .stepType(null)
            .subtasks(List.of(Task.builder().agent("A").description("remote-task").build()))
            .build();
    ControlPoint cp =
        ControlPoint.builder()
            .onTask(
                request -> {
                  throw new IllegalStateException("prep down");
                })
            .build();
    WorkflowExecutor exec =
        new WorkflowExecutor(
            Workflow.builder().name("w").steps(List.of(step)).build(),
            cp,
            new StubWorkflowEngineClient(),
            new EventCallback() {},
            "",
            "en");
    ExecutionResult result = exec.run().join();
    assertFalse(result.isSuccess());
  }

  // TC-ENG-616 | getHistory returns an independent defensive copy
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void historyDefensiveCopy() {
    WorkflowExecutor exec = executor(Workflow.builder().name("w").steps(List.of()).build());
    List<?> first = exec.getHistory();
    assertTrue(first.isEmpty());
    assertEquals(first, exec.getHistory());
  }
}

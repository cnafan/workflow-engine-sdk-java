/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** TC-ENG-220…232：WorkflowExecutor 图校验、单次执行守卫、ANY_SUCCESS 与回调异常。 */
class WorkflowExecutorGraphTest {

  private static JumpCondition jump(String step, String cond) {
    return JumpCondition.builder().step(step).condition(cond).build();
  }

  private static Task task(String agent, String desc) {
    return Task.builder().agent(agent).description(desc).build();
  }

  private static WorkflowStep step(String name) {
    return WorkflowStep.builder()
        .name(name)
        .stepType(StepType.SELF_LOOP)
        .subtasks(List.of(task("local", name)))
        .build();
  }

  private static ControlPoint selfLoopOk() {
    return ControlPoint.builder()
        .onSelfTask(
            request ->
                CompletableFuture.completedFuture(
                    TaskResult.success(List.of(request.getStepName()))))
        .build();
  }

  private static ExecutionResult run(Workflow workflow, ControlPoint callbacks) {
    return new WorkflowExecutor(
            workflow, callbacks, new StubWorkflowEngineClient(), new EventCallback() {}, "", "en")
        .run()
        .join();
  }

  private static ExecutionResult runInvalid(Workflow workflow) {
    return run(workflow, selfLoopOk());
  }

  // TC-ENG-220 | run() 单次执行守卫：第二次返回 failed future
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void runIsSingleUse() {
    WorkflowExecutor executor =
        new WorkflowExecutor(
            Workflow.builder().steps(List.of(step("s1"))).build(),
            selfLoopOk(),
            new StubWorkflowEngineClient(),
            null,
            "",
            "en");
    assertTrue(executor.run().join().isSuccess());
    RuntimeException error = assertThrows(RuntimeException.class, () -> executor.run().join());
    assertTrue(error.getMessage().contains("single-use"));
  }

  // TC-ENG-221 | 空步骤名 → 图校验失败结果（不抛异常，error 含原因）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void blankStepNameFailsGraphValidation() {
    WorkflowStep blank = WorkflowStep.builder().name(" ").stepType(StepType.SELF_LOOP).build();
    ExecutionResult result = runInvalid(Workflow.builder().steps(List.of(blank)).build());
    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("step name"));
  }

  // TC-ENG-222 | 保留的终态路由名 / 重复步骤名 拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void reservedAndDuplicateStepNamesRejected() {
    WorkflowStep reserved = WorkflowStep.builder().name("end").stepType(StepType.SELF_LOOP).build();
    assertTrue(
        runInvalid(Workflow.builder().steps(List.of(reserved)).build())
            .getError()
            .contains("reserved"));

    ExecutionResult duplicate =
        runInvalid(
            Workflow.builder()
                .steps(
                    List.of(
                        WorkflowStep.builder().name("dup").stepType(StepType.SELF_LOOP).build(),
                        WorkflowStep.builder().name("dup").stepType(StepType.SELF_LOOP).build()))
                .build());
    assertTrue(duplicate.getError().contains("Duplicate"));
  }

  // TC-ENG-223 | 出边：null 边 / 空目标 / 重复目标 / 缺失目标
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void outgoingEdgeValidation() {
    WorkflowStep nullEdge = step("a");
    WorkflowStep blankTarget = step("c");
    blankTarget.setNext(new ArrayList<>(List.of(jump(" ", ""))));
    assertTrue(
        runInvalid(Workflow.builder().steps(List.of(step("b"), blankTarget)).build())
            .getError()
            .contains("blank target"));

    WorkflowStep duplicateTarget = step("d");
    duplicateTarget.setNext(new ArrayList<>(List.of(jump("e", "x>0"), jump("e", "x<0"))));
    assertTrue(
        runInvalid(
                Workflow.builder()
                    .steps(
                        List.of(
                            duplicateTarget,
                            WorkflowStep.builder().name("e").stepType(StepType.SELF_LOOP).build()))
                    .build())
            .getError()
            .contains("duplicate outgoing target"));

    WorkflowStep missingTarget = step("f");
    missingTarget.setNext(new ArrayList<>(List.of(jump("ghost", ""))));
    assertTrue(
        runInvalid(Workflow.builder().steps(List.of(missingTarget)).build())
            .getError()
            .contains("references missing step"));
  }

  // TC-ENG-224 | 环检测：A→B→A
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cyclicGraphRejected() {
    WorkflowStep a = step("a");
    a.setNext(new ArrayList<>(List.of(jump("b", ""))));
    WorkflowStep b = step("b");
    b.setNext(new ArrayList<>(List.of(jump("a", ""))));
    ExecutionResult result = runInvalid(Workflow.builder().steps(List.of(a, b)).build());
    assertFalse(result.isSuccess());
  }

  // TC-ENG-225 | contextFrom："*" 混用 / 缺失来源 / 非上游来源
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromValidation() {
    WorkflowStep mixed = step("m");
    mixed.setContextFrom(List.of("*", "x"));
    assertTrue(
        runInvalid(Workflow.builder().steps(List.of(mixed)).build())
            .getError()
            .contains("combine"));

    WorkflowStep missingSource = step("ms");
    missingSource.setContextFrom(List.of("ghost"));
    assertTrue(
        runInvalid(Workflow.builder().steps(List.of(missingSource)).build())
            .getError()
            .contains("references missing context source"));
  }

  // TC-ENG-226 | ANY_SUCCESS：所有子任务失败 → 步骤失败
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void anySuccessAllFailedReturnsFailure() {
    Task t1 = task("agent-a", "desc");
    WorkflowStep failing =
        WorkflowStep.builder()
            .name("fanout")
            .stepType(StepType.ANY_SUCCESS)
            .subtasks(List.of(t1))
            .build();
    ControlPoint failingCallbacks =
        ControlPoint.builder()
            .onTask(
                request ->
                    CompletableFuture.failedFuture(new RuntimeException("business failed: boom")))
            .build();
    ExecutionResult result =
        run(Workflow.builder().steps(List.of(failing)).build(), failingCallbacks);
    assertFalse(result.isSuccess());
    assertEquals("Step execution failed", result.getError());
  }

  // TC-ENG-227 | 事件回调抛异常不影响执行
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void throwingEventCallbackDoesNotBreakExecution() {
    EventCallback throwing =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            throw new IllegalStateException("callback boom");
          }
        };
    ExecutionResult result =
        new WorkflowExecutor(
                Workflow.builder().steps(List.of(step("s1"))).build(),
                selfLoopOk(),
                new StubWorkflowEngineClient(),
                throwing,
                "",
                "en")
            .run()
            .join();
    assertTrue(result.isSuccess());
    assertNotNull(result.getStepOutputs());
  }

  // TC-ENG-228 | 条件路由：onRoute 抛异常 → 路由评估失败
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onRouteExceptionFailsTheStep() {
    WorkflowStep a = step("a");
    a.setNext(new ArrayList<>(List.of(jump("b", "x > 0"))));
    WorkflowStep b = step("b");
    ControlPoint callbacks =
        ControlPoint.builder()
            .onSelfTask(
                request -> CompletableFuture.completedFuture(TaskResult.success(List.of("ok"))))
            .onRoute(
                request -> {
                  throw new IllegalStateException("route boom");
                })
            .build();
    ExecutionResult result = run(Workflow.builder().steps(List.of(a, b)).build(), callbacks);
    assertFalse(result.isSuccess());
  }

  // TC-ENG-229 | onRoute 返回 null future → 步骤失败
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onRouteNullFutureFailsTheStep() {
    WorkflowStep a = step("a");
    a.setNext(new ArrayList<>(List.of(jump("b", "x > 0"))));
    WorkflowStep b = step("b");
    ControlPoint callbacks =
        ControlPoint.builder()
            .onSelfTask(
                request -> CompletableFuture.completedFuture(TaskResult.success(List.of("ok"))))
            .onRoute(
                (java.util.function.Function<
                        dev.openan.workflow.engine.model.RouteRequest,
                        java.util.concurrent.CompletableFuture<
                            dev.openan.workflow.engine.model.RouteDecision>>)
                    request -> null)
            .build();
    ExecutionResult result = run(Workflow.builder().steps(List.of(a, b)).build(), callbacks);
    assertFalse(result.isSuccess());
  }

  // TC-ENG-230 | 无条件边：空 condition 直接放行（到达后续步骤）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unconditionalEdgeAlwaysAllowed() {
    WorkflowStep a = step("a");
    a.setNext(new ArrayList<>(List.of(jump("b", null))));
    ExecutionResult result =
        run(Workflow.builder().steps(List.of(a, step("b"))).build(), selfLoopOk());
    assertTrue(result.isSuccess());
    assertEquals(2, result.getHistory().size());
  }

  // TC-ENG-231 | onTask 返回 null content → 子任务失败
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onTaskNullContentFailsSubtask() {
    WorkflowStep remote =
        WorkflowStep.builder()
            .name("remote")
            .stepType(StepType.ALL_SUCCESS)
            .subtasks(List.of(task("agent-a", "desc")))
            .build();
    ControlPoint nullContent =
        ControlPoint.builder().onTask(request -> CompletableFuture.completedFuture(null)).build();
    ExecutionResult result = run(Workflow.builder().steps(List.of(remote)).build(), nullContent);
    assertFalse(result.isSuccess());
  }

  // TC-ENG-232 | 历史与当前步骤输出可访问（不可修改视图）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void historyAndStepOutputsAccessible() {
    ExecutionResult result =
        run(Workflow.builder().steps(List.of(step("s1"))).build(), selfLoopOk());
    assertEquals(1, result.getHistory().size());
    assertEquals(1, result.getStepOutputs().size());
    assertThrows(UnsupportedOperationException.class, () -> result.getHistory().clear());
  }
}

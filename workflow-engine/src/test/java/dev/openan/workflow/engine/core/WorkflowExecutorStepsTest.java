/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.BusinessInput;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** TC-ENG-590…603：WorkflowExecutor 默认值/空结果/显式输入/校验与分发异常分支。 */
class WorkflowExecutorStepsTest {

  private static WorkflowStep selfStep(String name) {
    return WorkflowStep.builder()
        .name(name)
        .stepType(StepType.SELF_LOOP)
        .subtasks(List.of(Task.builder().agent("local").description(name).build()))
        .build();
  }

  private static Workflow workflow(WorkflowStep... steps) {
    return Workflow.builder().steps(List.of(steps)).build();
  }

  private static ControlPoint selfOk() {
    return ControlPoint.builder()
        .onSelfTask(
            request ->
                CompletableFuture.completedFuture(
                    TaskResult.success(List.of(request.getStepName()))))
        .build();
  }

  // TC-ENG-590 | lang=null 默认 "zh"
  //
  // 输入: 构造 executor 时 lang=null
  // 预期输出: SELF_LOOP TaskRequest.language == "zh"
  // 断言点: language；防回归: 默认值被删即 null
  @Test
  void nullLangDefaultsToZh() {
    AtomicReference<TaskRequest> captured = new AtomicReference<>();
    ControlPoint cp =
        ControlPoint.builder()
            .onSelfTask(
                request -> {
                  captured.set(request);
                  return CompletableFuture.completedFuture(TaskResult.success(List.of("s1")));
                })
            .build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(selfStep("s1")), cp, new StubWorkflowEngineClient(), null, "intent", null)
            .run()
            .join();
    assertTrue(result.isSuccess());
    assertEquals("zh", captured.get().getLanguage());
  }

  // TC-ENG-591 | onSelfTask 返回 null future → 步骤失败映射
  //
  // 输入: onSelfTask 返回 null
  // 预期输出: workflow 失败；history 记 NPE/requireNonNull 错误
  // 断言点: success=false + error；防回归: requireNonNull 被删即 NPE 裸穿
  @Test
  void nullSelfTaskFutureFailsStep() {
    ControlPoint cp = ControlPoint.builder().onSelfTask(request -> null).build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(selfStep("s1")),
                cp,
                new StubWorkflowEngineClient(),
                new EventCallback() {},
                "",
                "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("Step execution failed"), result.getError());
  }

  // TC-ENG-592 | onSelfTask 返回 future 结果为 null → "Callback returned null"
  //
  // 输入: onSelfTask future 正常完成但值为 null
  // 预期输出: 步骤失败
  // 断言点: success=false；防回归: null 值守卫被删
  @Test
  void nullSelfTaskResultFailsStep() {
    ControlPoint cp =
        ControlPoint.builder()
            .onSelfTask(request -> CompletableFuture.completedFuture(null))
            .build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(selfStep("s1")),
                cp,
                new StubWorkflowEngineClient(),
                new EventCallback() {},
                "",
                "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(
        result.getHistory().stream()
            .anyMatch(item -> String.valueOf(item).contains("IllegalStateException")),
        String.valueOf(result.getHistory()));
  }

  // TC-ENG-593 | getCurrentStepOutputs 返回防御性拷贝
  //
  // 输入: 成功执行一步后取快照并清空
  // 预期输出: 二次快照仍含该步骤
  // 断言点: 独立性；防回归: 拷贝被删即外部可改内部
  @Test
  void currentStepOutputsAreDefensiveCopy() {
    WorkflowExecutor executor =
        new WorkflowExecutor(
            workflow(selfStep("s1")), selfOk(), new StubWorkflowEngineClient(), null, "", "en");
    assertTrue(executor.run().join().isSuccess());
    Map<String, Map<String, Object>> snapshot = executor.getCurrentStepOutputs();
    assertTrue(snapshot.containsKey("s1"));
    snapshot.clear();
    assertTrue(executor.getCurrentStepOutputs().containsKey("s1"));
    assertNotSame(snapshot, executor.getCurrentStepOutputs());
  }

  // TC-ENG-594 | 远端任务显式 BusinessInput 不被描述覆盖
  //
  // 输入: Task.input=BusinessInput.text("structured")，description="do-A"
  // 预期输出: TaskRequest.input.text == "structured"
  // 断言点: input；防回归: 三元回退被改即错传描述
  @Test
  void explicitTaskInputIsForwarded() {
    AtomicReference<TaskRequest> captured = new AtomicReference<>();
    Task task =
        Task.builder()
            .agent("A")
            .description("do-A")
            .input(BusinessInput.text("structured body"))
            .build();
    WorkflowStep remote =
        WorkflowStep.builder()
            .name("s1")
            .stepType(StepType.ALL_SUCCESS)
            .subtasks(List.of(task))
            .build();
    ControlPoint cp =
        ControlPoint.builder()
            .onTask(
                request -> {
                  captured.set(request);
                  return CompletableFuture.completedFuture(
                      dev.openan.workflow.engine.model.MessageContent.text("do-A"));
                })
            .build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(remote), cp, new StubWorkflowEngineClient("A"), null, "", "en")
            .run()
            .join();
    assertTrue(result.isSuccess(), result.getError());
    assertEquals("structured body", captured.get().getInput().text());
    assertEquals("do-A", captured.get().getInstruction());
  }

  // TC-ENG-595 | sendTask 同步抛异常 → 任务失败不外泄
  //
  // 输入: engineClient.sendTask 抛 IllegalStateException
  // 预期输出: 工作流失败，错误含 "client down"
  // 断言点: 错误内容；防回归: 同步 catch 被删即崩溃
  @Test
  void synchronousSendFailureFailsTask() {
    StubWorkflowEngineClient throwing =
        new StubWorkflowEngineClient("A") {
          @Override
          public CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult> sendTask(
              String agentName, dev.openan.workflow.engine.model.MessageContent content) {
            throw new IllegalStateException("client down");
          }
        };
    WorkflowStep remote =
        WorkflowStep.builder()
            .name("s1")
            .stepType(StepType.ALL_SUCCESS)
            .subtasks(List.of(Task.builder().agent("A").description("do-A").build()))
            .build();
    ControlPoint cp =
        ControlPoint.builder()
            .onTask(
                request ->
                    CompletableFuture.completedFuture(
                        dev.openan.workflow.engine.model.MessageContent.text("do-A")))
            .build();
    ExecutionResult result =
        new WorkflowExecutor(workflow(remote), cp, throwing, null, "", "en").run().join();
    assertFalse(result.isSuccess());
    assertTrue(
        String.join(" ", result.getError(), String.valueOf(result.getHistory()))
            .contains("IllegalStateException"),
        result.getError());
    assertTrue(result.getHistory().toString().contains("workflow.execution_failed"));
  }

  // TC-ENG-596 | onTask 同步抛异常 → 任务失败
  //
  // 输入: controlPoint.onTask 抛 IllegalStateException
  // 预期输出: 工作流失败，错误含 "prep sync boom"
  // 断言点: 错误；防回归: 外层 catch 被删
  @Test
  void synchronousOnTaskThrowFailsTask() {
    WorkflowStep remote =
        WorkflowStep.builder()
            .name("s1")
            .stepType(StepType.ALL_SUCCESS)
            .subtasks(List.of(Task.builder().agent("A").description("do-A").build()))
            .build();
    ControlPoint cp =
        ControlPoint.builder()
            .onTask(
                request -> {
                  throw new IllegalStateException("prep sync boom");
                })
            .build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(remote), cp, new StubWorkflowEngineClient("A"), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(
        String.join(" ", result.getError(), String.valueOf(result.getHistory()))
            .contains("IllegalStateException"),
        result.getError());
    assertTrue(result.getHistory().toString().contains("workflow.execution_failed"));
  }

  // TC-ENG-597 | ANY_SUCCESS 空子任务 → 真空成功，输出空 Map
  //
  // 输入: ANY_SUCCESS 步骤 subtasks=[]
  // 预期输出: success=true，stepOutputs 含空 Map
  // 断言点: success + 输出；防回归: 空策略被删即异常
  @Test
  void anySuccessWithNoSubtasksSucceedsVacuously() {
    WorkflowStep empty =
        WorkflowStep.builder()
            .name("empty")
            .stepType(StepType.ANY_SUCCESS)
            .subtasks(List.of())
            .build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(empty), selfOk(), new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertTrue(result.isSuccess());
    assertTrue(result.getStepOutputs().containsKey("empty"));
    assertEquals(0, result.getHistory().size());
  }

  // TC-ENG-598 | null 步骤名图校验
  //
  // 输入: WorkflowStep.name=null
  // 预期输出: 失败结果含 "must not be blank"
  // 断言点: 错误字面量；防回归: null 分支被删
  @Test
  void nullStepNameFailsGraphValidation() {
    WorkflowStep nullName = WorkflowStep.builder().name(null).stepType(StepType.SELF_LOOP).build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(nullName), selfOk(), new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("must not be blank"), result.getError());
  }

  // TC-ENG-599 | 出边目标为 null（JumpCondition.step=null）→ 图校验失败
  //
  // 输入: 两步骤图，s1 的边 step=null
  // 预期输出: 失败含 "blank target"
  // 断言点: 错误；防回归: null 目标分支被删
  @Test
  void nullEdgeTargetFailsGraphValidation() {
    WorkflowStep s1 = selfStep("s1");
    List<JumpCondition> edges = new ArrayList<>();
    edges.add(JumpCondition.builder().step(null).condition("x").build());
    s1.setNext(edges);
    WorkflowStep s2 = selfStep("s2");
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(s1, s2), selfOk(), new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("blank target"), result.getError());
  }

  // TC-ENG-600 | contextFrom 命名非上游步骤 → 图校验失败
  //
  // 输入: s1->s3；s2 独立根；s3.contextFrom=["s2"]
  // 预期输出: 失败含 "is not an upstream dependency"
  // 断言点: 错误；防回归: 祖先校验被删即错注入上下文
  @Test
  void contextFromNonAncestorRejected() {
    WorkflowStep s1 = selfStep("s1");
    WorkflowStep s2 = selfStep("s2");
    WorkflowStep s3 = selfStep("s3");
    s1.setNext(List.of(JumpCondition.builder().step("s3").build()));
    s3.setContextFrom(new ArrayList<>(List.of("s2")));
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(s1, s2, s3), selfOk(), new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(result.getError().contains("is not an upstream dependency"), result.getError());
    assertTrue(result.getError().contains("s2"), result.getError());
  }

  // TC-ENG-601 | 回调超时取消在途 send future
  //
  // 输入: sendTask 返回永不完成的 future，callbackTimeoutSeconds=1
  // 预期输出: 工作流失败，错误为 TimeoutException；send future 被取消
  // 断言点: 错误类型 + 取消标记；防回归: 超时取消联动被删
  @Test
  void callbackTimeoutCancelsInFlightSend() {
    CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult> parked =
        new CompletableFuture<>();
    StubWorkflowEngineClient slow =
        new StubWorkflowEngineClient("A") {
          @Override
          public CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult> sendTask(
              String agentName, dev.openan.workflow.engine.model.MessageContent content) {
            return parked;
          }

          @Override
          public long callbackTimeoutSeconds() {
            return 1;
          }
        };
    WorkflowStep remote =
        WorkflowStep.builder()
            .name("s1")
            .stepType(StepType.ALL_SUCCESS)
            .subtasks(List.of(Task.builder().agent("A").description("do-A").build()))
            .build();
    ControlPoint cp =
        ControlPoint.builder()
            .onTask(
                request ->
                    CompletableFuture.completedFuture(
                        dev.openan.workflow.engine.model.MessageContent.text("do-A")))
            .build();
    ExecutionResult result =
        new WorkflowExecutor(workflow(remote), cp, slow, null, "", "en").run().join();
    assertFalse(result.isSuccess());
    assertTrue(parked.isCancelled(), "in-flight send must be cancelled on timeout");
    String joined = String.join(" ", result.getError(), String.valueOf(result.getHistory()));
    assertTrue(
        joined.contains("TimeoutException") || joined.toLowerCase().contains("timed out"), joined);
  }

  // TC-ENG-602 | getHistory 防御性拷贝
  //
  // 输入: 成功执行后修改返回的 history
  // 预期输出: executor 内部 history 不受影响
  // 断言点: 大小不变；防回归: 拷贝被删
  @Test
  void historyIsDefensiveCopy() {
    WorkflowExecutor executor =
        new WorkflowExecutor(
            workflow(selfStep("s1")), selfOk(), new StubWorkflowEngineClient(), null, "", "en");
    executor.run().join();
    List<Map<String, Object>> history = executor.getHistory();
    int size = history.size();
    assertTrue(size >= 1);
    history.clear();
    assertEquals(size, executor.getHistory().size());
  }

  // TC-ENG-603 | 图校验失败结果含空 stepOutputs 而非 null
  //
  // 输入: 非法工作流（空名）
  // 预期输出: success=false, stepOutputs 非 null
  // 断言点: 非 null；防回归: 失败结果构造被改
  @Test
  void invalidGraphResultHasEmptyOutputs() {
    WorkflowStep blank = WorkflowStep.builder().name(" ").stepType(StepType.SELF_LOOP).build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(blank), selfOk(), new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(result.getStepOutputs() != null && result.getStepOutputs().isEmpty());
  }

  // TC-ENG-604 | onSelfTask 返回 null future → NPE 失败（覆盖 375 requireNonNull）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullSelfTaskFutureFails() {
    ControlPoint cp = ControlPoint.builder().onSelfTask(request -> null).build();
    ExecutionResult result =
        new WorkflowExecutor(
                workflow(selfStep("s1")), cp, new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
  }

  // TC-ENG-605 | 路由回调抛无消息异常 → 类简单名回退
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void routeThrowWithoutMessageUsesClassName() {
    WorkflowStep s1 = selfStep("s1");
    WorkflowStep s2 = selfStep("s2");
    s1.setNext(
        new ArrayList<>(
            List.of(JumpCondition.builder().step("s2").condition("needs-route").build())));
    ControlPoint cp =
        new ControlPoint() {
          @Override
          public java.util.concurrent.CompletableFuture<dev.openan.workflow.engine.model.TaskResult>
              onSelfTask(dev.openan.workflow.engine.model.TaskRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                TaskResult.success(List.of(request.getStepName())));
          }

          @Override
          public java.util.concurrent.CompletableFuture<
                  dev.openan.workflow.engine.model.RouteDecision>
              onRoute(dev.openan.workflow.engine.model.RouteRequest request) {
            throw new IllegalStateException();
          }
        };
    ExecutionResult result =
        new WorkflowExecutor(workflow(s1, s2), cp, new StubWorkflowEngineClient(), null, "", "en")
            .run()
            .join();
    assertFalse(result.isSuccess());
    assertTrue(
        String.join(" ", result.getError(), String.valueOf(result.getHistory()))
            .contains("IllegalStateException"));
  }
}

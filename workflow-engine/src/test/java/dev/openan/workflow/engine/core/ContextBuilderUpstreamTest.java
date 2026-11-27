/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.TaskExecutionResult;
import dev.openan.workflow.engine.model.TaskStatus;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowInput;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-270…273：ContextBuilder 上游选择三种策略与传递闭包。 */
class ContextBuilderUpstreamTest {

  private static WorkflowStep step(String name, List<String> next, List<String> contextFrom) {
    WorkflowStep.WorkflowStepBuilder b =
        WorkflowStep.builder()
            .name(name)
            .subtasks(List.of())
            .next(
                next == null
                    ? List.of()
                    : next.stream().map(n -> JumpCondition.builder().step(n).build()).toList());
    if (contextFrom != null) {
      b.contextFrom(contextFrom);
    }
    return b.build();
  }

  private static TaskExecutionResult taskResult(String step) {
    return new TaskExecutionResult(
        "agent",
        "skill",
        "t-" + step,
        "desc",
        TaskStatus.SUCCESS,
        List.of("out-" + step),
        List.of(),
        null,
        null,
        null);
  }

  private static Workflow diamond() {
    // a -> b, a -> c, b -> d, c -> d
    return Workflow.builder()
        .steps(
            List.of(
                step("a", List.of("b", "c"), null),
                step("b", List.of("d"), null),
                step("c", List.of("d"), null),
                step("d", List.of(), List.of("*"))))
        .build();
  }

  // TC-ENG-270 | contextFrom=["*"]：选择所有有结果的祖先（传递闭包）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void starSelectsAllAncestorsWithResults() {
    Workflow workflow = diamond();
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    WorkflowStep d = workflow.getSteps().get(3);
    Map<String, List<TaskExecutionResult>> results = new LinkedHashMap<>();
    results.put("a", List.of(taskResult("a")));
    results.put("b", List.of(taskResult("b")));
    results.put("c", List.of(taskResult("c")));

    WorkflowInput input = builder.buildWorkflowInput(d, results);
    assertEquals(3, input.upstreamResults().size());
    assertTrue(input.upstreamResults().stream().anyMatch(r -> r.stepName().equals("a")));
  }

  // TC-ENG-271 | contextFrom 命名来源：只选择指定步骤
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void namedContextFromSelectsOnlyListed() {
    Workflow workflow = diamond();
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    WorkflowStep d =
        WorkflowStep.builder().name("d2").subtasks(List.of()).contextFrom(List.of("b")).build();
    Map<String, List<TaskExecutionResult>> results = new LinkedHashMap<>();
    results.put("a", List.of(taskResult("a")));
    results.put("b", List.of(taskResult("b")));
    results.put("c", List.of(taskResult("c")));

    WorkflowInput input = builder.buildWorkflowInput(d, results);
    assertEquals(1, input.upstreamResults().size());
    assertEquals("b", input.upstreamResults().get(0).stepName());
  }

  // TC-ENG-272 | contextFrom=null：仅直接前驱
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullContextFromUsesDirectPredecessors() {
    Workflow workflow =
        Workflow.builder()
            .steps(
                List.of(
                    step("a", List.of("b", "c"), null),
                    step("b", List.of("d"), null),
                    step("c", List.of("d"), null),
                    step("d", null, null)))
            .build();
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    WorkflowStep d = workflow.getSteps().get(3);
    Map<String, List<TaskExecutionResult>> results = new LinkedHashMap<>();
    results.put("a", List.of(taskResult("a")));
    results.put("b", List.of(taskResult("b")));
    results.put("c", List.of(taskResult("c")));

    // d 的直接前驱是 b、c（a 是祖父）
    List<String> direct = builder.getStepPredecessors("d");
    assertEquals(2, direct.size());
    assertTrue(direct.contains("b"));
    assertTrue(direct.contains("c"));

    WorkflowInput input = builder.buildWorkflowInput(d, results);
    assertEquals(2, input.upstreamResults().size());
  }

  // TC-ENG-273 | 传递闭包与 findStepIndex
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void transitiveAncestorsAndStepIndex() {
    Workflow workflow = diamond();
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    List<String> ancestorsOfD = builder.getAllPredecessors("d");
    assertTrue(ancestorsOfD.contains("a"));
    assertEquals(Integer.valueOf(0), builder.findStepIndex("a"));
    assertNull(builder.findStepIndex("ghost"));
  }
}

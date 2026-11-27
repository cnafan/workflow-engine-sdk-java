/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-960…966：ContextBuilder 前序/祖先/contextFrom 选择分支。 */
class ContextBuilderPredecessorsTest {

  private WorkflowStep step(String name, String... next) {
    WorkflowStep step = WorkflowStep.builder().name(name).build();
    if (next.length > 0) {
      List<JumpCondition> edges = new ArrayList<>();
      for (String target : next) edges.add(JumpCondition.builder().step(target).build());
      step.setNext(edges);
    }
    return step;
  }

  private Workflow workflow(WorkflowStep... steps) {
    return Workflow.builder().name("wf").steps(List.of(steps)).build();
  }

  private TaskExecutionResult result(String step) {
    return new TaskExecutionResult(
        "a",
        "",
        "t-" + step,
        "desc",
        TaskStatus.SUCCESS,
        List.of("output-" + step),
        List.of(),
        null,
        null,
        null);
  }

  // TC-ENG-960 | 直接前序：s1->s3, s2->s3
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void directPredecessors() {
    Workflow workflow = workflow(step("s1", "s3"), step("s2", "s3"), step("s3"));
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    List<String> preds = builder.getStepPredecessors("s3");
    assertTrue(preds.contains("s1"));
    assertTrue(preds.contains("s2"));
    assertEquals(0, builder.getStepPredecessors("s1").size());
  }

  // TC-ENG-961 | 自引用边不产生前序
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void selfEdgeIsNotPredecessor() {
    WorkflowStep self = step("s1");
    self.setNext(new ArrayList<>(List.of(JumpCondition.builder().step("s1").build())));
    ContextBuilder builder = new ContextBuilder(workflow(self), "intent");
    assertEquals(0, builder.getStepPredecessors("s1").size());
  }

  // TC-ENG-962 | 传递祖先：s1->s2->s3，s3 的祖先含 s1
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void transitiveAncestors() {
    Workflow workflow = workflow(step("s1", "s2"), step("s2", "s3"), step("s3"));
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    List<String> ancestors = builder.getAllPredecessors("s3");
    assertTrue(ancestors.contains("s1"));
    assertTrue(ancestors.contains("s2"));
  }

  // TC-ENG-963 | contextFrom 显式命名（空列表与命中）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void explicitContextFromList() {
    Workflow workflow = workflow(step("s1", "s2"), step("s2"));
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    WorkflowStep s2 = workflow.getSteps().get(1);
    s2.setContextFrom(new ArrayList<>(List.of("s1")));
    Map<String, List<TaskExecutionResult>> results =
        Map.of("s1", List.of(result("s1")), "other", List.of(result("other")));
    WorkflowInput input = builder.buildWorkflowInput(s2, results);
    assertEquals(1, input.upstreamResults().size());
    assertEquals("s1", input.upstreamResults().get(0).stepName());

    // 空 contextFrom：不选任何上游
    s2.setContextFrom(new ArrayList<>());
    assertEquals(0, builder.buildWorkflowInput(s2, results).upstreamResults().size());
  }

  // TC-ENG-964 | contextFrom="*" 选择全部祖先
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void wildcardContextFromSelectsAllAncestors() {
    Workflow workflow = workflow(step("s1", "s2"), step("s2", "s3"), step("s3"));
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    WorkflowStep s3 = workflow.getSteps().get(2);
    s3.setContextFrom(new ArrayList<>(List.of("*")));
    Map<String, List<TaskExecutionResult>> results = new HashMap<>();
    results.put("s1", List.of(result("s1")));
    results.put("s2", List.of(result("s2")));
    WorkflowInput input = builder.buildWorkflowInput(s3, results);
    assertEquals(2, input.upstreamResults().size());
  }

  // TC-ENG-965 | null contextFrom 回退直接前序
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullContextFromFallsBackToPredecessors() {
    Workflow workflow = workflow(step("s1", "s2"), step("s2"));
    ContextBuilder builder = new ContextBuilder(workflow, null);
    WorkflowStep s2 = workflow.getSteps().get(1);
    Map<String, List<TaskExecutionResult>> results = Map.of("s1", List.of(result("s1")));
    WorkflowInput input = builder.buildWorkflowInput(s2, results);
    assertEquals(1, input.upstreamResults().size());
    assertEquals("", input.runtimeIntent());
  }

  // TC-ENG-966 | findStepIndex
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void findStepIndex() {
    Workflow workflow = workflow(step("s1", "s2"), step("s2"));
    ContextBuilder builder = new ContextBuilder(workflow, "intent");
    assertEquals(0, builder.findStepIndex("s1"));
    assertEquals(1, builder.findStepIndex("s2"));
    assertNull(builder.findStepIndex("missing"));
  }
}

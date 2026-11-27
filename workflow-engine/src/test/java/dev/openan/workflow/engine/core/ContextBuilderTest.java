/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskExecutionResult;
import dev.openan.workflow.engine.model.TaskStatus;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

  private WorkflowStep step(String name, int layer, List<String> next, List<String> contextFrom) {
    return WorkflowStep.builder()
        .name(name)
        .layer(layer)
        .next(
            next == null
                ? List.of()
                : next.stream()
                    .map(target -> JumpCondition.builder().step(target).build())
                    .toList())
        .contextFrom(contextFrom)
        .subtasks(List.of(Task.builder().agent("A").description("t").build()))
        .build();
  }

  private TaskExecutionResult result(String agent, String output) {
    return new TaskExecutionResult(
        agent,
        "diagnose",
        agent + "-task",
        "task",
        TaskStatus.SUCCESS,
        List.of(output),
        List.of(),
        null,
        null,
        Map.of());
  }

  // TC-ENG | layerZeroKeepsRuntimeIntentSeparateFromTaskInstruction
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void layerZeroKeepsRuntimeIntentSeparateFromTaskInstruction() {
    Workflow workflow =
        Workflow.builder().name("w").steps(List.of(step("s1", 0, List.of("s2"), null))).build();

    var context =
        new ContextBuilder(workflow, "my intent")
            .buildWorkflowInput(workflow.getSteps().get(0), Map.of());
    assertEquals("my intent", context.runtimeIntent());
    assertTrue(context.upstreamResults().isEmpty());
  }

  // TC-ENG | directPredecessorsProvideTypedResults
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void directPredecessorsProvideTypedResults() {
    WorkflowStep s1 = step("s1", 0, List.of("s2"), null);
    WorkflowStep s2 = step("s2", 1, List.of(), null);
    Workflow workflow = Workflow.builder().name("w").steps(List.of(s1, s2)).build();
    Map<String, List<TaskExecutionResult>> results =
        Map.of("s1", List.of(result("OMC-1", "result-from-A")));

    var context = new ContextBuilder(workflow, "intent").buildWorkflowInput(s2, results);

    assertEquals("intent", context.runtimeIntent());
    assertEquals(1, context.upstreamResults().size());
    assertEquals("s1", context.upstreamResults().get(0).stepName());
    assertEquals("OMC-1", context.upstreamResults().get(0).taskResults().get(0).agentName());
    assertEquals(
        List.of("result-from-A"), context.upstreamResults().get(0).taskResults().get(0).outputs());
  }

  // TC-ENG | contextFromExplicitListOverridesDirectPredecessors
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromExplicitListOverridesDirectPredecessors() {
    WorkflowStep s1 = step("s1", 0, List.of("s3"), null);
    WorkflowStep s2 = step("s2", 0, List.of("s3"), null);
    WorkflowStep s3 = step("s3", 1, List.of(), List.of("s2"));
    Workflow workflow = Workflow.builder().name("w").steps(List.of(s1, s2, s3)).build();
    Map<String, List<TaskExecutionResult>> results = new HashMap<>();
    results.put("s1", List.of(result("OMC-1", "out1")));
    results.put("s2", List.of(result("OMC-2", "out2")));

    var context = new ContextBuilder(workflow, "intent").buildWorkflowInput(s3, results);

    assertEquals(
        List.of("s2"), context.upstreamResults().stream().map(value -> value.stepName()).toList());
  }

  // TC-ENG | contextFromStarIncludesAllAncestors
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromStarIncludesAllAncestors() {
    WorkflowStep s1 = step("s1", 0, List.of("s2"), null);
    WorkflowStep s2 = step("s2", 1, List.of("s3"), null);
    WorkflowStep s3 = step("s3", 2, List.of(), List.of("*"));
    Workflow workflow = Workflow.builder().name("w").steps(List.of(s1, s2, s3)).build();
    Map<String, List<TaskExecutionResult>> results = new HashMap<>();
    results.put("s1", List.of(result("OMC-1", "out1")));
    results.put("s2", List.of(result("OMC-2", "out2")));

    var context = new ContextBuilder(workflow, "intent").buildWorkflowInput(s3, results);

    assertEquals(2, context.upstreamResults().size());
    assertEquals(
        List.of("s2", "s1"),
        context.upstreamResults().stream().map(value -> value.stepName()).toList());
  }

  // TC-ENG | explicitEmptyContextFromDisablesUpstreamAggregation
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void explicitEmptyContextFromDisablesUpstreamAggregation() {
    WorkflowStep s1 = step("s1", 0, List.of("s2"), null);
    WorkflowStep s2 = step("s2", 1, List.of(), List.of());
    Workflow workflow = Workflow.builder().name("w").steps(List.of(s1, s2)).build();
    Map<String, List<TaskExecutionResult>> results = Map.of("s1", List.of(result("OMC-1", "out1")));

    var context = new ContextBuilder(workflow, "intent").buildWorkflowInput(s2, results);
    assertTrue(context.upstreamResults().isEmpty());
  }

  // TC-ENG | getStepPredecessorsFindsDirectParents
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void getStepPredecessorsFindsDirectParents() {
    WorkflowStep s1 = step("s1", 0, List.of("s3"), null);
    WorkflowStep s2 = step("s2", 0, List.of("s3"), null);
    WorkflowStep s3 = step("s3", 1, List.of(), null);
    Workflow workflow = Workflow.builder().name("w").steps(List.of(s1, s2, s3)).build();

    List<String> predecessors = new ContextBuilder(workflow, "").getStepPredecessors("s3");
    assertEquals(2, predecessors.size());
    assertTrue(predecessors.contains("s1"));
    assertTrue(predecessors.contains("s2"));
  }
}

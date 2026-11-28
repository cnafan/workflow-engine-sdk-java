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

package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for Workflow/WorkflowStep/Task/JumpCondition/StepType model parsing. */
class WorkflowModelTest {

  // TC-ENG | mapLoadingPreservesStructuredBusinessInput
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void mapLoadingPreservesStructuredBusinessInput() {
    Map<String, Object> input = Map.of("data", Map.of("serviceId", "service-42"));
    Workflow workflow =
        Workflow.fromMap(
            Map.of(
                "steps",
                List.of(
                    Map.of(
                        "name",
                        "start",
                        "subtasks",
                        List.of(
                            Map.of("agent", "A", "description", "diagnose", "input", input))))));
    assertEquals(
        BusinessInput.data(Map.of("serviceId", "service-42")),
        workflow.getSteps().get(0).getSubtasks().get(0).getInput());
  }

  // TC-ENG | mapLoadingRejectsAmbiguousBusinessInput
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void mapLoadingKeepsTheDeclaredHost() {
    Workflow workflow = Workflow.fromMap(Map.of("workflow_host", "Host Agent", "steps", List.of()));
    assertEquals("Host Agent", workflow.getWorkflowHost());
    assertEquals("Host Agent", workflow.declaredHost().orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () -> Workflow.fromMap(Map.of("workflow_host", List.of("Host Agent"), "steps", List.of())));
  }

  @Test
  void declaredHostFallsBackToTheSingleSelfLoopAgent() {
    Workflow legacy =
        Workflow.fromMap(
            Map.of(
                "steps",
                List.of(
                    Map.of(
                        "name",
                        "merge",
                        "step_type",
                        "SelfLoop",
                        "subtasks",
                        List.of(Map.of("agent", " Host Agent ", "description", "summarise"))))));
    assertEquals("Host Agent", legacy.declaredHost().orElseThrow());
  }

  @Test
  void declaredHostIsEmptyForAHostlessDefinition() {
    Workflow remote =
        Workflow.fromMap(
            Map.of(
                "steps",
                List.of(
                    Map.of(
                        "name",
                        "diagnose",
                        "subtasks",
                        List.of(Map.of("agent", "Domain Agent", "description", "diagnose"))))));
    assertTrue(Workflow.fromMap(Map.of("steps", List.of())).declaredHost().isEmpty());
    assertTrue(remote.declaredHost().isEmpty(), "an ordinary task's agent is not a host");
  }

  @Test
  void declaredHostRefusesADefinitionThatNamesTwoHosts() {
    Map<String, Object> definition =
        Map.of(
            "workflow_host",
            "Host A",
            "steps",
            List.of(
                Map.of(
                    "name",
                    "merge",
                    "step_type",
                    "SelfLoop",
                    "subtasks",
                    List.of(Map.of("agent", "Host B", "description", "x")))));
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> Workflow.fromMap(definition).declaredHost());
    assertTrue(failure.getMessage().contains("Host A") && failure.getMessage().contains("Host B"));
  }

  @Test
  void mapLoadingRejectsAmbiguousBusinessInput() {
    Map<String, Object> input = Map.of("text", "diagnose", "data", Map.of("id", 42));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workflow.fromMap(
                Map.of(
                    "steps",
                    List.of(
                        Map.of(
                            "name",
                            "start",
                            "subtasks",
                            List.of(Map.of("agent", "A", "input", input)))))));
  }

  // TC-ENG | parsesLinearWorkflow
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void parsesLinearWorkflow() {
    Map<String, Object> data =
        Map.of(
            "id", "wf-1",
            "name", "test",
            "steps",
                List.of(
                    Map.of(
                        "name",
                        "s1",
                        "layer",
                        0,
                        "subtasks",
                        List.of(Map.of("agent", "A", "skill", "diag", "description", "do A")),
                        "next",
                        List.of(Map.of("step", "s2", "condition", ""))),
                    Map.of(
                        "name",
                        "s2",
                        "layer",
                        1,
                        "subtasks",
                        List.of(Map.of("agent", "B", "description", "do B")))));
    Workflow wf = Workflow.fromMap(data);
    assertEquals("wf-1", wf.getId());
    assertEquals("test", wf.getName());
    assertEquals(2, wf.getSteps().size());
    assertEquals("s1", wf.getSteps().get(0).getName());
    assertEquals(0, wf.getSteps().get(0).getLayer());
    assertEquals("A", wf.getSteps().get(0).getSubtasks().get(0).getAgent());
    assertEquals("diag", wf.getSteps().get(0).getSubtasks().get(0).getSkill());
    assertEquals("do A", wf.getSteps().get(0).getSubtasks().get(0).getDescription());
    assertEquals("s2", wf.getSteps().get(0).getNext().get(0).getStep());
    assertEquals(1, wf.getSteps().get(1).getLayer());
  }

  // TC-ENG | stepTypeCaseInsensitiveAllSuccess
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stepTypeCaseInsensitiveAllSuccess() {
    for (String v :
        List.of("AllSuccess", "ALLSUCCESS", "allsuccess", "ALL_SUCCESS", "all_success")) {
      Map<String, Object> data =
          Map.of(
              "name",
              "t",
              "steps",
              List.of(Map.of("name", "s", "step_type", v, "subtasks", List.of())));
      Workflow wf = Workflow.fromMap(data);
      assertEquals(
          StepType.ALL_SUCCESS, wf.getSteps().get(0).getStepType(), "Failed for input: " + v);
    }
  }

  // TC-ENG | stepTypeCaseInsensitiveAnySuccess
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stepTypeCaseInsensitiveAnySuccess() {
    for (String v :
        List.of("AnySuccess", "ANYSUCCESS", "anysuccess", "ANY_SUCCESS", "any_success")) {
      Map<String, Object> data =
          Map.of(
              "name",
              "t",
              "steps",
              List.of(Map.of("name", "s", "step_type", v, "subtasks", List.of())));
      Workflow wf = Workflow.fromMap(data);
      assertEquals(
          StepType.ANY_SUCCESS, wf.getSteps().get(0).getStepType(), "Failed for input: " + v);
    }
  }

  // TC-ENG | stepTypeViaTypeFieldFallback
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stepTypeViaTypeFieldFallback() {
    Map<String, Object> data =
        Map.of(
            "name",
            "t",
            "steps",
            List.of(Map.of("name", "s", "type", "AnySuccess", "subtasks", List.of())));
    Workflow wf = Workflow.fromMap(data);
    assertEquals(StepType.ANY_SUCCESS, wf.getSteps().get(0).getStepType());
  }

  // TC-ENG | stepTypeDefaultsToAllSuccess
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stepTypeDefaultsToAllSuccess() {
    Map<String, Object> data =
        Map.of("name", "t", "steps", List.of(Map.of("name", "s", "subtasks", List.of())));
    Workflow wf = Workflow.fromMap(data);
    assertEquals(StepType.ALL_SUCCESS, wf.getSteps().get(0).getStepType());
  }

  // TC-ENG | unknownStepTypeFailsClearly
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unknownStepTypeFailsClearly() {
    Map<String, Object> data =
        Map.of(
            "name",
            "t",
            "steps",
            List.of(Map.of("name", "s", "step_type", "bogus", "subtasks", List.of())));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(data));
    assertTrue(error.getMessage().contains("Unknown workflow step type"));
  }

  // TC-ENG | contextFromSingleStringNormalizedToList
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromSingleStringNormalizedToList() {
    Map<String, Object> data =
        Map.of(
            "name",
            "t",
            "steps",
            List.of(Map.of("name", "s", "context_from", "prev_step", "subtasks", List.of())));
    Workflow wf = Workflow.fromMap(data);
    assertNotNull(wf.getSteps().get(0).getContextFrom());
    assertEquals(List.of("prev_step"), wf.getSteps().get(0).getContextFrom());
  }

  // TC-ENG | contextFromListPreserved
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromListPreserved() {
    java.util.ArrayList<String> sources = new java.util.ArrayList<>(List.of("a", "b"));
    Map<String, Object> step = new java.util.HashMap<>();
    step.put("name", "s");
    step.put("context_from", sources);
    step.put("subtasks", List.of());
    Map<String, Object> data = Map.of("name", "t", "steps", List.of(step));
    Workflow wf = Workflow.fromMap(data);
    sources.add("later");
    assertEquals(List.of("a", "b"), wf.getSteps().get(0).getContextFrom());
  }

  // TC-ENG | invalidWorkflowFieldTypesFailClearly
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void invalidWorkflowFieldTypesFailClearly() {
    IllegalArgumentException nameError =
        assertThrows(
            IllegalArgumentException.class,
            () -> Workflow.fromMap(Map.of("name", 42, "steps", List.of())));
    assertEquals("name must be a string", nameError.getMessage());

    IllegalArgumentException stepsError =
        assertThrows(
            IllegalArgumentException.class,
            () -> Workflow.fromMap(Map.of("name", "t", "steps", List.of("not-an-object"))));
    assertEquals("steps entries must be objects", stepsError.getMessage());
  }

  // TC-ENG | contextFromStarPreserved
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromStarPreserved() {
    Map<String, Object> data =
        Map.of(
            "name",
            "t",
            "steps",
            List.of(Map.of("name", "s", "context_from", List.of("*"), "subtasks", List.of())));
    Workflow wf = Workflow.fromMap(data);
    assertEquals(List.of("*"), wf.getSteps().get(0).getContextFrom());
  }

  // TC-ENG | taskStatusDefaultsToPending
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskStatusDefaultsToPending() {
    Task t = Task.builder().agent("A").description("do").build();
    assertEquals(TaskStatus.PENDING, t.getStatus());
  }

  // TC-ENG | jumpConditionDefaults
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void jumpConditionDefaults() {
    JumpCondition jc = JumpCondition.builder().step("s2").build();
    assertEquals("s2", jc.getStep());
    assertEquals("", jc.getCondition());
  }

  // TC-ENG | emptyStepsAllowed
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyStepsAllowed() {
    Map<String, Object> data = Map.of("name", "empty", "steps", List.of());
    Workflow wf = Workflow.fromMap(data);
    assertTrue(wf.getSteps().isEmpty());
  }
}

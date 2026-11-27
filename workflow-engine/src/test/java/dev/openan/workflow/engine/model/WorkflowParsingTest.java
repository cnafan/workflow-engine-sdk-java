/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-280…287：Workflow/WorkflowStep 构造与 fromMap 解析分支。 */
class WorkflowParsingTest {

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // TC-ENG-280 | null 数据拒绝；空 steps 默认
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullAndEmptyMap() {
    assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(null));
    Workflow empty = Workflow.fromMap(new LinkedHashMap<>());
    assertTrue(empty.getSteps().isEmpty());
    assertEquals("", empty.getName());
  }

  // TC-ENG-281 | 完整 PSOP 结构解析：subtasks/input text/next/context_from/layer/type
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fullPsopStructureParses() {
    Map<String, Object> subtask =
        map(
            "agent",
            "agent-a",
            "skill",
            "skill-a",
            "description",
            "desc",
            "input",
            map("text", "hello"));
    Map<String, Object> s1 =
        map(
            "name",
            "s1",
            "layer",
            2,
            "step_type",
            "anySuccess",
            "context_from",
            List.of("src"),
            "subtasks",
            List.of(subtask),
            "next",
            List.of(map("step", "s2", "condition", "")));
    Map<String, Object> s2 = map("name", "s2", "input", map("data", List.of(1, 2)));
    Map<String, Object> data =
        map("id", "wf-1", "name", "full", "description", "d", "steps", List.of(s1, s2));
    Workflow wf = Workflow.fromMap(data);
    assertEquals("wf-1", wf.getId());
    assertEquals(2, wf.getSteps().size());
    WorkflowStep first = wf.getSteps().get(0);
    assertEquals(StepType.ANY_SUCCESS, first.getStepType());
    assertEquals(2, first.getLayer());
    assertEquals(List.of("src"), first.getContextFrom());
    assertEquals(1, first.getSubtasks().size());
    assertEquals(1, first.getNext().size());
    assertEquals(StepType.ALL_SUCCESS, wf.getSteps().get(1).getStepType());
  }

  // TC-ENG-282 | context_from：字符串单值 / 非列表非字符串拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromStringAndInvalid() {
    Workflow stringForm =
        Workflow.fromMap(map("steps", List.of(map("name", "s", "context_from", "src"))));
    assertEquals(List.of("src"), stringForm.getSteps().get(0).getContextFrom());

    assertThrows(
        IllegalArgumentException.class,
        () -> Workflow.fromMap(map("steps", List.of(map("name", "s", "context_from", 42)))));
  }

  // TC-ENG-283 | subtask input 非法：非 Map / text+data 同时 / 都缺 / 值为 null
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subtaskInputValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workflow.fromMap(
                map("steps", List.of(map("name", "s", "subtasks", List.of(map("input", "x")))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workflow.fromMap(
                map(
                    "steps",
                    List.of(
                        map(
                            "name",
                            "s",
                            "subtasks",
                            List.of(map("input", map("text", "x", "data", "y"))))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workflow.fromMap(
                map("steps", List.of(map("name", "s", "subtasks", List.of(map("input", map())))))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workflow.fromMap(
                map(
                    "steps",
                    List.of(
                        map("name", "s", "subtasks", List.of(map("input", map("text", null))))))));
  }

  // TC-ENG-284 | steps/subtasks/next 非列表拒绝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void listFieldsMustBeLists() {
    assertThrows(
        IllegalArgumentException.class, () -> Workflow.fromMap(map("steps", "not-a-list")));
    assertThrows(
        IllegalArgumentException.class,
        () -> Workflow.fromMap(map("steps", List.of(map("name", "s", "subtasks", "x")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Workflow.fromMap(map("steps", List.of(map("name", "s", "next", "x")))));
  }

  // TC-ENG-285 | 构造器与 setter 的 null 默认值；视图不可修改
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorAndSetterNullDefaults() {
    Workflow wf = new Workflow(null, null, null, null);
    assertEquals("", wf.getId());
    assertTrue(wf.getSteps().isEmpty());
    wf.setSteps(null);
    assertTrue(wf.getSteps().isEmpty());
    List<WorkflowStep> mutable = new ArrayList<>();
    mutable.add(WorkflowStep.builder().name("s").subtasks(List.of()).build());
    wf.setSteps(mutable);
    mutable.clear();
    assertEquals(1, wf.getSteps().size());
    assertThrows(UnsupportedOperationException.class, () -> wf.getSteps().clear());

    WorkflowStep step = new WorkflowStep("n", null, null, 0, null, null);
    assertTrue(step.getSubtasks().isEmpty());
    assertTrue(step.getNext().isEmpty());
    assertNull(step.getContextFrom());
    step.setSubtasks(null);
    step.setNext(null);
    step.setContextFrom(null);
    assertNull(step.getContextFrom());
    assertThrows(UnsupportedOperationException.class, () -> step.getSubtasks().clear());
  }

  // TC-ENG-286 | 非字符串字段拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonStringFieldsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Workflow.fromMap(map("steps", List.of(map("name", 1)))));
  }

  // TC-ENG-287 | BusinessInput text/data 工厂
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessInputFactories() {
    BusinessInput text = BusinessInput.text("hello");
    assertEquals("hello", text.text());
    org.junit.jupiter.api.Assertions.assertNull(text.data());
    Object payload = Map.of("k", "v");
    BusinessInput data = BusinessInput.data(payload);
    assertEquals(payload, data.data());
    org.junit.jupiter.api.Assertions.assertNull(data.text());
  }
}

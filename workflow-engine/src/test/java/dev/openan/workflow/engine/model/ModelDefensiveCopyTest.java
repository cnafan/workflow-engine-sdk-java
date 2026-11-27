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

package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-150…155：ExecutionResult / WorkflowSearchResult 防御性拷贝与默认值。 */
class ModelDefensiveCopyTest {

  // TC-ENG-150 | ExecutionResult null 集合 → null，普通构造保留字段
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void executionResultNullCollections() {
    ExecutionResult result = new ExecutionResult(true, null, null, null);
    assertTrue(result.isSuccess());
    assertNull(result.getHistory());
    assertNull(result.getStepOutputs());
    assertNull(result.getError());
  }

  // TC-ENG-151 | 防御性拷贝：ctor/setter 复制，getter 返回不可修改视图
  //
  // 防回归: 外部修改原始集合不得影响结果；不可修改视图的修改必须抛异常
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void executionResultDefensiveCopiesAndUnmodifiableViews() {
    List<Map<String, Object>> history = new ArrayList<>();
    history.add(new HashMap<>(Map.of("step", "s1")));
    Map<String, Map<String, Object>> outputs = new HashMap<>();
    outputs.put("s1", new HashMap<>(Map.of("k", "v")));
    ExecutionResult result = new ExecutionResult(false, history, outputs, "boom");
    assertFalse(result.isSuccess());
    assertEquals("boom", result.getError());

    history.clear();
    outputs.clear();
    assertEquals(1, result.getHistory().size());
    assertEquals("s1", result.getHistory().get(0).get("step"));
    assertEquals("v", result.getStepOutputs().get("s1").get("k"));

    assertThrows(
        UnsupportedOperationException.class, () -> result.getHistory().add(new HashMap<>()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.getStepOutputs().put("x", new HashMap<>()));

    List<Map<String, Object>> newHistory =
        new ArrayList<>(List.of(new HashMap<>(Map.of("step", "s2"))));
    result.setHistory(newHistory);
    newHistory.clear();
    assertEquals(1, result.getHistory().size());
    result.setHistory(null);
    assertNull(result.getHistory());

    Map<String, Map<String, Object>> newOutputs =
        new HashMap<>(Map.of("s2", new HashMap<>(Map.of("k", "v2"))));
    result.setStepOutputs(newOutputs);
    newOutputs.clear();
    assertEquals("v2", result.getStepOutputs().get("s2").get("k"));
    result.setStepOutputs(null);
    assertNull(result.getStepOutputs());
  }

  // TC-ENG-152 | builder 默认值与全字段
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void executionResultBuilder() {
    ExecutionResult empty = ExecutionResult.builder().build();
    assertFalse(empty.isSuccess());
    ExecutionResult ok =
        ExecutionResult.builder()
            .success(true)
            .history(new ArrayList<>(List.of(new HashMap<>(Map.of("step", "s")))))
            .stepOutputs(new HashMap<>(Map.of("s", new HashMap<>(Map.of("out", "x")))))
            .build();
    assertTrue(ok.isSuccess());
    assertEquals(1, ok.getHistory().size());
  }

  // TC-ENG-153 | WorkflowSearchResult builder 默认 tags 空、score=1.0
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowSearchResultBuilderDefaults() {
    WorkflowSearchResult result =
        WorkflowSearchResult.builder().workflowId("wf-1").workflowType("PSOP").name("n").build();
    assertEquals("wf-1", result.getWorkflowId());
    assertEquals(List.of(), result.getTags());
    assertEquals(1.0, result.getScore(), 0.0001);
  }

  // TC-ENG-154 | tags null → 空；setter 防御拷贝；不可修改视图
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowSearchResultTagsDefensive() {
    WorkflowSearchResult result =
        new WorkflowSearchResult("wf", "PSOP", "n", "d", null, "now", 0.5, null, null, null);
    assertEquals(List.of(), result.getTags());
    assertEquals(0.5, result.getScore(), 0.0001);

    List<String> tags = new ArrayList<>(List.of("a", "b"));
    result.setTags(tags);
    tags.add("c");
    assertEquals(List.of("a", "b"), result.getTags());
    assertThrows(UnsupportedOperationException.class, () -> result.getTags().add("x"));

    result.setTags(null);
    assertEquals(List.of(), result.getTags());

    WorkflowSearchResult viaBuilder =
        WorkflowSearchResult.builder()
            .workflowId("wf2")
            .userIntent("intent")
            .relatedPreflow("pf")
            .tasksSummary("summary")
            .tags(new ArrayList<>(List.of("t")))
            .build();
    assertEquals("intent", viaBuilder.getUserIntent());
    assertEquals("pf", viaBuilder.getRelatedPreflow());
    assertEquals("summary", viaBuilder.getTasksSummary());
    assertEquals("n", result.getName());
    assertEquals("d", result.getDescription());
    assertEquals("now", result.getCreatedAt());
  }
}

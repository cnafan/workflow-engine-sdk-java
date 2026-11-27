/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteModelTest {

  // TC-ENG | decisionFactoriesExposeAllowedStateAndReason
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void decisionFactoriesExposeAllowedStateAndReason() {
    assertEquals(new RouteDecision(true, ""), RouteDecision.allow());
    assertEquals(new RouteDecision(true, "matched"), RouteDecision.allow("matched"));
    assertEquals(new RouteDecision(false, ""), RouteDecision.deny());
    assertEquals(new RouteDecision(false, "blocked"), RouteDecision.deny("blocked"));
    assertEquals("", new RouteDecision(true, null).reason());
  }

  // TC-ENG | requestRepresentsOneConditionalEdgeAndCopiesResults
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void requestRepresentsOneConditionalEdgeAndCopiesResults() {
    List<TaskExecutionResult> results = new ArrayList<>();
    RouteRequest request =
        new RouteRequest(
            "execution", "source", "target", "alarm exists", WorkflowInput.empty(), results);
    results.add(null);

    assertEquals("target", request.nextStep());
    assertEquals("alarm exists", request.condition());
    assertEquals(List.of(), request.currentResults());
  }

  // TC-ENG | requestRejectsBlankConditionalEdgeFields
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void requestRejectsBlankConditionalEdgeFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RouteRequest(
                "execution", "source", "target", "  ", WorkflowInput.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RouteRequest(
                "execution", "source", " ", "condition", WorkflowInput.empty(), List.of()));
  }
}

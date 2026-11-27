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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-209…212：StepType 与 TaskExecutionResult 记录契约。 */
class ModelEdgeTest {

  // TC-ENG-209 | StepType.fromValue：null→ALL_SUCCESS，值与枚举名大小写不敏感，未知抛异常
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stepTypeParsing() {
    assertEquals(StepType.ALL_SUCCESS, StepType.fromValue(null));
    assertEquals(StepType.ANY_SUCCESS, StepType.fromValue("anysuccess"));
    assertEquals(StepType.ANY_SUCCESS, StepType.fromValue("AnySuccess"));
    assertEquals(StepType.SELF_LOOP, StepType.fromValue("selfloop"));
    assertThrows(IllegalArgumentException.class, () -> StepType.fromValue("unknown"));
    assertEquals("AnySuccess", StepType.ANY_SUCCESS.getValue());
  }

  // TC-ENG-210 | TaskExecutionResult：空白 agentName/null taskId/null status 拒绝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskExecutionResultRequiredFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskExecutionResult(
                " ", "s", "t-1", "d", TaskStatus.SUCCESS, List.of(), List.of(), null, null, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new TaskExecutionResult(
                "agent",
                "s",
                null,
                "d",
                TaskStatus.SUCCESS,
                List.of(),
                List.of(),
                null,
                null,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new TaskExecutionResult(
                "agent", "s", "t-1", "d", null, List.of(), List.of(), null, null, null));
  }

  // TC-ENG-211 | TaskExecutionResult：默认值与防御性不可修改视图
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskExecutionResultDefaultsAndUnmodifiableViews() {
    TaskExecutionResult result =
        new TaskExecutionResult(
            "agent", null, "t-1", null, TaskStatus.SUCCESS, List.of("out"), null, null, null, null);
    assertEquals("", result.skill());
    assertEquals("", result.taskDescription());
    assertEquals(List.of(), result.receivedMessages());
    assertEquals(Map.of(), result.errorDetails());
    assertEquals(List.of("out"), result.outputs());
    assertThrows(UnsupportedOperationException.class, () -> result.outputs().add("x"));
    assertThrows(UnsupportedOperationException.class, () -> result.errorDetails().put("k", "v"));
  }

  // TC-ENG-212 | 有 ReceivedMessage 时 outputs 由消息投影（防御性拷贝）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskExecutionResultOutputsFromReceivedMessages() {
    MessageContent content = MessageContent.text("projected");
    ReceivedMessage received = new ReceivedMessage(content, new java.util.HashMap<>(), List.of());
    List<ReceivedMessage> messages = new ArrayList<>(List.of(received));
    TaskExecutionResult result =
        new TaskExecutionResult(
            "agent",
            "s",
            "t-1",
            "d",
            TaskStatus.SUCCESS,
            List.of("ignored"),
            messages,
            null,
            null,
            null);
    assertTrue(result.outputs().contains("projected"));
    // 修改入参不影响结果
    messages.clear();
    assertEquals(1, result.receivedMessages().size());
  }
}

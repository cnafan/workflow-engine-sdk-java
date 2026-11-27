/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-1067…1073：record/builder 参数校验 throw 体覆盖。 */
class ModelValidationThrowTest {

  // TC-ENG-1067 | MessageContent.withExtension：null URI → NPE（requireNonNull 守卫）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void withExtensionRejectsNullUri() {
    MessageContent content = MessageContent.text("x");
    assertThrows(NullPointerException.class, () -> content.withExtension(null));
  }

  // TC-ENG-1068 | ReceivedMessage：null 归一为空 Map/空列表（紧凑构造器分支）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void receivedMessageNormalizesNulls() {
    ReceivedMessage received = new ReceivedMessage(null, null, null);
    assertTrue(received.taskMetadata().isEmpty());
    assertTrue(received.artifacts().isEmpty());
    assertTrue(received.outputs(false).isEmpty());
  }

  // TC-ENG-1069 | BusinessFailure：null code → IAE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessFailureRejectsNullCode() {
    assertThrows(IllegalArgumentException.class, () -> new BusinessFailure(null, "m", Map.of()));
  }

  // TC-ENG-1070 | UpstreamStepResult：null stepName → IAE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void upstreamStepResultRejectsNullName() {
    assertThrows(IllegalArgumentException.class, () -> new UpstreamStepResult(null, List.of()));
  }

  // TC-ENG-1071 | WorkflowInput：null 归一为 ""/空列表（紧凑构造器两侧分支）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowInputNormalizesNulls() {
    WorkflowInput input = new WorkflowInput(null, null);
    assertEquals("", input.runtimeIntent());
    assertTrue(input.upstreamResults().isEmpty());
  }

  // TC-ENG-1072 | TaskResult builder：failure 携带 details 归一
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskResultBuilderBranches() {
    TaskResult withDetails =
        TaskResult.builder().success(false).errorCode("c").error("m").errorDetails(null).build();
    assertTrue(withDetails.getErrorDetails() != null);
  }

  // TC-ENG-1073 | Workflow：getSteps/setSteps null 守卫与 fromMap 缺 steps
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowGuards() {
    Workflow workflow = new Workflow("id", "name", "desc", null);
    assertTrue(workflow.getSteps().isEmpty());
    Map<String, Object> noSteps = new HashMap<>();
    noSteps.put("name", "wf");
    Workflow parsed = Workflow.fromMap(noSteps);
    assertTrue(parsed.getSteps().isEmpty());
  }
}

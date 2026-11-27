/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskResult;
import org.junit.jupiter.api.Test;

/** TC-ENG-247/248：ProtocolResultAdapter 状态到 TaskResult 的映射。 */
class ProtocolResultAdapterMappingTest {

  // TC-ENG | nullResultFails
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullResultFails() {
    TaskResult result = ProtocolResultAdapter.toTaskResult(null);
    assertFalse(result.isSuccess());
    assertEquals("remote.no_response", result.getErrorCode());
  }

  // TC-ENG | stateMappingAndFailureCodePrecedence
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stateMappingAndFailureCodePrecedence() {
    SendMessageResult completed =
        SendMessageResult.builder().taskState("TASK_STATE_COMPLETED").build();
    assertTrue(ProtocolResultAdapter.toTaskResult(completed).isSuccess());

    SendMessageResult failed = SendMessageResult.builder().taskState("TASK_STATE_FAILED").build();
    assertFalse(ProtocolResultAdapter.toTaskResult(failed).isSuccess());

    SendMessageResult unknown = SendMessageResult.builder().taskState("NOT_A_STATE").build();
    assertFalse(ProtocolResultAdapter.toTaskResult(unknown).isSuccess());

    SendMessageResult withFailureCode =
        SendMessageResult.builder()
            .taskState("TASK_STATE_COMPLETED")
            .failureCode("negotiation.aborted")
            .failureMessage("aborted")
            .build();
    TaskResult adapted = ProtocolResultAdapter.toTaskResult(withFailureCode);
    assertFalse(adapted.isSuccess());
    assertEquals("negotiation.aborted", adapted.getErrorCode());
  }

  // TC-ENG | standaloneMessageAndUnspecifiedSucceed
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standaloneMessageAndUnspecifiedSucceed() {
    dev.openan.workflow.engine.model.ReceivedMessage message =
        new dev.openan.workflow.engine.model.ReceivedMessage(
            dev.openan.workflow.engine.model.MessageContent.text("hi"),
            java.util.Map.of(),
            java.util.List.of());
    SendMessageResult standalone =
        SendMessageResult.builder().receivedMessages(java.util.List.of(message)).task(null).build();
    assertTrue(ProtocolResultAdapter.toTaskResult(standalone).isSuccess());

    SendMessageResult unspecified =
        SendMessageResult.builder()
            .taskState("TASK_STATE_UNSPECIFIED")
            .receivedMessages(java.util.List.of(message))
            .build();
    assertTrue(ProtocolResultAdapter.toTaskResult(unspecified).isSuccess());
  }

  // TC-ENG | failureMappingCancellationMapsToWorkflowCancelled
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failureMappingCancellationMapsToWorkflowCancelled() {
    TaskResult result =
        FailureMapping.from(new java.util.concurrent.CancellationException("cancelled"));
    org.junit.jupiter.api.Assertions.assertEquals("workflow.cancelled", result.getErrorCode());
  }
}

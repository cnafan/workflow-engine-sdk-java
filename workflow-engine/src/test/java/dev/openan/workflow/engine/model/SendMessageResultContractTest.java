/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-790…798：SendMessageResult 防御性拷贝/输出投影/setter 分支。 */
class SendMessageResultContractTest {

  // TC-ENG-790 | builder null 归一
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderNullsNormalizeToDefaults() {
    SendMessageResult result = SendMessageResult.builder().build();
    assertEquals("", result.getText());
    assertEquals("", result.getTaskState());
    assertEquals(Map.of(), result.getMetadata());
    assertEquals(List.of(), result.getReceivedMessages());
  }

  // TC-ENG-791 | setMetadata null 归一；getMetadata 不可变
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void setMetadataNullAndImmutable() {
    SendMessageResult result = new SendMessageResult();
    result.setMetadata(null);
    assertEquals(Map.of(), result.getMetadata());
    result.setMetadata(new HashMap<>(Map.of("k", "v")));
    assertThrows(UnsupportedOperationException.class, () -> result.getMetadata().put("x", "y"));
  }

  // TC-ENG-792 | setReceivedMessages null 归一；返回不可变
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void setReceivedMessagesNullAndImmutable() {
    SendMessageResult result = new SendMessageResult();
    result.setReceivedMessages(null);
    assertEquals(0, result.getReceivedMessages().size());
    List<ReceivedMessage> messages =
        new ArrayList<>(
            List.of(new ReceivedMessage(MessageContent.text("ok"), Map.of(), List.of())));
    result.setReceivedMessages(messages);
    assertThrows(UnsupportedOperationException.class, () -> result.getReceivedMessages().clear());
  }

  // TC-ENG-793 | 完成态 message 输出计入 getOutputs
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void completedOutputsIncludeMessageParts() {
    ReceivedMessage message =
        new ReceivedMessage(
            new MessageContent(
                List.of(new org.a2aproject.sdk.spec.TextPart("payload")),
                new HashMap<>(),
                java.util.Set.of()),
            Map.of(),
            List.of());
    SendMessageResult result =
        SendMessageResult.builder()
            .taskState("TASK_STATE_COMPLETED")
            .receivedMessages(List.of(message))
            .build();
    assertEquals(1, result.getOutputs().size());
  }

  // TC-ENG-794 | 失败码存在时 message 输出被排除
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failureCodeExcludesMessageOutputs() {
    ReceivedMessage message =
        new ReceivedMessage(
            MessageContent.parts(List.of(new org.a2aproject.sdk.spec.TextPart("x"))),
            Map.of(),
            List.of());
    SendMessageResult result =
        SendMessageResult.builder()
            .taskState("TASK_STATE_COMPLETED")
            .failureCode("business.aborted")
            .receivedMessages(List.of(message))
            .build();
    assertEquals(0, result.getOutputs().size());
  }

  // TC-ENG-795 | 非完成态 message 输出排除
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonCompletedStateExcludesMessageOutputs() {
    ReceivedMessage message =
        new ReceivedMessage(
            MessageContent.parts(List.of(new org.a2aproject.sdk.spec.TextPart("x"))),
            Map.of(),
            List.of());
    SendMessageResult result =
        SendMessageResult.builder()
            .task(
                org.a2aproject.sdk.spec.Task.builder()
                    .id("t")
                    .contextId("c")
                    .status(
                        new org.a2aproject.sdk.spec.TaskStatus(
                            org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING))
                    .build())
            .taskState("TASK_STATE_WORKING")
            .receivedMessages(List.of(message))
            .build();
    assertEquals(0, result.getOutputs().size());
  }

  // TC-ENG-796 | setter 与 lombok 字段
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failureFieldsRoundTrip() {
    SendMessageResult result = new SendMessageResult();
    result.setFailureCode("code");
    result.setFailureMessage("message");
    result.setText("text");
    result.setTaskState("TASK_STATE_FAILED");
    assertEquals("code", result.getFailureCode());
    assertEquals("message", result.getFailureMessage());
    assertEquals("text", result.getText());
    assertTrue(result.toString().contains("code"));
  }

  // TC-ENG-797 | builder 传入可变 Map 被快照化
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderMetadataIsSnapshot() {
    Map<String, Object> source = new HashMap<>(Map.of("k", "v"));
    SendMessageResult result = SendMessageResult.builder().metadata(source).build();
    source.put("extra", "value");
    assertEquals(1, result.getMetadata().size());
  }

  // TC-ENG-798 | builder 传入可变 List 被拷贝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderReceivedMessagesIsCopied() {
    List<ReceivedMessage> source = new ArrayList<>();
    SendMessageResult result = SendMessageResult.builder().receivedMessages(source).build();
    source.add(null);
    assertEquals(0, result.getReceivedMessages().size());
  }
}

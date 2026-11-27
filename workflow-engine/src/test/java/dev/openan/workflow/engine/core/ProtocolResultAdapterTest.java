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

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

class ProtocolResultAdapterTest {

  // TC-ENG | convertsOrderedTextAndStructuredArtifactsToBusinessOutputs
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void convertsOrderedTextAndStructuredArtifactsToBusinessOutputs() {
    Task task =
        Task.builder()
            .id("task-1")
            .contextId("context-1")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .artifacts(
                List.of(
                    Artifact.builder()
                        .artifactId("text-result")
                        .parts(new TextPart("diagnosis"))
                        .build(),
                    Artifact.builder()
                        .artifactId("data-result")
                        .parts(new DataPart(Map.of("alarmCount", 1)))
                        .build()))
            .history(List.of())
            .metadata(Map.of("protocol", "must-not-leak"))
            .build();

    var response =
        ProtocolResultAdapter.toTaskResult(
            SendMessageResult.builder()
                .task(task)
                .receivedMessages(
                    dev.openan.workflow.engine.client.ProtocolResponses.assemble(
                        List.of(new org.a2aproject.sdk.client.TaskEvent(task))))
                .text("flattened-value-must-not-win")
                .taskState("TASK_STATE_COMPLETED")
                .metadata(Map.of("wire", "must-not-leak"))
                .build());

    assertTrue(response.isSuccess());
    assertEquals(List.of("diagnosis", Map.of("alarmCount", 1)), response.getOutputs());
  }

  // TC-ENG | doesNotInventTextFallbackWhenTransportHasNoBusinessContent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void doesNotInventTextFallbackWhenTransportHasNoBusinessContent() {
    var response =
        ProtocolResultAdapter.toTaskResult(
            SendMessageResult.builder()
                .text("diagnosis")
                .taskState("TASK_STATE_COMPLETED")
                .build());

    assertEquals(List.of(), response.getOutputs());
  }

  // TC-ENG | keepsFailedProtocolStateOutOfBusinessOutputs
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void keepsFailedProtocolStateOutOfBusinessOutputs() {
    var response =
        ProtocolResultAdapter.toTaskResult(
            SendMessageResult.builder()
                .text("remote failure detail")
                .taskState("TASK_STATE_FAILED")
                .build());

    assertFalse(response.isSuccess());
    assertEquals(List.of(), response.getOutputs());
    assertEquals("Agent returned state=TASK_STATE_FAILED", response.getError());
  }

  // TC-ENG | unknownStateEndingInUnspecifiedIsNotTreatedAsSuccess
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unknownStateEndingInUnspecifiedIsNotTreatedAsSuccess() {
    var response =
        ProtocolResultAdapter.toTaskResult(
            SendMessageResult.builder()
                .taskState("VENDOR_UNSPECIFIED")
                .receivedMessages(
                    List.of(
                        new dev.openan.workflow.engine.model.ReceivedMessage(
                            dev.openan.workflow.engine.model.MessageContent.text("result"),
                            Map.of(),
                            List.of())))
                .build());

    assertFalse(response.isSuccess());
    assertEquals("Agent returned state=VENDOR_UNSPECIFIED", response.getError());
  }

  // TC-ENG | failedStatusIsRetainedAsEvidenceButOnlyArtifactsBecomePartialOutputs
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failedStatusIsRetainedAsEvidenceButOnlyArtifactsBecomePartialOutputs() {
    var status = dev.openan.workflow.engine.model.MessageContent.text("error: remote rejected");
    var evidence =
        new dev.openan.workflow.engine.model.ReceivedMessage(
            status, Map.of("reason", "rejected"), List.of());
    var failed =
        SendMessageResult.builder()
            .taskState("TASK_STATE_FAILED")
            .receivedMessages(List.of(evidence))
            .build();
    var result = ProtocolResultAdapter.toTaskResult(failed);
    assertFalse(result.isSuccess());
    assertEquals(List.of(), result.getOutputs());
    assertEquals(status, result.getReceivedMessages().get(0).message());

    var partial =
        new dev.openan.workflow.engine.model.ReceivedMessage(
            status,
            Map.of(),
            List.of(
                Artifact.builder()
                    .artifactId("partial")
                    .parts(new DataPart(Map.of("ports", List.of("a", "b"))))
                    .build()));
    failed.setReceivedMessages(List.of(partial));
    assertEquals(
        List.of(Map.of("ports", List.of("a", "b"))),
        ProtocolResultAdapter.toTaskResult(failed).getOutputs());
  }
}

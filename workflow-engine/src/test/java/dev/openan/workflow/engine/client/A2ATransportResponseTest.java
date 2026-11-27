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
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

class A2ATransportResponseTest {

  private static TaskArtifactUpdateEvent artifactUpdate(String id, String text, boolean append) {
    return TaskArtifactUpdateEvent.builder()
        .taskId("task-1")
        .contextId("context-1")
        .artifact(artifact(id, text))
        .append(append)
        .lastChunk(!append)
        .metadata(Map.of())
        .build();
  }

  private static Task task(TaskState state, List<Artifact> artifacts) {
    return Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(new TaskStatus(state))
        .artifacts(artifacts)
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  private static Artifact artifact(String id, String text) {
    return Artifact.builder()
        .artifactId(id)
        .name(id)
        .parts(new TextPart(text))
        .metadata(Map.of())
        .build();
  }

  private static Message message(String text) {
    return Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .messageId("message-1")
        .parts(new TextPart(text))
        .metadata(Map.of())
        .build();
  }

  // TC-ENG | notificationAcknowledgementRequiresANonBlankProtocolState
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notificationAcknowledgementRequiresANonBlankProtocolState() {
    assertFalse(A2ATransport.isAcknowledgementState(null));
    assertFalse(A2ATransport.isAcknowledgementState(""));
    assertFalse(A2ATransport.isAcknowledgementState("  "));
    assertTrue(A2ATransport.isAcknowledgementState("TASK_STATE_WORKING"));
  }

  // TC-ENG | extractsArtifactOnlyOnceAcrossUpdateSnapshotAndTerminalTask
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void extractsArtifactOnlyOnceAcrossUpdateSnapshotAndTerminalTask() {
    Artifact artifact = artifact("result", "diagnosis");
    Task working = task(TaskState.TASK_STATE_WORKING, List.of(artifact));
    Task completed = task(TaskState.TASK_STATE_COMPLETED, List.of(artifact));
    TaskArtifactUpdateEvent update =
        TaskArtifactUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .artifact(artifact)
            .append(false)
            .lastChunk(true)
            .metadata(Map.of())
            .build();

    List<ClientEvent> events =
        List.of(new TaskUpdateEvent(working, update), new TaskEvent(completed));

    assertEquals("diagnosis", A2ATransport.extractResponseText(events));
  }

  // TC-ENG | assemblesAppendChunksForEachArtifact
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void assemblesAppendChunksForEachArtifact() {
    Task task = task(TaskState.TASK_STATE_WORKING, List.of());
    List<ClientEvent> events =
        List.of(
            new TaskUpdateEvent(task, artifactUpdate("result", "part-1", false)),
            new TaskUpdateEvent(task, artifactUpdate("result", "+part-2", true)));

    assertEquals("part-1+part-2", A2ATransport.extractResponseText(events));
  }

  // TC-ENG | fallsBackToDistinctStatusOrMessageTextWhenNoArtifactExists
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fallsBackToDistinctStatusOrMessageTextWhenNoArtifactExists() {
    Message concern = message("need parameter");
    TaskStatus status = new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, concern, null);
    Task task =
        Task.builder()
            .id("task-1")
            .contextId("context-1")
            .status(status)
            .artifacts(List.of())
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskStatusUpdateEvent update =
        new TaskStatusUpdateEvent("task-1", status, "context-1", Map.of());

    assertEquals(
        "need parameter",
        A2ATransport.extractResponseText(
            List.of(new TaskUpdateEvent(task, update), new MessageEvent(concern))));
  }

  // TC-ENG | mixedStreamingTextAndStructuredDataAreBothRetained
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void mixedStreamingTextAndStructuredDataAreBothRetained() {
    Artifact mixed =
        Artifact.builder()
            .artifactId("mixed")
            .parts(
                List.of(
                    new TextPart("diagnosis"),
                    new org.a2aproject.sdk.spec.DataPart(Map.of("ports", List.of("a", "b")))))
            .build();
    Task task = task(TaskState.TASK_STATE_WORKING, List.of());
    var update =
        TaskArtifactUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .artifact(mixed)
            .append(false)
            .lastChunk(true)
            .build();
    assertEquals(
        List.of("diagnosis", Map.of("ports", List.of("a", "b"))),
        ProtocolResponses.assemble(List.of(new TaskUpdateEvent(task, update))).get(0).outputs());
  }
}

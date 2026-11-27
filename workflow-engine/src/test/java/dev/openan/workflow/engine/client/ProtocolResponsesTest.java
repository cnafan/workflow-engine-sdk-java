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

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.a2aproject.sdk.client.*;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;

class ProtocolResponsesTest {
  private static Task task(TaskState state, List<Artifact> artifacts, Message message) {
    return Task.builder()
        .id("task")
        .contextId("context")
        .status(new TaskStatus(state, message, null))
        .artifacts(artifacts)
        .metadata(Map.of("level", "task"))
        .build();
  }

  private static Artifact artifact(String id, String text, Map<String, Object> metadata) {
    return Artifact.builder()
        .artifactId(id)
        .name("report")
        .description("description")
        .parts(new TextPart(text))
        .metadata(metadata)
        .extensions(List.of("urn:test"))
        .build();
  }

  private static TaskArtifactUpdateEvent update(Artifact artifact, boolean append) {
    return TaskArtifactUpdateEvent.builder()
        .taskId("task")
        .contextId("context")
        .artifact(artifact)
        .append(append)
        .lastChunk(false)
        .build();
  }

  // TC-ENG | appendDoesNotCountSdkSnapshotTwiceAndReplaceIsAuthoritative
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void appendDoesNotCountSdkSnapshotTwiceAndReplaceIsAuthoritative() {
    Artifact first = artifact("a", "first", Map.of("x", 1));
    Artifact second = artifact("a", "second", Map.of("y", 2));
    Task seed =
        task(
            TaskState.TASK_STATE_WORKING,
            List.of(),
            Message.builder()
                .messageId("progress")
                .role(Message.Role.ROLE_AGENT)
                .parts(new TextPart("working"))
                .build());
    Task afterFirst =
        org.a2aproject.sdk.spec.util.Utils.appendArtifactToTask(seed, update(first, false), "task");
    Task afterSecond =
        org.a2aproject.sdk.spec.util.Utils.appendArtifactToTask(
            afterFirst, update(second, true), "task");
    List<ClientEvent> events =
        new ArrayList<>(
            List.of(
                new TaskUpdateEvent(afterFirst, update(first, false)),
                new TaskUpdateEvent(afterSecond, update(second, true))));
    var partial = ProtocolResponses.assemble(events).get(0);
    assertEquals(List.of("first", "second"), partial.outputs());
    assertEquals(Map.of("x", 1, "y", 2), partial.artifacts().get(0).metadata());
    Artifact replacement = artifact("a", "replaced", Map.of("final", true));
    Artifact other = artifact("b", "city2", Map.of());
    events.add(
        new TaskUpdateEvent(
            task(TaskState.TASK_STATE_WORKING, List.of(replacement), null),
            update(replacement, false)));
    events.add(
        new TaskEvent(task(TaskState.TASK_STATE_COMPLETED, List.of(replacement, other), null)));
    var completed = ProtocolResponses.assemble(events).get(0);
    assertEquals(List.of("replaced", "city2"), completed.outputs());
    assertEquals(
        List.of("a", "b"), completed.artifacts().stream().map(Artifact::artifactId).toList());
    assertEquals(Map.of("final", true), completed.artifacts().get(0).metadata());
  }

  // TC-ENG | failureRetainsPartialArtifactButNotErrorTextAsOutput
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failureRetainsPartialArtifactButNotErrorTextAsOutput() {
    Message error =
        Message.builder()
            .messageId("error")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("failed detail"))
            .metadata(Map.of("level", "message"))
            .build();
    var received =
        ProtocolResponses.assemble(
                List.of(
                    new TaskEvent(
                        task(
                            TaskState.TASK_STATE_FAILED,
                            List.of(artifact("a", "partial", Map.of("level", "artifact"))),
                            error))))
            .get(0);
    assertEquals(List.of("partial"), received.outputs());
    assertEquals("message", received.message().metadata().get("level"));
    assertEquals("task", received.taskMetadata().get("level"));
    assertEquals("artifact", received.artifacts().get(0).metadata().get("level"));
  }

  // TC-ENG | metadataOnlyAndNestedDataRemainAvailable
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void metadataOnlyAndNestedDataRemainAvailable() {
    var received =
        ProtocolResponses.assemble(
                List.of(new TaskEvent(task(TaskState.TASK_STATE_COMPLETED, List.of(), null))))
            .get(0);
    assertTrue(received.outputs().isEmpty());
    assertEquals("task", received.taskMetadata().get("level"));
    Artifact nested =
        Artifact.builder().artifactId("a").parts(new DataPart(List.of(List.of(1, 2)))).build();
    assertEquals(
        List.of(List.of(List.of(1, 2))),
        ProtocolResponses.assemble(
                List.of(new TaskEvent(task(TaskState.TASK_STATE_COMPLETED, List.of(nested), null))))
            .get(0)
            .outputs());
  }

  // TC-ENG | incrementalDeliveryDoesNotReplayOrRetainPriorStandaloneMessages
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void incrementalDeliveryDoesNotReplayOrRetainPriorStandaloneMessages() {
    ProtocolResponses.Accumulator accumulator = new ProtocolResponses.Accumulator();
    Message first =
        Message.builder()
            .messageId("first")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("one"))
            .build();
    Message second =
        Message.builder()
            .messageId("second")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("two"))
            .build();

    assertEquals(
        List.of("one"), accumulator.acceptIncrementally(new MessageEvent(first)).get(0).outputs());
    assertEquals(
        List.of("two"), accumulator.acceptIncrementally(new MessageEvent(second)).get(0).outputs());
    assertTrue(accumulator.snapshots().isEmpty());
  }
}

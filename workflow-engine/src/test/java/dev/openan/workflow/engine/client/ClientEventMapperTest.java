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

import java.util.List;
import java.util.Map;
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

class ClientEventMapperTest {

  // TC-ENG-001 | TaskEvent 映射为 kind=task 且携带状态与文本
  //
  // 输入: TaskEvent(Task state=COMPLETED, artifact 含文本 "result body", task metadata 空)
  // 预期输出: event_kind=task, task_id/state/is_final/text 正确
  // 断言点: 各键的具体值；防回归: 提取逻辑改动即失败
  @Test
  void taskEventMapsKindStateAndText() {
    Task task = taskWithState(TaskState.TASK_STATE_COMPLETED);
    Map<String, Object> data = ClientEventMapper.toMap(new TaskEvent(task), "agent-a");

    assertEquals("task", data.get("event_kind"));
    assertEquals("task-1", data.get("task_id"));
    assertEquals("TASK_STATE_COMPLETED", data.get("state"));
    assertEquals(Boolean.TRUE, data.get("is_final"));
    assertEquals("agent-a", data.get("agent"));
    assertEquals("result body", data.get("text"));
  }

  // TC-ENG-002 | TaskUpdateEvent(status) 映射 kind=status，状态消息文本进入 text
  //
  // 输入: TaskStatusUpdateEvent(state=WORKING, message 含 "working on it", metadata 非空)
  // 预期输出: event_kind=status, text 与 metadata 原样透传
  // 断言点: text、metadata 键；防回归: 消息文本提取被删即失败
  @Test
  void statusUpdateMapsMessageTextAndMetadata() {
    TaskStatusUpdateEvent sue =
        TaskStatusUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING, message("working on it"), null))
            .metadata(Map.of("k1", "v1"))
            .build();
    Map<String, Object> data = ClientEventMapper.toMap(new TaskUpdateEvent(task(), sue), "agent-a");

    assertEquals("status", data.get("event_kind"));
    assertEquals("task-1", data.get("task_id"));
    assertEquals("TASK_STATE_WORKING", data.get("state"));
    assertEquals("working on it", data.get("text"));
    assertEquals(Map.of("k1", "v1"), data.get("metadata"));
  }

  // TC-ENG-003 | 状态消息为 null 且 metadata 为空时不产生 text/metadata 键
  //
  // 输入: TaskStatusUpdateEvent(state=SUBMITTED, 无消息, 空 metadata)
  // 预期输出: map 不含 text 与 metadata 键
  // 断言点: containsKey 为 false；防回归: 空串进入 text 即失败
  @Test
  void statusUpdateWithoutTextOmitsKeys() {
    TaskStatusUpdateEvent sue =
        TaskStatusUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED, null, null))
            .metadata(Map.of())
            .build();
    Map<String, Object> data = ClientEventMapper.toMap(new TaskUpdateEvent(task(), sue), "agent-a");

    assertEquals("status", data.get("event_kind"));
    assertFalse(data.containsKey("text"));
    assertFalse(data.containsKey("metadata"));
  }

  // TC-ENG-004 | TaskUpdateEvent(artifact) 映射 kind=artifact 及分块字段
  //
  // 输入: TaskArtifactUpdateEvent(artifact "a-1"/"report", append=true, artifact metadata 非空)
  // 预期输出: artifact_id/artifact_name/append/last_chunk/text/metadata 正确
  // 断言点: 分块布尔值与 artifact 优先的 metadata；防回归: append/last_chunk 弄反即失败
  @Test
  void artifactUpdateMapsArtifactFields() {
    TaskArtifactUpdateEvent ae =
        TaskArtifactUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .artifact(
                Artifact.builder()
                    .artifactId("a-1")
                    .name("report")
                    .parts(List.of(new TextPart("chunk text")))
                    .metadata(Map.of("src", "artifact"))
                    .build())
            .append(true)
            .lastChunk(false)
            .metadata(Map.of("src", "event"))
            .build();
    Map<String, Object> data = ClientEventMapper.toMap(new TaskUpdateEvent(task(), ae), "agent-a");

    assertEquals("artifact", data.get("event_kind"));
    assertEquals("a-1", data.get("artifact_id"));
    assertEquals("report", data.get("artifact_name"));
    assertEquals(Boolean.TRUE, data.get("append"));
    assertEquals(Boolean.FALSE, data.get("last_chunk"));
    assertEquals("chunk text", data.get("text"));
    assertEquals(Map.of("src", "artifact"), data.get("metadata"));
  }

  // TC-ENG-005 | artifact 无 metadata 时回退到事件级 metadata
  //
  // 输入: artifact.metadata 为空 map, event metadata 非空
  // 预期输出: metadata 取事件级
  // 断言点: metadata 值；防回归: 回退顺序写反即失败
  @Test
  void artifactWithoutMetadataFallsBackToEventMetadata() {
    TaskArtifactUpdateEvent ae =
        TaskArtifactUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .artifact(
                Artifact.builder()
                    .artifactId("a-2")
                    .name("log")
                    .parts(List.of(new TextPart("line")))
                    .metadata(Map.of())
                    .build())
            .append(false)
            .lastChunk(true)
            .metadata(Map.of("src", "event"))
            .build();
    Map<String, Object> data = ClientEventMapper.toMap(new TaskUpdateEvent(task(), ae), "agent-a");

    assertEquals(Map.of("src", "event"), data.get("metadata"));
  }

  // TC-ENG-006 | MessageEvent 映射 kind=message 与角色/文本
  //
  // 输入: MessageEvent(role=ROLE_AGENT, 文本 "hi")
  // 预期输出: kind=message, role, text
  // 断言点: kind/role/text；防回归: 文本提取被删即失败
  // 注: toMap 的未知事件兜底分支因 ClientEvent 为 sealed 接口（仅 3 个实现）而不可达，
  //     属防御性代码，测试无法触发（见 TEST_DESIGN.md §6）
  @Test
  void messageEventMapsRoleAndText() {
    Message msg = message("hi");
    Map<String, Object> data = ClientEventMapper.toMap(new MessageEvent(msg), "agent-a");
    assertEquals("message", data.get("event_kind"));
    assertEquals("ROLE_AGENT", data.get("role"));
    assertEquals("hi", data.get("text"));
  }

  private static Task task() {
    return Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  private static Task taskWithState(TaskState state) {
    return Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(new TaskStatus(state))
        .artifacts(
            List.of(
                Artifact.builder()
                    .artifactId("a-1")
                    .name("result")
                    .parts(List.of(new TextPart("result body")))
                    .metadata(Map.of())
                    .build()))
        .history(List.of())
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
}

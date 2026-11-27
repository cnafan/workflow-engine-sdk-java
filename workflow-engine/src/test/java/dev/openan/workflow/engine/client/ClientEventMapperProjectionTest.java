/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-930…934：ClientEventMapper 文本/元数据/事件类型投影分支。 */
class ClientEventMapperProjectionTest {

  private Task task(TaskState state, String messageText) {
    return Task.builder()
        .id("t")
        .contextId("c")
        .status(
            new TaskStatus(
                state,
                messageText == null
                    ? null
                    : Message.builder()
                        .messageId("m")
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart(messageText))
                        .metadata(Map.of("k", "v"))
                        .build(),
                null))
        .history(List.of())
        .metadata(Map.of("tk", "tv"))
        .build();
  }

  // TC-ENG-930 | TaskEvent 含任务元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskEventWithTextAndMetadata() {
    Map<String, Object> map =
        ClientEventMapper.toMap(
            new TaskEvent(task(TaskState.TASK_STATE_COMPLETED, "done")), "agent");
    assertEquals("task", map.get("event_kind"));
    assertTrue(map.containsKey("metadata"));
    assertEquals(Boolean.TRUE, map.get("is_final"));
  }

  // TC-ENG-931 | TaskEvent 无文本无元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskEventWithoutTextOrMetadata() {
    Map<String, Object> map =
        ClientEventMapper.toMap(new TaskEvent(task(TaskState.TASK_STATE_WORKING, null)), "agent");
    assertFalse(map.containsKey("text"));
    assertTrue(map.containsKey("metadata"));
  }

  // TC-ENG-932 | status update 带文本与元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void statusUpdateProjection() {
    TaskStatusUpdateEvent status =
        TaskStatusUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .status(
                new TaskStatus(
                    TaskState.TASK_STATE_WORKING,
                    Message.builder()
                        .messageId("m")
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart("working"))
                        .build(),
                    null))
            .metadata(Map.of("sk", "sv"))
            .build();
    Map<String, Object> map =
        ClientEventMapper.toMap(
            new TaskUpdateEvent(task(TaskState.TASK_STATE_WORKING, null), status), "agent");
    assertEquals("status", map.get("event_kind"));
    assertEquals("working", map.get("text"));
    assertTrue(map.containsKey("metadata"));
  }

  // TC-ENG-933 | artifact update 带文本；事件级 metadata 回退
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactUpdateProjectionAndEventMetadataFallback() {
    TaskArtifactUpdateEvent artifact =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a-1")
                    .name("report")
                    .parts(List.of(new TextPart("body")))
                    .build())
            .append(true)
            .lastChunk(true)
            .metadata(Map.of("ek", "ev"))
            .build();
    Map<String, Object> map =
        ClientEventMapper.toMap(
            new TaskUpdateEvent(task(TaskState.TASK_STATE_WORKING, null), artifact), "agent");
    assertEquals("artifact", map.get("event_kind"));
    assertEquals("a-1", map.get("artifact_id"));
    assertEquals("body", map.get("text"));
    assertTrue(map.containsKey("metadata"));
  }

  // TC-ENG-934 | MessageEvent 投影
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageEventProjection() {
    MessageEvent event =
        new MessageEvent(
            Message.builder()
                .messageId("m")
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart("hi"))
                .metadata(Map.of())
                .build());
    Map<String, Object> map = ClientEventMapper.toMap(event, "agent");
    assertEquals("message", map.get("event_kind"));
    assertEquals("hi", map.get("text"));
    assertEquals("ROLE_USER", map.get("role"));
  }
}

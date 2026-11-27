/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-250…253：ClientEventMapper.toMap 四类事件投影。 */
class ClientEventMapperTypesTest {

  private static Task task(TaskState state, String text) {
    Task.Builder builder =
        Task.builder().id("t-1").contextId("c-1").status(new TaskStatus(state)).history(List.of());
    if (text != null) {
      builder.artifacts(
          List.of(
              Artifact.builder()
                  .artifactId("a-1")
                  .name("out")
                  .parts(List.of(new TextPart(text)))
                  .metadata(Map.of())
                  .build()));
    }
    builder.metadata(Map.of("k", "v"));
    return builder.build();
  }

  // TC-ENG-250 | TaskEvent：终态标记 + 文本 + 元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskEventProjectsStateTextMetadata() {
    Map<String, Object> terminal =
        ClientEventMapper.toMap(
            new TaskEvent(task(TaskState.TASK_STATE_COMPLETED, "done")), "agent-a");
    assertEquals("task", terminal.get("event_kind"));
    assertEquals("t-1", terminal.get("task_id"));
    assertEquals("TASK_STATE_COMPLETED", terminal.get("state"));
    assertEquals(Boolean.TRUE, terminal.get("is_final"));
    assertEquals("done", terminal.get("text"));
    assertTrue(((Map<?, ?>) terminal.get("metadata")).containsKey("k"));

    Map<String, Object> working =
        ClientEventMapper.toMap(new TaskEvent(task(TaskState.TASK_STATE_WORKING, null)), "agent-a");
    assertEquals(Boolean.FALSE, working.get("is_final"));
    assertFalse(working.containsKey("text"));
  }

  // TC-ENG-251 | TaskUpdateEvent status：状态消息文本 + 事件级元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void statusUpdateProjectsMessageText() {
    Message statusMessage =
        Message.builder()
            .messageId("m-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("waiting input"))
            .metadata(Map.of())
            .build();
    TaskStatus status = new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null);
    Task task =
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskUpdateEvent event =
        new TaskUpdateEvent(
            task,
            TaskStatusUpdateEvent.builder()
                .taskId("t-1")
                .contextId("c-1")
                .status(status)
                .metadata(Map.of("ek", "ev"))
                .build());
    Map<String, Object> map = ClientEventMapper.toMap(event, "agent-a");
    assertEquals("status", map.get("event_kind"));
    assertEquals("waiting input", map.get("text"));
    assertEquals("ev", ((Map<?, ?>) map.get("metadata")).get("ek"));
  }

  // TC-ENG-252 | TaskUpdateEvent artifact：append/lastChunk 标记 + artifact 元数据回退
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactUpdateProjectsFlagsAndMetadata() {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a-1")
            .name("report")
            .parts(List.of(new TextPart("chunk")))
            .metadata(new LinkedHashMap<>(Map.of("am", "av")))
            .build();
    Task task =
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskUpdateEvent withArtifactMeta =
        new TaskUpdateEvent(
            task,
            TaskArtifactUpdateEvent.builder()
                .taskId("t-1")
                .contextId("c-1")
                .artifact(artifact)
                .append(true)
                .lastChunk(false)
                .build());
    Map<String, Object> map = ClientEventMapper.toMap(withArtifactMeta, "agent-a");
    assertEquals("artifact", map.get("event_kind"));
    assertEquals("a-1", map.get("artifact_id"));
    assertEquals("report", map.get("artifact_name"));
    assertEquals(Boolean.TRUE, map.get("append"));
    assertEquals(Boolean.FALSE, map.get("last_chunk"));
    assertEquals("chunk", map.get("text"));
    assertEquals("av", ((Map<?, ?>) map.get("metadata")).get("am"));

    Artifact plain =
        Artifact.builder().artifactId("a-2").name("r2").parts(List.of(new TextPart("x"))).build();
    TaskUpdateEvent withEventMeta =
        new TaskUpdateEvent(
            task,
            TaskArtifactUpdateEvent.builder()
                .taskId("t-1")
                .contextId("c-1")
                .artifact(plain)
                .append(false)
                .lastChunk(true)
                .metadata(Map.of("fallback", "fb"))
                .build());
    Map<String, Object> fallback = ClientEventMapper.toMap(withEventMeta, "agent-a");
    assertEquals("fb", ((Map<?, ?>) fallback.get("metadata")).get("fallback"));
  }

  // TC-ENG-253 | MessageEvent：角色与元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageEventProjectsRole() {
    Message message =
        Message.builder()
            .messageId("m-2")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("hello"))
            .metadata(Map.of("mk", "mv"))
            .build();
    Map<String, Object> map = ClientEventMapper.toMap(new MessageEvent(message), "agent-a");
    assertEquals("message", map.get("event_kind"));
    assertEquals("ROLE_AGENT", map.get("role"));
    assertEquals("hello", map.get("text"));
    assertEquals("mv", ((Map<?, ?>) map.get("metadata")).get("mk"));
    assertEquals("agent-a", map.get("agent"));
  }

  // 保持 AgentCard 导入被引用（静态检查不报错）
  @SuppressWarnings("unused")
  private static final Class<?> KEEP = AgentCard.class;
}

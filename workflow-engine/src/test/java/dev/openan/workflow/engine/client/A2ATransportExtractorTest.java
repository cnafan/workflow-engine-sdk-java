/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-990…996: static text/metadata/task extractor defensive branches. */
class A2ATransportExtractorTest {

  private static Task task(String state, List<Artifact> artifacts, Map<String, Object> meta) {
    return Task.builder()
        .id("t")
        .contextId("c")
        .status(new TaskStatus(TaskState.valueOf(state)))
        .artifacts(artifacts)
        .history(List.of())
        .metadata(meta)
        .build();
  }

  // TC-ENG-990 | extractTextFromArtifact ignores non-TextPart parts
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonTextPartsAreIgnoredByArtifactExtractor() {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a")
            .parts(List.of(new DataPart(Map.of("k", "v")), new TextPart("text")))
            .build();
    StringBuilder sb = new StringBuilder();
    A2ATransport.extractTextFromArtifact(artifact, sb);
    assertEquals("text", sb.toString());
  }

  // TC-ENG-991 | extractTextFromTask with null artifacts is a no-op
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullArtifactsOnTaskAreSafe() {
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .history(List.of())
            .metadata(Map.of())
            .build();
    StringBuilder sb = new StringBuilder();
    A2ATransport.extractTextFromTask(task, sb);
    assertEquals(0, sb.length());
  }

  // TC-ENG-992 | extractTextFromMessage null message is safe
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullMessageIsSafe() {
    StringBuilder sb = new StringBuilder();
    A2ATransport.extractTextFromMessage(null, sb);
    assertEquals(0, sb.length());
  }

  // TC-ENG-993 | message-only events contribute fallback text and metadata
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageEventContributesTextAndMetadata() {
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("fallback"))
            .metadata(Map.of("mk", "mv"))
            .build();
    List<ClientEvent> events = List.of(new MessageEvent(message));
    assertEquals("fallback", A2ATransport.extractResponseText(events));
    assertEquals("mv", A2ATransport.extractResponseMetadata(events).get("mk"));
  }

  // TC-ENG-994 | mergeTaskMetadata merges artifact metadata
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void mergeTaskMetadataIncludesArtifacts() {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a")
            .parts(List.of(new TextPart("x")))
            .metadata(Map.of("ak", "av"))
            .build();
    Task task = task("TASK_STATE_COMPLETED", List.of(artifact), Map.of("tk", "tv"));
    Map<String, Object> metadata = new java.util.HashMap<>();
    A2ATransport.mergeTaskMetadata(task, metadata);
    assertEquals("tv", metadata.get("tk"));
    assertEquals("av", metadata.get("ak"));
  }

  // TC-ENG-995 | mergeTaskMetadata null task is a no-op
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void mergeNullTaskIsNoop() {
    Map<String, Object> metadata = new java.util.HashMap<>();
    A2ATransport.mergeTaskMetadata(null, metadata);
    assertTrue(metadata.isEmpty());
  }

  // TC-ENG-996 | status update with message metadata merges both event and message maps
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void statusUpdateMetadataMergesStatusAndMessage() {
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("note"))
            .metadata(Map.of("msgKey", "msgValue"))
            .build();
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING, message, null))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskStatusUpdateEvent status =
        TaskStatusUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING, message, null))
            .metadata(Map.of("eventKey", "eventValue"))
            .build();
    Map<String, Object> metadata =
        A2ATransport.extractResponseMetadata(List.of(new TaskUpdateEvent(task, status)));
    assertEquals("eventValue", metadata.get("eventKey"));
    assertEquals("msgValue", metadata.get("msgKey"));
  }

  // TC-ENG-997 | artifact update without an artifact id/name uses index key and keeps text
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unnamedArtifactUsesIndexKeyAndText() {
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskArtifactUpdateEvent event =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder()
                    .artifactId(" ")
                    .name(" ")
                    .parts(List.of(new TextPart("body")))
                    .build())
            .append(false)
            .lastChunk(true)
            .build();
    String text = A2ATransport.extractResponseText(List.of(new TaskUpdateEvent(task, event)));
    assertEquals("body", text);
  }

  // TC-ENG-998 | extractResponseTask returns the last task snapshot
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void extractResponseTaskReturnsLastSnapshot() {
    Task first =
        Task.builder()
            .id("t1")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    Task last =
        Task.builder()
            .id("t2")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .history(List.of())
            .metadata(Map.of())
            .build();
    Task result =
        A2ATransport.extractResponseTask(List.of(new TaskEvent(first), new TaskEvent(last)));
    assertEquals("t2", result.id());
  }

  // TC-ENG-999 | extractResponseTaskState honors status updates and terminal events
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void extractResponseTaskStateFromStatusUpdate() {
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskStatusUpdateEvent status =
        TaskStatusUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .build();
    String state =
        A2ATransport.extractResponseTaskState(List.of(new TaskUpdateEvent(task, status)));
    assertEquals("TASK_STATE_COMPLETED", state);
  }
}

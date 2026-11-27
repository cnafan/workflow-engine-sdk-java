/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-850…854：ProtocolResponses.Accumulator 直接驱动的分支覆盖。 */
class ProtocolResponsesAccumulatorTest {

  private Object newAccumulator() throws Exception {
    Class<?> cls = Class.forName("dev.openan.workflow.engine.client.ProtocolResponses$Accumulator");
    Constructor<?> ctor = cls.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }

  private void accept(Object accumulator, org.a2aproject.sdk.client.ClientEvent event)
      throws Exception {
    Method m =
        accumulator
            .getClass()
            .getDeclaredMethod("accept", org.a2aproject.sdk.client.ClientEvent.class);
    m.setAccessible(true);
    m.invoke(accumulator, event);
  }

  private List<?> snapshots(Object accumulator) throws Exception {
    Method m = accumulator.getClass().getDeclaredMethod("snapshots");
    m.setAccessible(true);
    return (List<?>) m.invoke(accumulator);
  }

  private List<?> acceptIncrementally(
      Object accumulator, org.a2aproject.sdk.client.ClientEvent event) throws Exception {
    Method m =
        accumulator
            .getClass()
            .getDeclaredMethod("acceptIncrementally", org.a2aproject.sdk.client.ClientEvent.class);
    m.setAccessible(true);
    return (List<?>) m.invoke(accumulator, event);
  }

  private Task taskWithArtifact(String artifactId) {
    return Task.builder()
        .id("t")
        .contextId("c")
        .status(new TaskStatus(org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING))
        .artifacts(
            List.of(
                Artifact.builder()
                    .artifactId(artifactId)
                    .parts(List.of(new TextPart("body")))
                    .build()))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  // TC-ENG-850 | MessageEvent 进入 messages 快照
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageEventProducesMessageSnapshot() throws Exception {
    Object accumulator = newAccumulator();
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("hi"))
            .metadata(Map.of())
            .build();
    accept(accumulator, new MessageEvent(message));
    List<?> snapshots = snapshots(accumulator);
    assertEquals(1, snapshots.size());
  }

  // TC-ENG-851 | artifact 追加：首次（无 previous）放入
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactUpdateFirstFrameIsKept() throws Exception {
    Object accumulator = newAccumulator();
    Task task = taskWithArtifact("a-1");
    TaskArtifactUpdateEvent artifactEvent =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder().artifactId("a-1").parts(List.of(new TextPart("chunk"))).build())
            .append(false)
            .lastChunk(true)
            .build();
    accept(accumulator, new TaskUpdateEvent(task, artifactEvent));
    assertEquals(1, snapshots(accumulator).size());
  }

  // TC-ENG-852 | acceptIncrementally：MessageEvent 立即返回消息；Task 事件返回任务快照
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void incrementalAcceptBranches() throws Exception {
    Object accumulator = newAccumulator();
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("hi"))
            .build();
    assertEquals(1, acceptIncrementally(accumulator, new MessageEvent(message)).size());
    Task task = taskWithArtifact("a-2");
    assertEquals(1, acceptIncrementally(accumulator, new TaskEvent(task)).size());
  }

  // TC-ENG-853 | status 更新携带 message 与 artifact 种子
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void statusUpdateSeedsArtifactsAndMessage() throws Exception {
    Object accumulator = newAccumulator();
    Task task = taskWithArtifact("a-3");
    TaskStatusUpdateEvent statusEvent =
        TaskStatusUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .status(
                new TaskStatus(
                    org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING,
                    Message.builder()
                        .messageId("sm")
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart("status"))
                        .build(),
                    null))
            .build();
    accept(accumulator, new TaskUpdateEvent(task, statusEvent));
    assertFalse(snapshots(accumulator).isEmpty());
  }

  // TC-ENG-854 | 权威 TaskEvent 清空并替换 artifact
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void authoritativeTaskReplacesArtifacts() throws Exception {
    Object accumulator = newAccumulator();
    accept(accumulator, new TaskEvent(taskWithArtifact("old")));
    accept(accumulator, new TaskEvent(taskWithArtifact("new")));
    List<?> snapshots = snapshots(accumulator);
    assertTrue(snapshots.stream().anyMatch(item -> item.toString().contains("new")));
  }

  // TC-ENG-855 | append=true 合并两帧 parts 与 metadata
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void appendTrueMergesPartsAndMetadata() throws Exception {
    Object accumulator = newAccumulator();
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskArtifactUpdateEvent first =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder()
                    .artifactId("a")
                    .name("report")
                    .parts(List.of(new TextPart("one-")))
                    .metadata(Map.of("k1", "v1"))
                    .build())
            .append(false)
            .lastChunk(false)
            .build();
    TaskArtifactUpdateEvent second =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder()
                    .artifactId("a")
                    .parts(List.of(new TextPart("two")))
                    .metadata(Map.of("k2", "v2"))
                    .build())
            .append(true)
            .lastChunk(true)
            .build();
    accept(accumulator, new TaskUpdateEvent(task, first));
    accept(accumulator, new TaskUpdateEvent(task, second));
    List<?> snapshots = snapshots(accumulator);
    String joined = snapshots.toString();
    assertTrue(joined.contains("one-"));
    assertTrue(joined.contains("two"));
    assertTrue(joined.contains("v1"));
    assertTrue(joined.contains("v2"));
  }

  // TC-ENG-856 | snapshot(null task) 不抛且不改状态
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullTaskSnapshotIsNoop() throws Exception {
    Object accumulator = newAccumulator();
    Method m = accumulator.getClass().getDeclaredMethod("snapshot", Task.class, boolean.class);
    m.setAccessible(true);
    m.invoke(accumulator, null, true);
    assertEquals(0, snapshots(accumulator).size());
  }
}

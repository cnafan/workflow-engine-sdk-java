/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-970…977：A2ATransport artifact 键/凭据构造/taskState 分支。 */
class A2ATransportArtifactKeyTest {

  private static Artifact artifact(String id, String name, String text) {
    Artifact.Builder builder =
        Artifact.builder().parts(List.of(new TextPart(text))).metadata(Map.of());
    if (id != null) builder.artifactId(id);
    if (name != null) builder.name(name);
    return builder.build();
  }

  private static String artifactKey(Artifact a, int index) throws Exception {
    Method m = A2ATransport.class.getDeclaredMethod("artifactKey", Artifact.class, int.class);
    m.setAccessible(true);
    return (String) m.invoke(null, a, index);
  }

  // TC-ENG-970 | artifactKey：id 优先
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactKeyPrefersId() throws Exception {
    assertEquals("id-1", artifactKey(artifact("id-1", "name", "x"), 0));
  }

  // TC-ENG-971 | artifactKey：空白 id 回退 name
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactKeyFallsBackToName() throws Exception {
    assertEquals("name-1", artifactKey(artifact(" ", "name-1", "x"), 0));
  }

  // TC-ENG-972 | artifactKey：id/name 都空 → artifact-<index>
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactKeyFallsBackToIndex() throws Exception {
    assertEquals("artifact-3", artifactKey(artifact(" ", " ", "x"), 3));
  }

  // TC-ENG-973 | 追加 artifact：append=true 多帧拼接
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void appendedArtifactsConcatenate() {
    Artifact first = artifact("a", null, "one-");
    Artifact second = artifact("a", null, "two");
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    TaskArtifactUpdateEvent e1 =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(first)
            .append(false)
            .lastChunk(false)
            .build();
    TaskArtifactUpdateEvent e2 =
        TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(second)
            .append(true)
            .lastChunk(true)
            .build();
    String text =
        A2ATransport.extractResponseText(
            List.of(new TaskUpdateEvent(task, e1), new TaskUpdateEvent(task, e2)));
    assertEquals("one-two", text);
  }

  // TC-ENG-974 | captureTaskArtifacts：null task/null artifacts 不崩
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void captureNullTaskAndArtifactsIsSafe() throws Exception {
    Method m = A2ATransport.class.getDeclaredMethod("captureTaskArtifacts", Task.class, Map.class);
    m.setAccessible(true);
    Map<String, String> sink = new HashMap<>();
    m.invoke(null, null, sink);
    m.invoke(
        null,
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .artifacts(null)
            .history(List.of())
            .metadata(Map.of())
            .build(),
        sink);
    assertTrue(sink.isEmpty());
  }

  // TC-ENG-975 | 无 artifactId/name 的快照 artifact 用索引键
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void snapshotArtifactsUseIndexKey() {
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .artifacts(List.of(artifact(" ", " ", "body")))
            .history(List.of())
            .metadata(Map.of())
            .build();
    String text = A2ATransport.extractResponseText(List.of(new TaskEvent(task)));
    assertEquals("body", text);
  }

  // TC-ENG-976 | 凭据 Map 配置构造路径（credentialsConfig 非空）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructsWithCredentialsMap() {
    AgentCard card =
        org.a2aproject.sdk.spec.AgentCard.builder()
            .name("a")
            .description("d")
            .version("1")
            .capabilities(
                new org.a2aproject.sdk.spec.AgentCapabilities(false, false, false, List.of()))
            .defaultInputModes(List.of("text/plain"))
            .defaultOutputModes(List.of("text/plain"))
            .skills(List.of())
            .supportedInterfaces(
                List.of(
                    new org.a2aproject.sdk.spec.AgentInterface(
                        "http://127.0.0.1:1/a", "HTTP+JSON", "1.0", "")))
            .build();
    Map<String, Map<String, Map<String, Object>>> credentials = new HashMap<>();
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .credentialsConfig(credentials)
            .sendTimeoutSeconds(2)
            .notificationAckTimeoutSeconds(2)
            .build();
    try (A2ATransport transport = new A2ATransport(List.of(card), null, config)) {
      assertNotNull(transport.getCard("a"));
    }
  }

  // TC-ENG-977 | 空事件列表 → 空文本
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyEventsProduceEmptyText() {
    assertEquals("", A2ATransport.extractResponseText(List.of()));
  }
}

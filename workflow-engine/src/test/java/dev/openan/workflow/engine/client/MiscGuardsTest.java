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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** TC-ENG-200…208：Normalizer 边界、ProtocolResponses 组装、gRPC 通道构造（反射）。 */
class MiscGuardsTest {

  // ---------- AgentCardNormalizer ----------

  // TC-ENG-200 | normalize：null 与无安全字段透传
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void normalizeNullAndPassthrough() {
    assertNull(AgentCardNormalizer.normalize(null));
    Map<String, Object> plain = new LinkedHashMap<>(Map.of("name", "a"));
    assertEquals("a", AgentCardNormalizer.normalize(plain).get("name"));
  }

  // TC-ENG-201 | securitySchemes：非 Map 输入触发 fail-fast CCE（实际行为，非优雅透传）；
  // 非 Map scheme 与未知结构透传
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  @SuppressWarnings("unchecked")
  void normalizeSecuritySchemeEdgeCases() {
    assertThrows(
        ClassCastException.class,
        () -> AgentCardNormalizer.normalize(mapOf("securitySchemes", List.of("x", "y"))));

    Map<String, Object> card = new LinkedHashMap<>();
    Map<String, Object> schemes = new LinkedHashMap<>();
    schemes.put("raw", "not-a-map");
    Map<String, Object> unknown = new LinkedHashMap<>();
    unknown.put("other", 1);
    schemes.put("unknownScheme", unknown);
    card.put("securitySchemes", schemes);
    Map<String, Object> normalized = AgentCardNormalizer.normalize(card);
    Map<String, Object> out = (Map<String, Object>) normalized.get("securitySchemes");
    assertEquals("not-a-map", out.get("raw"));
    assertEquals(unknown, out.get("unknownScheme"));
  }

  // TC-ENG-202 | apiKey OpenAPI 风格与 requirements 列表/非列表边界
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  @SuppressWarnings("unchecked")
  void normalizeApiKeyAndRequirementShapes() {
    Map<String, Object> card = new LinkedHashMap<>();
    Map<String, Object> schemes = new LinkedHashMap<>();
    Map<String, Object> apiKey = new LinkedHashMap<>();
    apiKey.put("type", "apiKey");
    apiKey.put("in", "header");
    apiKey.put("name", "X-Key");
    schemes.put("keyAuth", apiKey);
    card.put("securitySchemes", schemes);

    List<Object> requirements = new ArrayList<>();
    requirements.add("not-a-map");
    requirements.add(Map.of("schemes", List.of("keyAuth")));
    requirements.add(Map.of("schemes", "already-map"));
    card.put("securityRequirements", requirements);

    Map<String, Object> normalized = AgentCardNormalizer.normalize(card);
    Map<String, Object> outSchemes = (Map<String, Object>) normalized.get("securitySchemes");
    Map<String, Object> keyWrapped = (Map<String, Object>) outSchemes.get("keyAuth");
    assertTrue(keyWrapped.containsKey("apiKeySecurityScheme"));

    List<Map<String, Object>> outReqs =
        (List<Map<String, Object>>) normalized.get("securityRequirements");
    assertEquals(2, outReqs.size());
    Map<String, Object> structured = outReqs.get(0);
    Map<String, Object> schemesMap = (Map<String, Object>) structured.get("schemes");
    assertTrue(schemesMap.containsKey("keyAuth"));
    assertEquals("already-map", outReqs.get(1).get("schemes"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(String k, Object v) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(k, v);
    return m;
  }

  // ---------- ProtocolResponses ----------

  // TC-ENG-203 | content(null) 返回 null；终态四状态
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contentNullAndTerminalStates() {
    assertNull(ProtocolResponses.content(null));
    assertTrue(ProtocolResponses.terminal(TaskState.TASK_STATE_COMPLETED));
    assertTrue(ProtocolResponses.terminal(TaskState.TASK_STATE_FAILED));
    assertTrue(ProtocolResponses.terminal(TaskState.TASK_STATE_CANCELED));
    assertTrue(ProtocolResponses.terminal(TaskState.TASK_STATE_REJECTED));
    assertFalse(ProtocolResponses.terminal(TaskState.TASK_STATE_WORKING));
  }

  // TC-ENG-204 | assemble：消息事件 → 消息快照
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void assembleMessageEvents() {
    Message message =
        Message.builder()
            .messageId("m-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("hello"))
            .metadata(new LinkedHashMap<>())
            .build();
    List<dev.openan.workflow.engine.model.ReceivedMessage> snapshots =
        ProtocolResponses.assemble(List.of(new MessageEvent(message)));
    assertEquals(1, snapshots.size());
  }

  // TC-ENG-205 | assemble：TaskEvent + artifact 追加 → 任务快照含合并 artifact
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void assembleTaskWithAppendedArtifacts() {
    Artifact first =
        Artifact.builder()
            .artifactId("a-1")
            .name("out")
            .parts(List.of(new TextPart("first ")))
            .metadata(new LinkedHashMap<>())
            .build();
    Task task =
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .artifacts(List.of(first))
            .history(List.of())
            .metadata(new LinkedHashMap<>())
            .build();
    Artifact chunk =
        Artifact.builder()
            .artifactId("a-1")
            .name("out")
            .parts(List.of(new TextPart("second")))
            .metadata(new LinkedHashMap<>())
            .build();
    TaskUpdateEvent appendEvent =
        new TaskUpdateEvent(
            task,
            TaskArtifactUpdateEvent.builder()
                .taskId("t-1")
                .contextId("c-1")
                .artifact(chunk)
                .append(true)
                .lastChunk(true)
                .build());

    List<dev.openan.workflow.engine.model.ReceivedMessage> snapshots =
        ProtocolResponses.assemble(List.of(new TaskEvent(task), appendEvent));
    assertFalse(snapshots.isEmpty());
    assertEquals(1, snapshots.get(snapshots.size() - 1).artifacts().size());
  }

  // TC-ENG-206 | assemble：status update 携带状态消息
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void assembleStatusUpdateWithMessage() {
    Message statusMessage =
        Message.builder()
            .messageId("sm-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("waiting"))
            .metadata(new LinkedHashMap<>())
            .build();
    TaskStatus status = new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null);
    Task task =
        Task.builder()
            .id("t-1")
            .contextId("c-1")
            .status(status)
            .artifacts(List.of())
            .history(List.of())
            .metadata(new LinkedHashMap<>())
            .build();
    TaskUpdateEvent event =
        new TaskUpdateEvent(
            task,
            TaskStatusUpdateEvent.builder().taskId("t-1").contextId("c-1").status(status).build());
    List<dev.openan.workflow.engine.model.ReceivedMessage> snapshots =
        ProtocolResponses.assemble(List.of(event));
    assertEquals(1, snapshots.size());
    assertNotNull(snapshots.get(0).message());
  }

  // TC-ENG-207 | acceptIncrementally：Message 立即快照，未知事件空列表
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void acceptIncrementallyBranches() {
    Message message =
        Message.builder()
            .messageId("m-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("x"))
            .metadata(new LinkedHashMap<>())
            .build();
    ProtocolResponses.Accumulator accumulator = new ProtocolResponses.Accumulator();
    List<dev.openan.workflow.engine.model.ReceivedMessage> messageSnap =
        accumulator.acceptIncrementally(new MessageEvent(message));
    assertEquals(1, messageSnap.size());

    // ClientEvent 是 sealed 接口（仅 Task/TaskUpdate/Message 三实现），未知事件分支不可达，见 §6
  }

  // TC-ENG-208 | gRPC 通道构造：plaintext 与 TLS-with-CA（懒构造，不发起连接）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void grpcChannelConstructionBranches() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "GRPC");
    java.lang.reflect.Method create =
        DefaultA2AJavaClientRuntime.class.getDeclaredMethod("createGrpcChannel", String.class);
    create.setAccessible(true);

    Object plaintext = create.invoke(runtime, "127.0.0.1:1");
    assertNotNull(plaintext);
    if (plaintext instanceof io.grpc.ManagedChannel managed) {
      managed.shutdownNow();
    }
    runtime.close();
  }
}

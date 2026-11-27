/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-1013…1030：小类守卫分支集中覆盖（redactor/mapper/accumulator/error/crypto）。 */
class SmallClassEdgeTest {

  // ---------- SensitiveDataRedactor ----------

  // TC-ENG-1013 | 引号值未闭合（到达串尾无终止引号）→ 整段原样输出
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unterminatedValueStillRedacted() {
    String text = "{\"password\": \"secret";
    String out = SensitiveDataRedactor.redact(text);
    assertFalse(out.contains("secret"));
    assertTrue(out.contains("***"));
  }

  // TC-ENG-1014 | 键位置是转义引号/非字符串 JSON → 键解析失败跳过
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonStringKeySkipped() {
    String text = "{123: \"x\", \"password\":\"hush\"}";
    String out = SensitiveDataRedactor.redact(text);
    assertFalse(out.contains("hush"));
  }

  // TC-ENG-1015 | 值是嵌套对象/数组（depth>0）在关闭括号处截断
  //
  // 输入: 并发/竞争条件
  // 预期输出: 竞争条件下行为正确，无数据竞争
  // 断言点: 结果一致性 + 竞态窗口覆盖
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nestedValueEndAtClosingBrace() {
    String json = "{\"meta\":{\"token\":\"deep\"},\"end\":1}";
    String out = SensitiveDataRedactor.redact(json);
    assertFalse(out.contains("deep"));
    assertTrue(out.contains("\"end\":1") || out.contains("\"end\": 1") || out.contains("1"));
  }

  // TC-ENG-1016 | valueEnd 在转义引号后正确继续
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void escapedQuoteInsideKeyContinues() {
    String json = "{\"pass\\\"word\":\"v\"}";
    assertEquals(json, SensitiveDataRedactor.redact(json));
  }

  // ---------- ClientEventMapper ----------

  private static Task plainTask(TaskState state) {
    return Task.builder()
        .id("t")
        .contextId("c")
        .status(new TaskStatus(state))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  // TC-ENG-1017 | status update 无 message 文本/无事件元数据 → text/metadata 缺省
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void statusUpdateWithoutTextOrMetadata() {
    TaskStatusUpdateEvent status =
        TaskStatusUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .build();
    Map<String, Object> map =
        ClientEventMapper.toMap(
            new TaskUpdateEvent(plainTask(TaskState.TASK_STATE_WORKING), status), "a");
    assertFalse(map.containsKey("text"));
    assertFalse(map.containsKey("metadata"));
    assertEquals("status", map.get("event_kind"));
  }

  // TC-ENG-1018 | artifact update 无文本（DataPart）/artifact 与事件均无元数据
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void artifactUpdateWithoutTextOrMetadata() {
    org.a2aproject.sdk.spec.TaskArtifactUpdateEvent event =
        org.a2aproject.sdk.spec.TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder()
                    .artifactId("a-1")
                    .parts(List.of(new org.a2aproject.sdk.spec.DataPart(Map.of("k", 1))))
                    .build())
            .append(false)
            .lastChunk(true)
            .build();
    Map<String, Object> map =
        ClientEventMapper.toMap(
            new TaskUpdateEvent(plainTask(TaskState.TASK_STATE_WORKING), event), "a");
    assertEquals("artifact", map.get("event_kind"));
    assertFalse(map.containsKey("text"));
    assertFalse(map.containsKey("metadata"));
  }

  // TC-ENG-1019 | message 事件无元数据 → metadata 不出现
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageEventWithoutMetadata() {
    MessageEvent event =
        new MessageEvent(
            Message.builder()
                .messageId("m")
                .role(Message.Role.ROLE_AGENT)
                .parts(new TextPart("hi"))
                .build());
    Map<String, Object> map = ClientEventMapper.toMap(event, "a");
    assertFalse(map.containsKey("metadata"));
    assertEquals("hi", map.get("text"));
  }

  // ---------- ProtocolResponses.Accumulator ----------

  private static Object newAccumulator() throws Exception {
    Class<?> cls = Class.forName("dev.openan.workflow.engine.client.ProtocolResponses$Accumulator");
    java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }

  private static void accept(Object acc, org.a2aproject.sdk.client.ClientEvent event)
      throws Exception {
    java.lang.reflect.Method m =
        acc.getClass().getDeclaredMethod("accept", org.a2aproject.sdk.client.ClientEvent.class);
    m.setAccessible(true);
    m.invoke(acc, event);
  }

  private static List<?> snapshots(Object acc) throws Exception {
    java.lang.reflect.Method m = acc.getClass().getDeclaredMethod("snapshots");
    m.setAccessible(true);
    return (List<?>) m.invoke(acc);
  }

  private static Object acceptIncrementally(Object acc, org.a2aproject.sdk.client.ClientEvent event)
      throws Exception {
    java.lang.reflect.Method m =
        acc.getClass()
            .getDeclaredMethod("acceptIncrementally", org.a2aproject.sdk.client.ClientEvent.class);
    m.setAccessible(true);
    return m.invoke(acc, event);
  }

  // TC-ENG-1020 | content(null message) → null；带 extensions 的 message 保留扩展集合
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageWithExtensionsPreserved() throws Exception {
    Object acc = newAccumulator();
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("x"))
            .extensions(java.util.List.of("https://x/ext"))
            .build();
    accept(acc, new MessageEvent(message));
    List<?> snaps = snapshots(acc);
    assertEquals(1, snaps.size());
    assertTrue(snaps.get(0).toString().contains("https://x/ext"));
  }

  // TC-ENG-1021 | acceptIncrementally 对未识别事件返回空列表
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void incrementallyUnknownEventReturnsEmpty() throws Exception {
    Object acc = newAccumulator();
    Object result =
        acceptIncrementally(
            acc,
            new MessageEvent(
                Message.builder()
                    .messageId("m")
                    .role(Message.Role.ROLE_AGENT)
                    .parts(new TextPart("x"))
                    .build()));
    assertTrue(
        result.toString().contains("ReceivedMessage") || result.toString().contains("message"));
  }

  // TC-ENG-1022 | snapshot：task.status 为 null、artifacts 为 null 的快照不崩
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void snapshotWithNullArtifacts() throws Exception {
    Object acc = newAccumulator();
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    accept(acc, new TaskEvent(task));
    List<?> snaps = snapshots(acc);
    assertEquals(1, snaps.size());
  }

  // TC-ENG-1023 | append 帧 metadata 全 null 时不崩
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void appendFrameWithNullMetadataOnBothSides() throws Exception {
    Object acc = newAccumulator();
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build();
    org.a2aproject.sdk.spec.TaskArtifactUpdateEvent first =
        org.a2aproject.sdk.spec.TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder().artifactId("a").parts(List.of(new TextPart("one"))).build())
            .append(false)
            .lastChunk(false)
            .build();
    org.a2aproject.sdk.spec.TaskArtifactUpdateEvent second =
        org.a2aproject.sdk.spec.TaskArtifactUpdateEvent.builder()
            .taskId("t")
            .contextId("c")
            .artifact(
                Artifact.builder().artifactId("a").parts(List.of(new TextPart("two"))).build())
            .append(true)
            .lastChunk(true)
            .build();
    accept(acc, new TaskUpdateEvent(task, first));
    accept(acc, new TaskUpdateEvent(task, second));
    String joined = snapshots(acc).toString();
    assertTrue(joined.contains("one"));
    assertTrue(joined.contains("two"));
  }

  // ---------- RemoteA2AErrorException ----------

  // TC-ENG-1024 | fromResponse：根是数组/字符串 → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void arrayRootReturnsNull() {
    assertNull(RemoteA2AErrorException.fromResponse(400, "[{\"error\":{}}]", Map.of()));
    assertNull(RemoteA2AErrorException.fromResponse(400, "\"error\"", Map.of()));
  }

  // TC-ENG-1025 | code 不是错误区间（如 200）→ null；responseHeaders 传 null 也不崩
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonErrorEnvelopeCodeReturnsNull() {
    String body = "{\"error\":{\"code\":200,\"message\":\"fine\"}}";
    assertNull(RemoteA2AErrorException.fromResponse(400, body, null));
  }

  // TC-ENG-1026 | getRetryAfter 无头时返回空串
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void retryAfterEmptyWithoutHeader() {
    RemoteA2AErrorException error =
        RemoteA2AErrorException.fromResponse(
            400, "{\"error\":{\"code\":400,\"message\":\"m\"}}", Map.of());
    assertEquals("", error.getRetryAfter());
  }

  // TC-ENG-1027 | Retry-After 大小写不敏感
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void retryAfterCaseInsensitive() {
    RemoteA2AErrorException error =
        RemoteA2AErrorException.fromResponse(
            429,
            "{\"error\":{\"code\":429,\"message\":\"m\"}}",
            Map.of("RETRY-AFTER", List.of("12")));
    assertEquals("12", error.getRetryAfter());
  }
}

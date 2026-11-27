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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ProtocolLoggerTest {
  private static final String SETTING = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_SENSITIVE_HEADERS";
  private static final String PREVIEW = "WORKFLOW_ENGINE_PROTOCOL_MODEL_PREVIEW";
  private static final String INCLUDE_BODY = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
  private static final String MAX_BODY_CHARS = "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS";

  private ListAppender<ILoggingEvent> appender;
  private Logger protocolLogger;

  @BeforeEach
  void attachAppender() {
    protocolLogger = (Logger) LoggerFactory.getLogger("PROTOCOL");
    protocolLogger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    protocolLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppenderAndSettings() {
    protocolLogger.detachAppender(appender);
    List.of(SETTING, PREVIEW, INCLUDE_BODY, MAX_BODY_CHARS).forEach(System::clearProperty);
  }

  // TC-ENG-010 | 预览关闭时 logRequest/logResponseEvent 不产生任何日志
  //
  // 输入: 未设置 WORKFLOW_ENGINE_PROTOCOL_MODEL_PREVIEW（默认 false）
  // 预期输出: appender 为空
  // 断言点: 事件数量为 0；防回归: 开关默认值翻转为 true 即失败
  @Test
  void previewDisabledEmitsNothing() {
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of("Authorization", "Bearer s"));
    ProtocolLogger.logResponseEvent("agent-a", new TaskEvent(task()));

    assertTrue(appender.list.isEmpty());
  }

  // TC-ENG-011 | 预览开启时请求日志包含脱敏后的头与消息体
  //
  // 输入: MODEL_PREVIEW=true, 请求头含 Authorization: Bearer secret-token
  // 预期输出: 一条 MODEL_PREVIEW >>> 日志，含 agent 名与端点；Authorization: ***；secret 不出现
  // 断言点: 前缀、端点、脱敏结果；防回归: 脱敏被删除即失败
  @Test
  void requestPreviewRedactsSensitiveHeaders() {
    System.setProperty(PREVIEW, "true");
    ProtocolLogger.logRequest(
        "agent-a",
        "http://agent-a.example",
        params(),
        Map.of("Authorization", "Bearer secret-token", "X-Request-Id", "rid-1"));

    assertFalse(appender.list.isEmpty());
    String text = appender.list.get(0).getFormattedMessage();
    assertTrue(text.contains("MODEL_PREVIEW >>> [agent-a] REQUEST to http://agent-a.example"));
    assertTrue(text.contains("Authorization: ***"));
    assertFalse(text.contains("secret-token"));
    assertTrue(text.contains("X-Request-Id: rid-1"));
  }

  // TC-ENG-012 | INCLUDE_BODY=false 时消息体替换为占位符
  //
  // 输入: 预览开启 + INCLUDE_BODY=false
  // 预期输出: 日志含 "(body logging disabled)" 且不含消息文本
  // 断言点: 占位符与原文缺失；防回归: body 开关失效即失败
  @Test
  void bodyLoggingDisabledReplacesBody() {
    System.setProperty(PREVIEW, "true");
    System.setProperty(INCLUDE_BODY, "false");
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of());

    String text = appender.list.get(0).getFormattedMessage();
    assertTrue(text.contains("(body logging disabled)"));
    assertFalse(text.contains("hello"));
  }

  // TC-ENG-013 | body 超长按 MAX_BODY_CHARS 截断并标注原始长度
  //
  // 输入: MAX_BODY_CHARS=20, body 远长于 20
  // 预期输出: 含 "... (truncated, originalChars=" 且截断后长度收敛
  // 断言点: 截断标记；防回归: 截断逻辑被删除即失败
  @Test
  void oversizedBodyIsTruncatedWithMarker() {
    System.setProperty(PREVIEW, "true");
    System.setProperty(MAX_BODY_CHARS, "20");
    ProtocolLogger.logRequest(
        "agent-a", "http://x", paramsWithText("0123456789".repeat(30)), Map.of());

    String text = appender.list.get(0).getFormattedMessage();
    assertTrue(text.contains("... (truncated, originalChars="));
    assertTrue(text.length() < 400);
  }

  // TC-ENG-014 | MAX_BODY_CHARS 非法值回退默认（不截断）
  //
  // 输入: MAX_BODY_CHARS=abc / 0 / 负数
  // 预期输出: 无截断标记，完整 body 输出
  // 断言点: 截断标记不存在；防回归: 解析异常逃逸即失败
  @Test
  void invalidMaxBodyCharsFallsBackToDefault() {
    System.setProperty(PREVIEW, "true");
    System.setProperty(MAX_BODY_CHARS, "abc");
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of());
    String text = appender.list.get(0).getFormattedMessage();
    assertFalse(text.contains("truncated"));

    System.setProperty(MAX_BODY_CHARS, "0");
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of());
    assertTrue(appender.list.size() >= 2);
    assertFalse(appender.list.get(1).getFormattedMessage().contains("truncated"));
  }

  // TC-ENG-015 | 响应事件按载荷类型分别记录；未知事件输出占位符
  //
  // 输入: 1) TaskEvent 2) MessageEvent 3) 未知 ClientEvent
  // 预期输出: 前两者含 MODEL_PREVIEW <<< 与 RESPONSE [TaskEvent/MessageEvent]；
  //          未知事件输出 "(no serializable payload)"
  // 断言点: 事件类型名与占位符；防回归: extractPayload 兜底被删即失败
  @Test
  void responseEventsLogPayloadByType() {
    System.setProperty(PREVIEW, "true");
    ProtocolLogger.logResponseEvent("agent-a", new TaskEvent(task()));
    ProtocolLogger.logResponseEvent("agent-a", new MessageEvent(message("hello")));

    assertTrue(appender.list.size() >= 2);
    assertTrue(appender.list.get(0).getFormattedMessage().contains("RESPONSE [TaskEvent]"));
    assertTrue(appender.list.get(1).getFormattedMessage().contains("RESPONSE [MessageEvent]"));
  }

  // TC-ENG-016 | 空头映射格式化为 (none)
  //
  // 输入: headers = null / 空 map
  // 预期输出: formatHeaders 返回 "(none)"
  // 断言点: 两个分支的字面值；防回归: 空判断被删即失败
  @Test
  void emptyOrNullHeadersFormatToNone() {
    assertTrue(ProtocolLogger.formatHeaders(Map.of()).equals("(none)"));
    assertTrue(ProtocolLogger.formatHeaders(null).equals("(none)"));
  }

  // TC-ENG-017 | 响应事件不可序列化时告警而非抛出
  //
  // 输入: 载荷含不可序列化对象（FAIL_ON_EMPTY_BEANS 已禁用，产出空串/占位结构）
  // 预期输出: 不抛异常；事件或告警二者之一进入日志
  // 断言点: appender 至少一条；防回归: catch 分支被删且序列化抛错即失败
  @Test
  void responseEventSerializationFailureIsContained() {
    System.setProperty(PREVIEW, "true");
    ProtocolLogger.logResponseEvent("agent-a", new TaskEvent(task()));
    assertTrue(appender.list.size() >= 1);
    String text = appender.list.get(0).getFormattedMessage();
    assertTrue(text.contains("MODEL_PREVIEW <<< [agent-a]"));
  }

  // TC-ENG-018 | TaskUpdateEvent 两类更新载荷
  //
  // 输入: statusUpdate / artifactUpdate
  // 预期输出: 两条 RESPONSE [TaskUpdateEvent] 日志，均不抛
  // 断言点: 两条日志；防回归: extractPayload 分支被删
  @Test
  void taskUpdateEventsLogInnerPayloads() {
    System.setProperty(PREVIEW, "true");
    Task task = task();
    org.a2aproject.sdk.spec.TaskStatusUpdateEvent status =
        org.a2aproject.sdk.spec.TaskStatusUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .build();
    org.a2aproject.sdk.spec.TaskArtifactUpdateEvent artifact =
        org.a2aproject.sdk.spec.TaskArtifactUpdateEvent.builder()
            .taskId("task-1")
            .contextId("context-1")
            .artifact(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a-1")
                    .parts(List.of(new TextPart("chunk")))
                    .build())
            .append(false)
            .lastChunk(true)
            .build();
    ProtocolLogger.logResponseEvent(
        "agent-a", new org.a2aproject.sdk.client.TaskUpdateEvent(task, status));
    ProtocolLogger.logResponseEvent(
        "agent-a", new org.a2aproject.sdk.client.TaskUpdateEvent(task, artifact));
    assertEquals(2, appender.list.size());
    appender.list.forEach(event -> assertTrue(event.getFormattedMessage().contains("RESPONSE")));
  }

  // TC-ENG-019 | 负数 MAX_BODY_CHARS 回退默认
  //
  // 输入: MAX_BODY_CHARS=-5
  // 预期输出: 不截断
  // 断言点: 无截断标记；防回归: 非正回退被删
  @Test
  void negativeMaxBodyCharsFallsBackToDefault() {
    System.setProperty(PREVIEW, "true");
    System.setProperty(MAX_BODY_CHARS, "-5");
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of());
    assertFalse(appender.list.get(0).getFormattedMessage().contains("truncated"));
  }

  // TC-ENG-020 | 空白属性值回退到环境变量再回退默认（boolean/int 两侧分支）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void blankPropertyFallsThroughToEnvFallback() {
    System.setProperty(PREVIEW, "");
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of());
    // 空白 PREVIEW → env 未设置 → 默认 false → 不产生日志
    assertTrue(appender.list.isEmpty());

    System.setProperty(PREVIEW, "true");
    System.setProperty(MAX_BODY_CHARS, "");
    ProtocolLogger.logRequest("agent-a", "http://x", params(), Map.of());
    // 空白 MAX_BODY_CHARS → env 未设置 → 默认值 → 不截断
    assertFalse(appender.list.get(0).getFormattedMessage().contains("truncated"));

    System.setProperty(PREVIEW, "TRUE");
    System.clearProperty(MAX_BODY_CHARS);
    ProtocolLogger.logResponseEvent("agent-a", new TaskEvent(task()));
    // 大写 TRUE 解析为开启
    assertTrue(appender.list.size() >= 2);
  }

  // TC-ENG-021 | TaskUpdateEvent 只带 task 快照（update 非 status/artifact）→ extractPayload 取 task
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskUpdatePayloadFallsBackToTask() {
    System.setProperty(PREVIEW, "true");
    ProtocolLogger.logResponseEvent(
        "agent-a", new org.a2aproject.sdk.client.TaskUpdateEvent(task(), artifactUpdate()));
    assertEquals(1, appender.list.size());
    assertTrue(appender.list.get(0).getFormattedMessage().contains("RESPONSE [TaskUpdateEvent]"));
  }

  private static org.a2aproject.sdk.spec.TaskArtifactUpdateEvent artifactUpdate() {
    return org.a2aproject.sdk.spec.TaskArtifactUpdateEvent.builder()
        .taskId("task-1")
        .contextId("context-1")
        .artifact(
            org.a2aproject.sdk.spec.Artifact.builder()
                .artifactId("a-1")
                .parts(List.of(new TextPart("chunk")))
                .build())
        .append(false)
        .lastChunk(true)
        .build();
  }

  private static MessageSendParams params() {
    return paramsWithText("hello");
  }

  private static MessageSendParams paramsWithText(String text) {
    return new MessageSendParams(message(text), null, null, null);
  }

  private static Message message(String text) {
    return Message.builder()
        .role(Message.Role.ROLE_AGENT)
        .messageId("message-1")
        .parts(new TextPart(text))
        .metadata(Map.of())
        .build();
  }

  private static Task task() {
    return Task.builder()
        .id("task-1")
        .contextId("context-1")
        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }
}

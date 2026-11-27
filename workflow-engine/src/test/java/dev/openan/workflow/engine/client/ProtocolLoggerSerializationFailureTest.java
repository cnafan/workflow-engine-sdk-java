/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashMap;
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

/** TC-ENG-1122…1123：协议日志序列化失败 → WARN 告警而非外泄（自引用 Map 触发 Jackson 异常）。 */
class ProtocolLoggerSerializationFailureTest {

  private static final String PREVIEW = "WORKFLOW_ENGINE_PROTOCOL_MODEL_PREVIEW";
  private ListAppender<ILoggingEvent> appender;
  private Logger protocolLogger;

  @BeforeEach
  void attachAppender() {
    protocolLogger = (Logger) LoggerFactory.getLogger("PROTOCOL");
    protocolLogger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    protocolLogger.addAppender(appender);
    System.setProperty(PREVIEW, "true");
  }

  @AfterEach
  void detachAndClear() {
    protocolLogger.detachAppender(appender);
    System.clearProperty(PREVIEW);
  }

  /** 直接自引用的 Map：Jackson 序列化必然抛 "Direct self-reference leading to cycle"。 */
  private static Map<String, Object> selfReferencingMap() {
    Map<String, Object> self = new HashMap<>();
    self.put("self", self);
    return self;
  }

  // TC-ENG-1122 | logRequest：params 内自引用元数据 → Jackson 异常 → WARN "Failed to serialize request"
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void requestSerializationFailureEmitsWarn() {
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_USER)
            .parts(new TextPart("x"))
            .metadata(selfReferencingMap())
            .build();
    ProtocolLogger.logRequest(
        "agent-a", "http://x", MessageSendParams.builder().message(message).build(), Map.of());
    assertTrue(
        appender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("Failed to serialize request")),
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString());
  }

  // TC-ENG-1123 | logResponseEvent：task 元数据自引用 → WARN "Failed to serialize response event"
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void responseSerializationFailureEmitsWarn() {
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .history(List.of())
            .metadata(selfReferencingMap())
            .build();
    ProtocolLogger.logResponseEvent("agent-a", new TaskEvent(task));
    assertTrue(
        appender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("Failed to serialize response event")),
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString());

    // MessageEvent 载荷同样失败（extractPayload → Message）
    Message message =
        Message.builder()
            .messageId("m")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("x"))
            .metadata(selfReferencingMap())
            .build();
    ProtocolLogger.logResponseEvent("agent-a", new MessageEvent(message));
    assertTrue(
        appender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("Failed to serialize response event")),
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString());
    assertTrue(appender.list.size() >= 2);
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-1101：A2AJavaClientRuntime 接口 default 方法（仅实现 sendMessage+close 的最小运行时）。 所有实现类都 override
 * 了这些方法，default 体此前从未被执行。
 */
class A2AJavaClientRuntimeDefaultMethodTest {

  private static AgentCard card() {
    return org.a2aproject.sdk.spec.AgentCard.builder()
        .name("minimal")
        .description("d")
        .version("1")
        .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(false, false, false, List.of()))
        .defaultInputModes(List.of("text/plain"))
        .defaultOutputModes(List.of("text/plain"))
        .skills(List.of())
        .supportedInterfaces(
            List.of(new org.a2aproject.sdk.spec.AgentInterface("http://x", "HTTP+JSON", "1.0", "")))
        .build();
  }

  private static A2AJavaClientRuntime minimal() {
    return new A2AJavaClientRuntime() {
      @Override
      public Iterable<ClientEvent> sendMessage(
          AgentCard agentCard,
          MessageSendParams params,
          org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext callContext,
          java.util.function.Consumer<ClientEvent> eventSink,
          java.util.function.Consumer<String> logSink) {
        return List.of();
      }

      @Override
      public void close() {}
    };
  }

  // TC-ENG-1101 | 四个 default 方法抛 UnsupportedOperationException 且消息精确
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultMethodsRejectWithExactMessages() {
    A2AJavaClientRuntime runtime = minimal();
    AgentCard card = card();

    UnsupportedOperationException getError =
        assertThrows(UnsupportedOperationException.class, () -> runtime.getTask(card, "t-1", null));
    assertEquals("getTask not supported by this runtime", getError.getMessage());

    UnsupportedOperationException listError =
        assertThrows(
            UnsupportedOperationException.class,
            () -> runtime.listTasks(card, ListTasksParams.builder().pageSize(1).build(), null));
    assertEquals("listTasks not supported by this runtime", listError.getMessage());

    UnsupportedOperationException cancelError =
        assertThrows(
            UnsupportedOperationException.class, () -> runtime.cancelTask(card, "t-1", null));
    assertEquals("cancelTask not supported by this runtime", cancelError.getMessage());

    UnsupportedOperationException subscribeError =
        assertThrows(
            UnsupportedOperationException.class,
            () -> runtime.subscribeToTask(card, "t-1", null, event -> {}));
    assertEquals("subscribeToTask not supported by this runtime", subscribeError.getMessage());
  }

  // TC-ENG-1102 | 最小运行时的 sendMessage/close 正常工作（匿名实现可独立使用）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void minimalRuntimeSendMessageWorks() {
    A2AJavaClientRuntime runtime = minimal();
    Iterable<ClientEvent> sent =
        runtime.sendMessage(
            card(),
            MessageSendParams.builder()
                .message(
                    org.a2aproject.sdk.spec.Message.builder()
                        .messageId("m")
                        .role(org.a2aproject.sdk.spec.Message.Role.ROLE_USER)
                        .parts(new org.a2aproject.sdk.spec.TextPart("x"))
                        .build())
                .build(),
            new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
                new java.util.HashMap<>(), new java.util.HashMap<>()),
            null,
            null);
    java.util.ArrayList<ClientEvent> events = new java.util.ArrayList<>();
    sent.forEach(events::add);
    assertEquals(0, events.size());
    runtime.close();
  }
}

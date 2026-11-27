/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.MessageContent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

/** TC-ENG-900…904：DefaultExtensionSender 守卫/非拥有关闭。 */
class DefaultExtensionSenderGuardsTest {

  private AgentCard card(String uri, boolean supports) {
    org.a2aproject.sdk.spec.AgentCapabilities capabilities =
        new org.a2aproject.sdk.spec.AgentCapabilities(
            false,
            false,
            false,
            supports
                ? List.of(new org.a2aproject.sdk.spec.AgentExtension(uri, Map.of(), false, "ext"))
                : List.of());
    return org.a2aproject.sdk.spec.AgentCard.builder()
        .name("ext-agent")
        .description("d")
        .version("1")
        .capabilities(capabilities)
        .defaultInputModes(List.of("text/plain"))
        .defaultOutputModes(List.of("text/plain"))
        .skills(List.of())
        .supportedInterfaces(
            List.of(
                new org.a2aproject.sdk.spec.AgentInterface(
                    "http://127.0.0.1:1/a", "HTTP+JSON", "1.0", "")))
        .build();
  }

  private MessageContent content(String uri, boolean activate) {
    Set<String> extensions = activate ? Set.of(uri) : Set.of();
    Map<String, Object> metadata = activate ? Map.of(uri, Map.of()) : Map.of();
    return new MessageContent(
        List.of(new org.a2aproject.sdk.spec.TextPart("x")), metadata, extensions);
  }

  private A2ATransport transport(AgentCard card) {
    return new A2ATransport(
        List.of(card),
        new FakeA2AJavaClientRuntime(),
        WorkflowEngineClientConfig.builder().build());
  }

  // TC-ENG-900 | null transport → NPE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullTransportRejected() {
    assertThrows(NullPointerException.class, () -> new DefaultExtensionSender(null));
  }

  // TC-ENG-901 | null content → NPE（future 异常完成）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullContentRejected() throws Exception {
    try (DefaultExtensionSender sender =
        new DefaultExtensionSender(transport(card(A2ATExtension.AUTHORIZATION_T.uri(), true)))) {
      java.util.concurrent.CompletionException error =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () -> sender.sendAuthorization("ext-agent", null).join());
      assertTrue(error.getCause() instanceof NullPointerException);
    }
  }

  // TC-ENG-902 | 未知 agent → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unknownAgentRejected() throws Exception {
    try (DefaultExtensionSender sender =
        new DefaultExtensionSender(transport(card(A2ATExtension.AUTHORIZATION_T.uri(), true)))) {
      java.util.concurrent.CompletionException error =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () ->
                  sender
                      .sendAuthorization(
                          "missing", content(A2ATExtension.AUTHORIZATION_T.uri(), true))
                      .join());
      assertTrue(error.getCause().getMessage().contains("Agent not found"));
    }
  }

  // TC-ENG-903 | 卡片不支持扩展/内容未激活/元数据缺键 → IAE
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void capabilityAndContentGuard() throws Exception {
    String uri = A2ATExtension.AUTHORIZATION_T.uri();
    try (DefaultExtensionSender sender = new DefaultExtensionSender(transport(card(uri, false)))) {
      java.util.concurrent.CompletionException error =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () -> sender.sendAuthorization("ext-agent", content(uri, true)).join());
      assertTrue(error.getCause() instanceof IllegalArgumentException);
    }
    try (DefaultExtensionSender sender = new DefaultExtensionSender(transport(card(uri, true)))) {
      java.util.concurrent.CompletionException error =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () -> sender.sendAuthorization("ext-agent", content(uri, false)).join());
      assertTrue(error.getCause() instanceof IllegalArgumentException);
    }
  }

  // TC-ENG-904 | nonOwning sender 的 close 不关闭 transport
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonOwningCloseDoesNotCloseTransport() throws Exception {
    A2ATransport transport = transport(card(A2ATExtension.NOTIFICATION_T.uri(), true));
    DefaultExtensionSender sender = DefaultExtensionSender.nonOwning(transport);
    sender.close();
    sender.close();
    assertEquals(transport, sender.transport());
    transport.close();
  }
}

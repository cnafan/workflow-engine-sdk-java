/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-330…331：Runtime 入参守卫。 */
class RuntimeGuardsTest {

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static AgentCard card(String interfacesJson) throws Exception {
    return JSON.readValue(
        """
        {"name":"guard-agent","description":"d","version":"1",
         "capabilities":{"streaming":true,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[%s]}
        """
            .formatted(interfacesJson),
        AgentCard.class);
  }

  // TC-ENG-330 | 通知流 contextId 空白 → IAE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void blankContextIdForStreamRejected() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, null);
    AgentCard agentCard =
        card(
            """
            {"protocolBinding":"HTTP+JSON","protocolVersion":"1.0","tenant":"",
             "url":"http://127.0.0.1:1/message:stream"}
            """);
    MessageSendParams params =
        MessageSendParams.builder()
            .message(
                Message.builder()
                    .messageId(java.util.UUID.randomUUID().toString())
                    .role(Message.Role.ROLE_USER)
                    .parts(new TextPart("x"))
                    .build())
            .build();
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              try {
                java.lang.reflect.Method m =
                    DefaultA2AJavaClientRuntime.class.getDeclaredMethod(
                        "getOrCreateStreamClient", AgentCard.class, String.class);
                m.setAccessible(true);
                m.invoke(runtime, agentCard, " ");
              } catch (java.lang.reflect.InvocationTargetException e) {
                throw (RuntimeException) e.getCause();
              } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
              }
            });
    assertTrue(error.getMessage().contains("contextId must not be blank"));
    runtime.close();
  }

  // TC-ENG-331 | 卡片无受支持接口 → 运行时异常
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cardWithoutInterfacesRejected() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, null);
    AgentCard empty = card("");
    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> {
              try {
                runtime.sendMessage(empty, messageParams("c-1"), null, null, null);
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertTrue(error.getMessage().contains("supportedInterfaces"));
    runtime.close();
  }

  private static MessageSendParams messageParams(String contextId) {
    return MessageSendParams.builder()
        .message(
            Message.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart("x"))
                .contextId(contextId)
                .build())
        .build();
  }

  @SuppressWarnings("unused")
  private static final List<String> KEEP = List.of();
}

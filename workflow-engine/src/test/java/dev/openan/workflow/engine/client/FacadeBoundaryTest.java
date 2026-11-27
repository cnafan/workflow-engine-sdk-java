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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-170…185：DefaultWorkflowEngineClient 边界守卫（公开门面）与纯校验静态方法（反射）。
 *
 * <p>门面守卫全部走公开 API 的 failed future，不需要服务器；纯校验私有静态方法用反射调用， 断言落在异常消息（fail-closed 契约）而非实现细节。
 */
class FacadeBoundaryTest {

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static AgentCard cardWithExtensions(String... extensionUris) throws Exception {
    StringBuilder extensions = new StringBuilder();
    for (String uri : extensionUris) {
      if (extensions.length() > 0) {
        extensions.append(",");
      }
      extensions.append("{\"uri\":\"").append(uri).append("\",\"required\":false}");
    }
    return JSON.readValue(
        """
        {"name":"agent-a","description":"d","version":"1",
         "capabilities":{"streaming":true,"extensions":[%s]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
         "tenant":"","url":"http://localhost:1"}]}
        """
            .formatted(extensions),
        AgentCard.class);
  }

  private static DefaultWorkflowEngineClient client(AgentCard card) {
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            DefaultWorkflowEngineClientNegotiationTest.runtime(
                params ->
                    List.of(
                        DefaultWorkflowEngineClientNegotiationTest.response(
                            params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("ok")))),
            WorkflowEngineClientConfig.builder().build());
    return new DefaultWorkflowEngineClient(transport, WorkflowEngineClientConfig.builder().build());
  }

  // TC-ENG-170 | 构造校验：交换预算必须为正、轮询间隔至少 100ms
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorValidatesBudgetAndPollInterval() throws Exception {
    A2ATransport transport =
        new A2ATransport(
            List.of(DefaultWorkflowEngineClientNegotiationTest.card()),
            DefaultWorkflowEngineClientNegotiationTest.runtime(params -> List.of()),
            WorkflowEngineClientConfig.builder().build());

    IllegalArgumentException badBudget =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DefaultWorkflowEngineClient.owning(
                    transport,
                    WorkflowEngineClientConfig.builder().maxNegotiationExchanges(0).build()));
    assertTrue(badBudget.getMessage().contains("must be positive"));

    IllegalArgumentException badInterval =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DefaultWorkflowEngineClient.owning(
                    transport,
                    WorkflowEngineClientConfig.builder().taskPollIntervalMillis(99).build()));
    assertTrue(badInterval.getMessage().contains("at least 100ms"));
  }

  // TC-ENG-171 | getTask：关闭后 / 空名 / 未知 agent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void getTaskBoundaryGuards() throws Exception {
    DefaultWorkflowEngineClient client = client(DefaultWorkflowEngineClientNegotiationTest.card());
    RuntimeException blank =
        assertThrows(RuntimeException.class, () -> client.getTask(" ", "t-1").join());
    assertTrue(blank.getMessage().contains("must not be blank"));
    RuntimeException unknown =
        assertThrows(RuntimeException.class, () -> client.getTask("nobody", "t-1").join());
    assertTrue(unknown.getMessage().contains("Agent not found"));
    client.close();
    RuntimeException closed =
        assertThrows(RuntimeException.class, () -> client.getTask("agent-a", "t-1").join());
    assertTrue(closed.getMessage().contains("client closed"));
  }

  // TC-ENG-172 | listTasks：关闭后 / 空名 / null 参数 / 未知 agent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void listTasksBoundaryGuards() throws Exception {
    DefaultWorkflowEngineClient client = client(DefaultWorkflowEngineClientNegotiationTest.card());
    RuntimeException blank =
        assertThrows(RuntimeException.class, () -> client.listTasks("", null).join());
    assertTrue(blank.getMessage().contains("must not be null or blank"));
    RuntimeException nullParams =
        assertThrows(
            RuntimeException.class,
            () -> client.listTasks("agent-a", (ListTasksParams) null).join());
    assertTrue(nullParams.getMessage().contains("must not be null or blank"));
    RuntimeException unknown =
        assertThrows(
            RuntimeException.class, () -> client.listTasks("nobody", new ListTasksParams()).join());
    assertTrue(unknown.getMessage().contains("Agent not found"));
    client.close();
    assertTrue(
        assertThrows(
                RuntimeException.class,
                () ->
                    client
                        .listTasks(
                            "agent-a",
                            new ListTasksParams("", null, 10, null, null, null, null, null))
                        .join())
            .getMessage()
            .contains("client closed"));
  }

  // TC-ENG-173 | cancelTask：关闭后 / 空名 / 未知 agent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancelTaskBoundaryGuards() throws Exception {
    DefaultWorkflowEngineClient client = client(DefaultWorkflowEngineClientNegotiationTest.card());
    RuntimeException blank =
        assertThrows(RuntimeException.class, () -> client.cancelTask("agent-a", " ").join());
    assertTrue(blank.getMessage().contains("must not be blank"));
    RuntimeException unknown =
        assertThrows(RuntimeException.class, () -> client.cancelTask("nobody", "t-1").join());
    assertTrue(unknown.getMessage().contains("Agent not found"));
    client.close();
    assertTrue(
        assertThrows(RuntimeException.class, () -> client.cancelTask("agent-a", "t-1").join())
            .getMessage()
            .contains("client closed"));
  }

  // TC-ENG-174 | subscribeToTask：关闭后 / 空名 / 未知 agent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subscribeBoundaryGuards() throws Exception {
    DefaultWorkflowEngineClient client = client(DefaultWorkflowEngineClientNegotiationTest.card());
    RuntimeException blank =
        assertThrows(
            RuntimeException.class, () -> client.subscribeToTask("agent-a", "", null).join());
    assertTrue(blank.getMessage().contains("must not be blank"));
    RuntimeException unknown =
        assertThrows(
            RuntimeException.class, () -> client.subscribeToTask("nobody", "t-1", null).join());
    assertTrue(unknown.getMessage().contains("Agent not found"));
    client.close();
    assertTrue(
        assertThrows(
                RuntimeException.class, () -> client.subscribeToTask("agent-a", "t-1", null).join())
            .getMessage()
            .contains("client closed"));
  }

  // TC-ENG-175 | sendTask：null 策略 / null 内容 / 未知 agent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sendTaskBoundaryGuards() throws Exception {
    DefaultWorkflowEngineClient client = client(DefaultWorkflowEngineClientNegotiationTest.card());
    RuntimeException nullStrategy =
        assertThrows(
            RuntimeException.class,
            () -> client.sendTask("agent-a", MessageContent.text("x"), null).join());
    assertTrue(nullStrategy.getMessage().contains("negotiationStrategy"));
    RuntimeException nullContent =
        assertThrows(
            RuntimeException.class,
            () ->
                client
                    .sendTask(
                        "agent-a",
                        null,
                        (request) ->
                            java.util.concurrent.CompletableFuture.completedFuture(
                                new dev.openan.workflow.engine.model.NegotiationReply.Send(
                                    MessageContent.text("ok"))))
                    .join());
    assertTrue(nullContent.getMessage().contains("null content"));
    RuntimeException unknown =
        assertThrows(
            RuntimeException.class,
            () ->
                client
                    .sendTask(
                        "nobody",
                        MessageContent.text("x"),
                        (request) ->
                            java.util.concurrent.CompletableFuture.completedFuture(
                                new dev.openan.workflow.engine.model.NegotiationReply.Send(
                                    MessageContent.text("ok"))))
                    .join());
    assertTrue(unknown.getMessage().contains("Agent not found"));
  }

  // TC-ENG-176 | setEventCallback(null) 不抛；callbackTimeoutSeconds 委托；close 幂等
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void eventCallbackNullSafeAndCloseIdempotent() throws Exception {
    DefaultWorkflowEngineClient client = client(DefaultWorkflowEngineClientNegotiationTest.card());
    client.setEventCallback(null);
    assertTrue(client.callbackTimeoutSeconds() > 0);
    client.close();
    client.close();
  }

  // TC-ENG-177 | validateTaskExtension：激活/元数据不匹配 → fail-closed
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validateTaskExtensionActivationMismatch() throws Exception {
    AgentCard card = DefaultWorkflowEngineClientNegotiationTest.cardWithoutNegotiation();
    String taskUri = A2ATExtension.TASK_T.uri();

    MessageContent activatedOnly =
        new MessageContent(
            MessageContent.text("x").parts(), new HashMap<>(), java.util.Set.of(taskUri));
    IllegalArgumentException activatedError =
        assertThrows(
            IllegalArgumentException.class, () -> validateTaskExtension(card, activatedOnly));
    assertTrue(activatedError.getMessage().contains("matching extension activation"));

    Map<String, Object> suppliedOnlyMeta = new HashMap<>();
    suppliedOnlyMeta.put(taskUri, Map.of());
    MessageContent suppliedOnly =
        new MessageContent(MessageContent.text("x").parts(), suppliedOnlyMeta, java.util.Set.of());
    assertThrows(IllegalArgumentException.class, () -> validateTaskExtension(card, suppliedOnly));
  }

  // TC-ENG-178 | validateTaskExtension：激活+元数据齐备但卡片未声明扩展 → fail-closed
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validateTaskExtensionUndeclaredByCard() throws Exception {
    AgentCard noExtensions = DefaultWorkflowEngineClientNegotiationTest.cardWithoutNegotiation();
    String taskUri = A2ATExtension.TASK_T.uri();
    Map<String, Object> meta = new HashMap<>();
    meta.put(taskUri, Map.of());
    MessageContent taskContent =
        new MessageContent(MessageContent.text("x").parts(), meta, java.util.Set.of(taskUri));
    IllegalArgumentException taskError =
        assertThrows(
            IllegalArgumentException.class, () -> validateTaskExtension(noExtensions, taskContent));
    assertTrue(taskError.getMessage().contains("does not declare Task-T"));

    MessageContent negotiationContent =
        DefaultWorkflowEngineClientNegotiationTest.negotiation(
            "n-1", 1, 2, NegotiationPerformative.PROPOSE);
    IllegalArgumentException negotiationError =
        assertThrows(
            IllegalArgumentException.class,
            () -> validateTaskExtension(noExtensions, negotiationContent));
    assertTrue(negotiationError.getMessage().contains("does not declare Negotiation-T"));
  }

  // TC-ENG-179 | validateTaskExtension 合法输入不抛；carriesNegotiationMetadata 三个来源
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validateTaskExtensionValidAndCarriesMetadata() throws Exception {
    String negUri = A2ATExtension.NEGOTIATION_T.uri();
    AgentCard card = DefaultWorkflowEngineClientNegotiationTest.card();
    MessageContent validNegotiation =
        DefaultWorkflowEngineClientNegotiationTest.negotiation(
            "n-1", 1, 2, NegotiationPerformative.PROPOSE);
    validateTaskExtension(card, validNegotiation);

    MessageContent withMessageMeta =
        new MessageContent(
            MessageContent.text("x").parts(), Map.of(negUri, Map.of()), java.util.Set.of(negUri));
    ReceivedMessage messageCarrier =
        new ReceivedMessage(withMessageMeta, new HashMap<>(), List.of());
    assertTrue(carriesNegotiationMetadata(messageCarrier));

    ReceivedMessage taskCarrier =
        new ReceivedMessage(MessageContent.text("x"), Map.of(negUri, Map.of()), List.of());
    assertTrue(carriesNegotiationMetadata(taskCarrier));

    ReceivedMessage empty =
        new ReceivedMessage(MessageContent.text("x"), new HashMap<>(), List.of());
    assertFalse(carriesNegotiationMetadata(empty));
  }

  // TC-ENG-180 | validateReply：合法 ACCEPT 通过；缺激活/轮次不匹配/PROPOSE 拒绝
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validateReplyBranches() throws Exception {
    NegotiationContext original =
        new NegotiationContext("n-1", 1, 3, NegotiationPerformative.PROPOSE);

    MessageContent accept =
        DefaultWorkflowEngineClientNegotiationTest.negotiation(
            "n-1", 1, 3, NegotiationPerformative.ACCEPT);
    NegotiationContext accepted = validateReply(original, accept);
    assertEquals(NegotiationPerformative.ACCEPT, accepted.performative());

    MessageContent wrongRound =
        DefaultWorkflowEngineClientNegotiationTest.negotiation(
            "n-1", 2, 3, NegotiationPerformative.ACCEPT);
    IllegalArgumentException roundError =
        assertThrows(IllegalArgumentException.class, () -> validateReply(original, wrongRound));
    assertTrue(roundError.getMessage().contains("does not match"));

    MessageContent propose =
        DefaultWorkflowEngineClientNegotiationTest.negotiation(
            "n-1", 1, 3, NegotiationPerformative.PROPOSE);
    assertThrows(IllegalArgumentException.class, () -> validateReply(original, propose));

    MessageContent plain = MessageContent.text("x");
    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> validateReply(original, plain));
    assertTrue(missing.getMessage().contains("Negotiation reply"));
  }

  // TC-ENG-181 | unwrap：CompletionException/ExecutionException 解包，普通异常原样返回
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unwrapChains() throws Exception {
    RuntimeException plain = new RuntimeException("plain");
    assertSame(plain, unwrap(plain));
    IllegalStateException cause = new IllegalStateException("inner");
    assertSame(cause, unwrap(new CompletionException(cause)));
    assertSame(cause, unwrap(new ExecutionException(cause)));
  }

  // ---- 反射访问私有纯校验方法（断言 fail-closed 消息，不依赖内部实现） ----

  private static RuntimeException unwrapReflection(java.lang.reflect.InvocationTargetException e) {
    Throwable cause = e.getCause();
    if (cause instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    return new RuntimeException(cause);
  }

  private static void validateTaskExtension(AgentCard card, MessageContent content) {
    try {
      Method m =
          DefaultWorkflowEngineClient.class.getDeclaredMethod(
              "validateTaskExtension", AgentCard.class, MessageContent.class);
      m.setAccessible(true);
      m.invoke(null, card, content);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw unwrapReflection(e);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean carriesNegotiationMetadata(ReceivedMessage received) {
    try {
      Method m =
          DefaultWorkflowEngineClient.class.getDeclaredMethod(
              "carriesNegotiationMetadata", ReceivedMessage.class);
      m.setAccessible(true);
      return (boolean) m.invoke(null, received);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw unwrapReflection(e);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private static NegotiationContext validateReply(
      NegotiationContext original, MessageContent reply) {
    try {
      Method m =
          DefaultWorkflowEngineClient.class.getDeclaredMethod(
              "validateReply", NegotiationContext.class, MessageContent.class);
      m.setAccessible(true);
      return (NegotiationContext) m.invoke(null, original, reply);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw unwrapReflection(e);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private static Throwable unwrap(Throwable error) {
    try {
      Method m = DefaultWorkflowEngineClient.class.getDeclaredMethod("unwrap", Throwable.class);
      m.setAccessible(true);
      return (Throwable) m.invoke(null, error);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw unwrapReflection(e);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}

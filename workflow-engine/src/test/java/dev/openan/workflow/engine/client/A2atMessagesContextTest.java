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
import dev.openan.workflow.engine.model.ReceivedMessage;
import java.util.List;
import java.util.Map;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-760…766：A2atMessages 多层/冲突/非法 wire 字段分支。 */
class A2atMessagesContextTest {

  private static final String KEY = MetadataContent.NEGOTIATION_CONTEXT_METADATA_KEY;

  private static MessageContent context(String id, Object round, Object maxRounds, String action) {
    Map<String, Object> wire =
        Map.of(
            "id", id,
            "round", round,
            "maxRounds", maxRounds,
            "performative", action);
    return new MessageContent(List.of(new TextPart("x")), Map.of(KEY, wire), java.util.Set.of());
  }

  // TC-ENG-760 | from(null generated) → NPE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullGeneratedRejected() {
    assertThrows(
        NullPointerException.class, () -> A2atMessages.from(null, List.of(new TextPart("x"))));
  }

  // TC-ENG-761 | contextOf(ReceivedMessage) 无上下文 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void receivedWithoutContextRejected() {
    ReceivedMessage empty = new ReceivedMessage(null, Map.of(), List.of());
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(empty));
  }

  // TC-ENG-762 | 多层一致上下文（message + taskMetadata）合并通过
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void consistentContextAcrossLayers() {
    MessageContent message = context("n-1", 1, 3, NegotiationPerformative.ACCEPT.name());
    ReceivedMessage received =
        new ReceivedMessage(
            message,
            Map.of(
                KEY,
                Map.of(
                    "id",
                    "n-1",
                    "round",
                    1,
                    "maxRounds",
                    3,
                    "performative",
                    NegotiationPerformative.ACCEPT.name())),
            List.of());
    NegotiationContext context = A2atMessages.contextOf(received);
    assertEquals("n-1", context.id());
    assertEquals(1, context.round());
  }

  // TC-ENG-763 | 多层冲突 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void conflictingContextsRejected() {
    MessageContent message = context("n-1", 1, 3, NegotiationPerformative.ACCEPT.name());
    ReceivedMessage received =
        new ReceivedMessage(
            message,
            Map.of(
                KEY,
                Map.of(
                    "id",
                    "n-OTHER",
                    "round",
                    1,
                    "maxRounds",
                    3,
                    "performative",
                    NegotiationPerformative.ACCEPT.name())),
            List.of());
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(received));
  }

  // TC-ENG-764 | contextOf(Map)：缺字段/非 Map/非法 performative
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void invalidWireShapesRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> A2atMessages.contextOf(Map.of(KEY, "scalar")));
    assertThrows(
        IllegalArgumentException.class,
        () -> A2atMessages.contextOf(Map.of(KEY, Map.of("id", "x"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            A2atMessages.contextOf(
                Map.of(
                    KEY,
                    Map.of(
                        "id",
                        "x",
                        "round",
                        1,
                        "maxRounds",
                        3,
                        "performative",
                        "NOT-A-PERFORMATIVE"))));
  }

  // TC-ENG-765 | integer：非 Number/小数 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fractionalAndNonNumericRoundsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            A2atMessages.contextOf(
                Map.of(
                    KEY,
                    Map.of(
                        "id",
                        "x",
                        "round",
                        "1",
                        "maxRounds",
                        3,
                        "performative",
                        NegotiationPerformative.ACCEPT.name()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            A2atMessages.contextOf(
                Map.of(
                    KEY,
                    Map.of(
                        "id",
                        "x",
                        "round",
                        1.5,
                        "maxRounds",
                        3,
                        "performative",
                        NegotiationPerformative.ACCEPT.name()))));
  }

  // TC-ENG-766 | artifact 元数据层也提供上下文
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextFromArtifactMetadata() {
    ReceivedMessage received =
        new ReceivedMessage(
            null,
            Map.of(),
            List.of(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a")
                    .parts(List.of(new TextPart("x")))
                    .metadata(
                        Map.of(
                            KEY,
                            Map.of(
                                "id",
                                "n-9",
                                "round",
                                2,
                                "maxRounds",
                                4,
                                "performative",
                                NegotiationPerformative.PROPOSE.name())))
                    .build()));
    NegotiationContext context = A2atMessages.contextOf(received);
    assertEquals("n-9", context.id());
    assertEquals(2, context.round());
    assertTrue(context.performative() == NegotiationPerformative.PROPOSE);
  }
}

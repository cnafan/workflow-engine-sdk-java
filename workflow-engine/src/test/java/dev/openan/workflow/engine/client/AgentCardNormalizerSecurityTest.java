/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-840…843：AgentCardNormalizer 安全方案/要求的边缘形态。 */
class AgentCardNormalizerSecurityTest {

  // TC-ENG-840 | securitySchemes 为 null → 空 Map；非方案对象原样保留
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullSecuritySchemesNormalizesToEmpty() {
    Map<String, Object> input = new java.util.HashMap<>();
    input.put("name", "a");
    input.put("securitySchemes", null);
    Map<String, Object> card = AgentCardNormalizer.normalize(input);
    assertInstanceOf(Map.class, card.get("securitySchemes"));
    assertTrue(((Map<?, ?>) card.get("securitySchemes")).isEmpty());
  }

  // TC-ENG-841 | OpenAPI http bearer 与 apiKey 方案转换
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void openApiSecuritySchemesConvert() {
    Map<String, Object> card =
        AgentCardNormalizer.normalize(
            Map.of(
                "name",
                "a",
                "securitySchemes",
                Map.of(
                    "bearerAuth",
                    Map.of("scheme", "bearer", "bearerFormat", "JWT"),
                    "keyAuth",
                    Map.of("type", "apiKey", "in", "header", "name", "X-Key"))));
    Map<?, ?> schemes = (Map<?, ?>) card.get("securitySchemes");
    assertTrue(schemes.get("bearerAuth") instanceof Map);
    assertTrue(((Map<?, ?>) schemes.get("bearerAuth")).containsKey("httpAuthSecurityScheme"));
    assertTrue(((Map<?, ?>) schemes.get("keyAuth")).containsKey("apiKeySecurityScheme"));
  }

  // TC-ENG-842 | securityRequirements 非 List → 空；非 Map 元素跳过；schemes Map 原样
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void securityRequirementsNormalization() {
    Map<String, Object> invalid =
        AgentCardNormalizer.normalize(Map.of("name", "a", "securityRequirements", "scalar"));
    assertEquals(List.of(), invalid.get("securityRequirements"));
    Map<String, Object> mixed =
        AgentCardNormalizer.normalize(
            Map.of(
                "name",
                "a",
                "securityRequirements",
                List.of(
                    "scalar",
                    Map.of("schemes", List.of("bearerAuth")),
                    Map.of("schemes", Map.of("apiKey", Map.of())))));
    List<?> requirements = (List<?>) mixed.get("securityRequirements");
    assertEquals(2, requirements.size());
    assertTrue(requirements.get(0).toString().contains("bearerAuth"));
  }

  // TC-ENG-843 | 已是结构化方案与非 Map 方案原样保留
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void structuredAndScalarSchemesPassThrough() {
    Map<String, Object> card =
        AgentCardNormalizer.normalize(
            Map.of(
                "name",
                "a",
                "securitySchemes",
                Map.of(
                    "structured",
                    Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer")),
                    "scalar",
                    "literal")));
    Map<?, ?> schemes = (Map<?, ?>) card.get("securitySchemes");
    assertEquals("literal", schemes.get("scalar"));
    assertTrue(((Map<?, ?>) schemes.get("structured")).containsKey("httpAuthSecurityScheme"));
  }
}

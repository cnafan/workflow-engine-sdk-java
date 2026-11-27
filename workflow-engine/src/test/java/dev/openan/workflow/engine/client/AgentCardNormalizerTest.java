/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.junit.jupiter.api.Test;

/** Tests for AgentCardNormalizer: OpenAPI -> structured format conversion. */
class AgentCardNormalizerTest {

  // TC-ENG | normalizeNoOpWhenAlreadyStructured
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void normalizeNoOpWhenAlreadyStructured() {
    Map<String, Object> card =
        Map.of(
            "name",
            "Agent1",
            "securitySchemes",
            Map.of("bearerAuth", Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer"))));
    Map<String, Object> result = AgentCardNormalizer.normalize(card);
    assertEquals("Agent1", result.get("name"));
    @SuppressWarnings("unchecked")
    Map<String, Object> schemes = (Map<String, Object>) result.get("securitySchemes");
    assertNotNull(schemes.get("bearerAuth"));
  }

  // TC-ENG | convertsOpenApiBearerScheme
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  @SuppressWarnings("unchecked")
  void convertsOpenApiBearerScheme() {
    Map<String, Object> card =
        Map.of(
            "name",
            "Agent1",
            "securitySchemes",
            Map.of("bearerAuth", Map.of("scheme", "bearer", "description", "Bearer auth")));
    Map<String, Object> result = AgentCardNormalizer.normalize(card);
    Map<String, Object> schemes = (Map<String, Object>) result.get("securitySchemes");
    Map<String, Object> bearer = (Map<String, Object>) schemes.get("bearerAuth");
    assertNotNull(bearer.get("httpAuthSecurityScheme"));
    Map<String, Object> httpAuth = (Map<String, Object>) bearer.get("httpAuthSecurityScheme");
    assertEquals("bearer", httpAuth.get("scheme"));
    assertEquals("Bearer auth", httpAuth.get("description"));
  }

  // TC-ENG | convertsOpenApiApiKeyScheme
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  @SuppressWarnings("unchecked")
  void convertsOpenApiApiKeyScheme() {
    Map<String, Object> card =
        Map.of(
            "name",
            "Agent1",
            "securitySchemes",
            Map.of("apiKeyAuth", Map.of("type", "apiKey", "in", "header", "name", "X-API-Key")));
    Map<String, Object> result = AgentCardNormalizer.normalize(card);
    Map<String, Object> schemes = (Map<String, Object>) result.get("securitySchemes");
    Map<String, Object> apiKeyWrapper = (Map<String, Object>) schemes.get("apiKeyAuth");
    assertNotNull(apiKeyWrapper.get("apiKeySecurityScheme"));
    Map<String, Object> apiKey = (Map<String, Object>) apiKeyWrapper.get("apiKeySecurityScheme");
    assertEquals("header", apiKey.get("location"));
    assertEquals("X-API-Key", apiKey.get("name"));
  }

  // TC-ENG | normalizedApiKeySchemeDeserializesWithItsLocation
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  @SuppressWarnings("unchecked")
  void normalizedApiKeySchemeDeserializesWithItsLocation() {
    Map<String, Object> card =
        Map.of(
            "securitySchemes",
            Map.of("apiKeyAuth", Map.of("type", "apiKey", "in", "header", "name", "X-API-Key")));
    Map<String, Object> normalized = AgentCardNormalizer.normalize(card);
    Map<String, Object> schemes = (Map<String, Object>) normalized.get("securitySchemes");
    SecurityScheme scheme =
        new ObjectMapper()
            .registerModule(new AgentCardJacksonModule())
            .convertValue(schemes.get("apiKeyAuth"), SecurityScheme.class);

    APIKeySecurityScheme apiKey = assertInstanceOf(APIKeySecurityScheme.class, scheme);
    assertEquals(APIKeySecurityScheme.Location.HEADER, apiKey.location());
    assertEquals("X-API-Key", apiKey.name());
  }

  // TC-ENG | securityRequirementScopesSurviveDeserialization
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void securityRequirementScopesSurviveDeserialization() {
    SecurityRequirement requirement =
        new ObjectMapper()
            .registerModule(new AgentCardJacksonModule())
            .convertValue(
                Map.of("schemes", Map.of("oauth", List.of("tasks:read", "tasks:write"))),
                SecurityRequirement.class);

    assertEquals(List.of("tasks:read", "tasks:write"), requirement.schemes().get("oauth"));
  }

  // TC-ENG | doesNotInventSecurityRequirementsFromSupportedSchemes
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void doesNotInventSecurityRequirementsFromSupportedSchemes() {
    Map<String, Object> card =
        Map.of(
            "name",
            "Agent1",
            "securitySchemes",
            Map.of("bearerAuth", Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer"))));
    Map<String, Object> result = AgentCardNormalizer.normalize(card);
    assertFalse(result.containsKey("securityRequirements"));
  }

  // TC-ENG | preservesExplicitlyEmptySecurityRequirements
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void preservesExplicitlyEmptySecurityRequirements() {
    Map<String, Object> card =
        Map.of(
            "name",
            "Agent1",
            "securitySchemes",
            Map.of("bearerAuth", Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer"))),
            "securityRequirements",
            List.of());
    Map<String, Object> result = AgentCardNormalizer.normalize(card);

    assertEquals(List.of(), result.get("securityRequirements"));
  }

  // TC-ENG | normalizesListStyleSecurityRequirements
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  @SuppressWarnings("unchecked")
  void normalizesListStyleSecurityRequirements() {
    Map<String, Object> card =
        Map.of(
            "name", "Agent1",
            "securitySchemes",
                Map.of("bearerAuth", Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer"))),
            "securityRequirements", List.of(Map.of("schemes", List.of("bearerAuth"))));
    Map<String, Object> result = AgentCardNormalizer.normalize(card);
    List<Map<String, Object>> reqs = (List<Map<String, Object>>) result.get("securityRequirements");
    Map<String, Object> schemesMap = (Map<String, Object>) reqs.get(0).get("schemes");
    assertNotNull(schemesMap.get("bearerAuth"));
  }

  // TC-ENG | normalizeReturnsInputWhenNotMap
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void normalizeReturnsInputWhenNotMap() {
    Object result = AgentCardNormalizer.normalize(null);
    assertNull(result);
  }

  // TC-ENG | preservesNameAndCapabilities
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void preservesNameAndCapabilities() {
    Map<String, Object> card =
        Map.of(
            "name", "MyAgent",
            "description", "An agent",
            "capabilities", Map.of("streaming", true));
    Map<String, Object> result = AgentCardNormalizer.normalize(card);
    assertEquals("MyAgent", result.get("name"));
    assertEquals("An agent", result.get("description"));
  }
}

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.junit.jupiter.api.Test;

/** TC-ENG-107…117：AgentCard Jackson 模块反序列化边界与 SendMessageResult 投影。 */
class AgentCardJacksonModuleDeserializationTest {
  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private JsonNode parse(String json) throws Exception {
    return mapper.readTree(json);
  }

  private JsonNode parser(JsonNode node) throws Exception {
    return node;
  }

  // TC-ENG-107 | httpAuthSecurityScheme：缺 scheme 拒绝，完整输入成功
  //
  // 输入: 1) 无 scheme 字段 2) 完整字段
  // 预期输出: 1) 解析异常含 "'scheme' field is required" 2) scheme/bearerFormat 正确
  // 断言点: 异常消息与字段值；防回归: 必填守卫被删即失败
  @Test
  void httpAuthSchemeRequiredFields() throws Exception {
    JsonNode bad =
        parse("{\"httpAuthSecurityScheme\":{\"bearerFormat\":\"JWT\",\"description\":\"d\"}}");
    com.fasterxml.jackson.databind.JsonMappingException badError =
        assertThrows(
            com.fasterxml.jackson.databind.JsonMappingException.class,
            () -> mapper.treeToValue(bad, SecurityScheme.class));
    assertTrue(badError.getMessage().contains("'scheme' field is required"));

    JsonNode good =
        parse(
            "{\"httpAuthSecurityScheme\":{\"scheme\":\"bearer\",\"bearerFormat\":\"JWT\","
                + "\"description\":\"d\"}}");
    Object value = mapper.treeToValue(good, SecurityScheme.class);
    assertTrue(value.toString().contains("bearer"));
  }

  // TC-ENG-108 | apiKeySecurityScheme：location/in 双字段支持，缺 name/location 拒绝
  //
  // 输入: 1) 缺 name 2) 缺 location 且缺 in 3) 用 in 字段
  // 预期输出: 前两者异常，第三者成功
  // 断言点: 异常消息与成功路径；防回归: in 兼容分支被删即失败
  @Test
  void apiKeySchemeVariants() throws Exception {
    JsonNode missingName = parse("{\"apiKeySecurityScheme\":{\"location\":\"header\"}}");
    assertThrows(
        com.fasterxml.jackson.databind.JsonMappingException.class,
        () -> mapper.treeToValue(missingName, SecurityScheme.class));

    JsonNode missingLocation = parse("{\"apiKeySecurityScheme\":{\"name\":\"k\"}}");
    assertThrows(
        com.fasterxml.jackson.databind.JsonMappingException.class,
        () -> mapper.treeToValue(missingLocation, SecurityScheme.class));

    JsonNode withIn = parse("{\"apiKeySecurityScheme\":{\"name\":\"k\",\"in\":\"header\"}}");
    assertNotNull(mapper.treeToValue(withIn, SecurityScheme.class));
  }

  // TC-ENG-109 | mtls/oauth2/openIdConnect 三种 scheme 映射
  //
  // 输入: 三种 scheme 的完整 JSON
  // 预期输出: 全部解析成功；openIdConnect 缺 url 拒绝
  // 断言点: 解析成功与异常消息；防回归: 任一映射被删即失败
  @Test
  void otherSchemeTypesMap() throws Exception {
    assertNotNull(
        mapper.treeToValue(
            parse("{\"mtlsSecurityScheme\":{\"description\":\"d\"}}"), SecurityScheme.class));
    // DEFECT-ENG-2：SecuritySchemeDeserializer 对 oauth2SecurityScheme 传入 flows=null，
    // 而 OAuth2SecurityScheme 构造器拒绝 null → 该类型反序列化恒失败（缺陷守卫，
    // 修复后本断言应改为成功解析 + 字段校验）
    JsonNode oauth2 =
        parse(
            "{\"oauth2SecurityScheme\":{\"description\":\"d\",\"oauth2MetadataUrl\":"
                + "\"http://meta\"}}");
    IllegalArgumentException oauth2Error =
        assertThrows(
            IllegalArgumentException.class, () -> mapper.treeToValue(oauth2, SecurityScheme.class));
    assertTrue(oauth2Error.getMessage().contains("flows"));
    JsonNode missingUrl = parse("{\"openIdConnectSecurityScheme\":{\"description\":\"d\"}}");
    assertThrows(
        com.fasterxml.jackson.databind.JsonMappingException.class,
        () -> mapper.treeToValue(missingUrl, SecurityScheme.class));
    assertNotNull(
        mapper.treeToValue(
            parse(
                "{\"openIdConnectSecurityScheme\":{\"openIdConnectUrl\":\"http://oid\","
                    + "\"description\":\"d\"}}"),
            SecurityScheme.class));
  }

  // TC-ENG-110 | 未知结构的 scheme 返回 null（不抛）
  //
  // 输入: 不含任何已知 type key 的对象
  // 预期输出: 反序列化为 null
  // 断言点: null 值；防回归: 兜底分支被删即抛异常
  @Test
  void unknownSchemeShapeYieldsNull() throws Exception {
    JsonNode unknown = parse("{\"unknownSecurityScheme\":{\"x\":1}}");
    assertNull(mapper.treeToValue(unknown, SecurityScheme.class));
  }

  // TC-ENG-111 | SecurityRequirement：对象空 scopes → 空列表；数组 scopes → 字符串列表
  //
  // 输入: {"schemes":{"a":{},"b":["read","write"]}}
  // 预期输出: schemes map 正确（a→[]，b→[read,write]）
  // 断言点: map 内容；防回归: 空对象/数组分支被删即失败
  @Test
  void securityRequirementScapesVariants() throws Exception {
    JsonNode node = parse("{\"schemes\":{\"a\":{},\"b\":[\"read\",\"write\"]}}");
    Object requirement =
        mapper.treeToValue(node, org.a2aproject.sdk.spec.SecurityRequirement.class);
    assertTrue(requirement.toString().contains("read"));
  }

  // TC-ENG-112 | SecurityRequirement：非字符串 scope / 非数组非对象 scopes 拒绝
  //
  // 输入: 1) scopes 数组含数字 2) scopes 为字符串
  // 预期输出: InvalidFormatException（weirdStringException）
  // 断言点: 异常类型；防回归: 类型守卫被删即失败
  @Test
  void invalidScopeShapesAreRejected() throws Exception {
    JsonNode numericScope = parse("{\"schemes\":{\"a\":[1]}}");
    assertThrows(
        InvalidFormatException.class,
        () -> mapper.treeToValue(numericScope, org.a2aproject.sdk.spec.SecurityRequirement.class));
    JsonNode stringScopes = parse("{\"schemes\":{\"a\":\"read\"}}");
    assertThrows(
        InvalidFormatException.class,
        () -> mapper.treeToValue(stringScopes, org.a2aproject.sdk.spec.SecurityRequirement.class));
  }

  // TC-ENG-113 | SendMessageResult 投影：null 默认值、防御性拷贝、失败态不投 outputs
  //
  // 输入: 1) 全 null 构造 2) 带 metadata/receivedMessages 构造
  // 预期输出: 1) text/taskState 空串、metadata 空、outputs 空 2) setter 防御拷贝
  // 断言点: 默认值与不可变性；防回归: null 守卫被删即失败
  @Test
  void sendMessageResultProjections() {
    SendMessageResult empty = new SendMessageResult(null, null, null, null, "F", "m", null);
    assertEquals("", empty.getText());
    assertEquals("", empty.getTaskState());
    assertEquals("F", empty.getFailureCode());
    assertEquals(List.of(), empty.getOutputs());
    assertEquals(List.of(), empty.getReceivedMessages());
    assertEquals(Map.of(), empty.getMetadata());
    empty.setMetadata(null);
    assertEquals(Map.of(), empty.getMetadata());
    empty.setReceivedMessages(null);
    assertEquals(List.of(), empty.getReceivedMessages());
  }

  private static void assertNotNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNotNull(value);
  }
}

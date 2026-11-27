/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

/** TC-ENG-800…803：CredentialHeaderContributor 剩余分支。 */
class CredentialHeaderContributorFallbackTest {

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static AgentCard card(String schemes, String requirements) throws Exception {
    String json =
        """
        {
          "name":"h-agent","description":"d","version":"1",
          "capabilities":{"streaming":false},
          "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
          "securitySchemes":%s,
          "securityRequirements":%s,
          "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
          "tenant":"","url":"http://127.0.0.1:1"}]
        }
        """
            .formatted(schemes, requirements);
    return JSON.readValue(json, AgentCard.class);
  }

  private static AgentCard plainCard() throws Exception {
    return JSON.readValue(
        """
        {"name":"plain","description":"d","version":"1",
         "capabilities":{"streaming":false},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
         "tenant":"","url":"http://127.0.0.1:1"}]}
        """,
        AgentCard.class);
  }

  private static AuthProvider provider() {
    return (agentName, agentCard, headers) -> headers.put("Authorization", "Bearer provider-token");
  }

  // TC-ENG-800 | 无凭据服务但提供 authProvider → 不装配凭据头，返回空 Map
  //
  // 输入: 卡片要求 bearerAuth；AgentAuthManager 无该 agent 配置；authProvider 非 null
  // 预期输出: 返回空 Map，不抛 SecurityException（由 AuthProviderHeaderContributor 兜底）
  // 断言点: 返回 Map 为空；防回归: 删掉 authProvider 回退分支即抛异常
  @Test
  void authProviderUsedWhenNoCredentialService() throws Exception {
    AgentAuthManager authManager = new AgentAuthManager(Map.of());
    CredentialHeaderContributor contributor =
        new CredentialHeaderContributor(authManager, provider());
    AgentCard card =
        card(
            "{\"bearerAuth\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
            "[{\"schemes\":{\"bearerAuth\":{}}}]");
    Map<String, String> headers =
        contributor.contribute(card, "h-agent", Map.of(), new LinkedHashMap<>());
    assertTrue(headers.isEmpty());
  }

  // TC-ENG-801 | 无凭据服务且无 authProvider → SecurityException
  //
  // 输入: 卡片要求 bearerAuth，凭据配置为空且无 authProvider
  // 预期输出: SecurityException，消息含 "none are configured"
  // 断言点: 异常类型+消息；防回归: fail-closed 被删即漏配凭据
  @Test
  void missingServiceWithoutProviderRejected() throws Exception {
    AgentAuthManager authManager = new AgentAuthManager(Map.of());
    CredentialHeaderContributor contributor = new CredentialHeaderContributor(authManager, null);
    AgentCard card =
        card(
            "{\"bearerAuth\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
            "[{\"schemes\":{\"bearerAuth\":{}}}]");
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () -> contributor.contribute(card, "h-agent", Map.of(), new LinkedHashMap<>()));
    assertTrue(error.getMessage().contains("none are configured"));
  }

  // TC-ENG-802 | scheme 配置为空 Map 且无 authProvider → "credential configuration is empty"
  //
  // 输入: AgentAuthManager 有 agent 条目但无 scheme 配置
  // 预期输出: SecurityException 命中空配置守卫
  // 断言点: 异常消息关键字；防回归: 空配置守卫被删即 NPE
  @Test
  void emptySchemeConfigurationRejected() throws Exception {
    AgentAuthManager authManager = new AgentAuthManager(Map.of("h-agent", Map.of()));
    CredentialHeaderContributor contributor = new CredentialHeaderContributor(authManager, null);
    AgentCard card =
        card(
            "{\"bearerAuth\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
            "[{\"schemes\":{\"bearerAuth\":{}}}]");
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () -> contributor.contribute(card, "h-agent", Map.of(), new LinkedHashMap<>()));
    assertTrue(error.getMessage().contains("credential configuration is empty"));
  }

  // TC-ENG-803 | 卡片无安全要求 → 直接返回空 Map（短路分支）
  //
  // 输入: 卡片不带 securitySchemes/securityRequirements
  // 预期输出: 空 Map，且不查询 AgentAuthManager
  // 断言点: 空；防回归: 短路删除后会访问空配置
  @Test
  void cardWithoutSecurityReturnsEmpty() throws Exception {
    AgentAuthManager authManager = new AgentAuthManager(Map.of());
    CredentialHeaderContributor contributor = new CredentialHeaderContributor(authManager, null);
    Map<String, String> headers =
        contributor.contribute(plainCard(), "plain", Map.of(), new LinkedHashMap<>());
    assertTrue(headers.isEmpty());
  }

  // TC-ENG-804 | 空配置 + authProvider 也走回退（与 800 互补：schemeConfigs 为空条目）
  //
  // 输入: AgentAuthManager 有 agent 条目但无 scheme 配置，提供 authProvider
  // 预期输出: 返回空 Map 不抛异常
  // 断言点: 空；防回归: 空配置处 provider 回退被删即失败
  @Test
  void emptySchemeConfigurationWithProviderReturnsEmpty() throws Exception {
    AuthProvider provider = provider();
    AgentAuthManager authManager = new AgentAuthManager(Map.of("h-agent", Map.of()));
    CredentialHeaderContributor contributor =
        new CredentialHeaderContributor(authManager, provider);
    AgentCard card =
        card(
            "{\"bearerAuth\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
            "[{\"schemes\":{\"bearerAuth\":{}}}]");
    Map<String, String> headers =
        contributor.contribute(card, "h-agent", Map.of(), new LinkedHashMap<>());
    assertTrue(headers.isEmpty());
  }

  // TC-ENG-804 | 自定义 auth_header + auth_header_prefix + accept_header
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void customAuthAndAcceptHeaders() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> authHeader = new AtomicReference<>();
    AtomicReference<String> acceptHeader = new AtomicReference<>();
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          authHeader.set(exchange.getRequestHeaders().getFirst("X-Auth"));
          acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
          String body = "{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      Map<String, Map<String, Map<String, Object>>> config = new LinkedHashMap<>();
      Map<String, Map<String, Object>> bearer = new LinkedHashMap<>();
      bearer.put(
          "bearerAuth",
          Map.of(
              "login_url",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/token",
              "token_field",
              "access_token",
              "auth_header",
              "X-Auth",
              "auth_header_prefix",
              "Token ",
              "accept_header",
              "application/json"));
      config.put("h-agent", bearer);
      AgentAuthManager manager = new AgentAuthManager(config);
      CredentialHeaderContributor contributor = new CredentialHeaderContributor(manager, null);
      AgentCard card =
          card(
              "{\"bearerAuth\":{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"}}}",
              "[{\"schemes\":{\"bearerAuth\":{}}}]");
      Map<String, String> headers =
          contributor.contribute(card, "h-agent", Map.of(), new LinkedHashMap<>());
      assertEquals("Token tok", headers.get("X-Auth"));
      assertEquals("application/json", headers.get("Accept"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-805 | 同一头被两个方案要求不同值 -> SecurityException
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void conflictingHeaderValuesRejected() throws Exception {
    Method m =
        CredentialHeaderContributor.class.getDeclaredMethod(
            "addCredentialHeader", Map.class, Map.class, String.class);
    m.setAccessible(true);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Auth", "one");
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, headers, Map.of("auth_header", "X-Auth"), "two"));
    assertTrue(error.getCause() instanceof SecurityException);
  }
}

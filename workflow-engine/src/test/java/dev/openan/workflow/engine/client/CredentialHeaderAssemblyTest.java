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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-114…126：凭据头装配的 fail-closed 分支与登录令牌获取矩阵。 */
class CredentialHeaderAssemblyTest {
  private HttpServer server;
  private int port;
  private volatile int loginStatus = 200;
  private volatile String loginBody = "{\"accessSession\":\"tok-1\",\"expires\":\"3600\"}";
  private volatile boolean includeBody = true;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/login",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (includeBody) {
            byte[] bytes = loginBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(loginStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
          } else {
            exchange.sendResponseHeaders(loginStatus, -1);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AgentCard cardWithSchemes(Map<String, SecurityScheme> schemes) {
    return AgentCard.builder()
        .name("agent-a")
        .description("d")
        .url("http://127.0.0.1:1")
        .version("1.0.0")
        .capabilities(org.a2aproject.sdk.spec.AgentCapabilities.builder().build())
        .supportedInterfaces(
            List.of(
                new org.a2aproject.sdk.spec.AgentInterface(
                    "http://127.0.0.1:1",
                    org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION)))
        .defaultInputModes(List.of("text"))
        .defaultOutputModes(List.of("text"))
        .skills(List.of())
        .securitySchemes(schemes)
        .securityRequirements(
            List.of(
                new org.a2aproject.sdk.spec.SecurityRequirement(Map.of("bearerAuth", List.of()))))
        .build();
  }

  private CredentialHeaderContributor contributor(AgentAuthManager authManager) {
    return new CredentialHeaderContributor(authManager, null);
  }

  private Map<String, Map<String, Object>> loginSchemeConfig(String tokenField) {
    Map<String, Object> scheme = new HashMap<String, Object>();
    scheme.put("login_url", "http://127.0.0.1:" + port + "/login");
    scheme.put("token_field", tokenField);
    scheme.put("username", "u");
    scheme.put("password", "p");
    return Map.of("bearerAuth", scheme);
  }

  // TC-ENG-114 | 卡片未声明安全方案 → 返回空头
  //
  // 输入: securitySchemes 为空的 AgentCard
  // 预期输出: contribute 返回空 map
  // 断言点: 空 map；防回归: 空守卫被删即失败
  @Test
  void noSecuritySchemesReturnsEmptyHeaders() {
    CredentialHeaderContributor contributor = contributor(new AgentAuthManager());
    AgentCard card =
        AgentCard.builder()
            .name("agent-a")
            .description("d")
            .url("http://x")
            .version("1")
            .capabilities(org.a2aproject.sdk.spec.AgentCapabilities.builder().build())
            .supportedInterfaces(
                List.of(
                    new org.a2aproject.sdk.spec.AgentInterface(
                        "http://x",
                        org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION)))
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .skills(List.of())
            .build();
    assertTrue(contributor.contribute(card, "agent-a", Map.of(), Map.of()).isEmpty());
  }

  // TC-ENG-115 | 无凭据配置 fail-closed
  //
  // 输入: 卡片要求凭据但 authManager 无任何配置
  // 预期输出: SecurityException("credential configuration is empty")
  // 断言点: 异常消息；防回归: 空配置被放行即失败
  @Test
  void emptyCredentialConfigurationFailsClosed() {
    CredentialHeaderContributor contributor = contributor(new AgentAuthManager());
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () ->
                contributor.contribute(
                    cardWithSchemes(schemes("bearerAuth")), "agent-a", Map.of(), Map.of()));
    assertTrue(error.getMessage().contains("none are configured"), error.getMessage());
  }

  // TC-ENG-116 | requirement 引用未声明 scheme fail-closed
  //
  // 输入: requirement 引用 "other" 但卡片只声明 "bearerAuth"，凭据配置齐全
  // 预期输出: SecurityException，消息含 "not declared by the AgentCard"
  // 断言点: 异常消息；防回归: 引用校验被删即失败
  @Test
  void undeclaredSchemeFailsClosed() throws Exception {
    AgentAuthManager authManager =
        new AgentAuthManager(
            Map.of(
                "agent-a",
                Map.of("bearerAuth", loginSchemeConfig("accessSession").get("bearerAuth"))),
            (CredentialHttpTransport) null,
            null);
    CredentialHeaderContributor contributor = contributor(authManager);
    AgentCard card =
        AgentCard.builder()
            .name("agent-a")
            .description("d")
            .url("http://x")
            .version("1")
            .capabilities(org.a2aproject.sdk.spec.AgentCapabilities.builder().build())
            .supportedInterfaces(
                List.of(
                    new org.a2aproject.sdk.spec.AgentInterface(
                        "http://x",
                        org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION)))
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .skills(List.of())
            .securitySchemes(schemes("bearerAuth"))
            .securityRequirements(
                List.of(
                    new org.a2aproject.sdk.spec.SecurityRequirement(Map.of("other", List.of()))))
            .build();
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () -> contributor.contribute(card, "agent-a", Map.of(), Map.of()));
    assertTrue(error.getMessage().contains("not declared by the AgentCard"));
  }

  // TC-ENG-117 | scheme 有声明但缺凭据配置 fail-closed
  //
  // 输入: 凭据配置只含 "other"，卡片要求 "bearerAuth"
  // 预期输出: SecurityException，消息含 "no configuration for scheme"
  // 断言点: 异常消息；防回归: 配置缺失校验被删即失败
  @Test
  void schemeWithoutConfigurationFailsClosed() throws Exception {
    AgentAuthManager authManager =
        new AgentAuthManager(
            typed(Map.of("agent-a", Map.of("other", Map.of("login_url", "http://127.0.0.1:1/x")))),
            (CredentialHttpTransport) null,
            null);
    CredentialHeaderContributor contributor = contributor(authManager);
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () ->
                contributor.contribute(
                    cardWithSchemes(schemes("bearerAuth")), "agent-a", Map.of(), Map.of()));
    assertTrue(error.getMessage().contains("no configuration for scheme"));
  }

  // TC-ENG-118 | 登录失败（不可达 URL）→ 无凭据 fail-closed
  //
  // 输入: login_url 指向不可达端口
  // 预期输出: SecurityException，消息含 "no credential for scheme"
  // 断言点: 异常消息；防回归: 登录失败被放行即失败
  @Test
  void loginFailureFailsClosed() throws Exception {
    Map<String, Map<String, Object>> config =
        Map.of(
            "bearerAuth",
            Map.of(
                "login_url", "http://127.0.0.1:1/login",
                "token_field", "accessSession",
                "username", "u",
                "password", "p"));
    AgentAuthManager authManager =
        new AgentAuthManager(
            typed(Map.of("agent-a", config)), (CredentialHttpTransport) null, null);
    CredentialHeaderContributor contributor = contributor(authManager);
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () ->
                contributor.contribute(
                    cardWithSchemes(schemes("bearerAuth")), "agent-a", Map.of(), Map.of()));
    assertTrue(error.getMessage().contains("no credential for scheme"));
  }

  // TC-ENG-119 | 登录 400 响应 → 无凭据 fail-closed
  //
  // 输入: 登录服务返回 400 + 空 body
  // 预期输出: SecurityException，消息含 "no credential for scheme"
  // 断言点: 错误流分支；防回归: 错误状态码被忽略即失败
  @Test
  void loginErrorStatusFailsClosed() throws Exception {
    loginStatus = 400;
    includeBody = false;
    HttpClient client = HttpClient.newHttpClient();
    AgentCredentialService service =
        new AgentCredentialService("agent-a", loginSchemeConfig("accessSession"), client);
    assertNull(service.getCredential("bearerAuth", null));
  }

  // TC-ENG-120 | 登录响应缺 token 字段 → null
  //
  // 输入: 响应不含配置的 token_field
  // 预期输出: getCredential 返回 null
  // 断言点: null；防回归: 缺字段被兜底成空串即失败
  @Test
  void loginResponseWithoutTokenFieldYieldsNull() {
    HttpClient client = HttpClient.newHttpClient();
    AgentCredentialService service =
        new AgentCredentialService("agent-a", loginSchemeConfig("missing.field"), client);
    assertNull(service.getCredential("bearerAuth", null));
  }

  // TC-ENG-121 | 登录快乐路径：JSON 体 + 嵌套 token 路径 + 字符串过期秒数
  //
  // 输入: 响应 {"data":{"accessSession":"tok-9","expires":"7200"}}，token_field=data.accessSession
  // 预期输出: getCredential 返回 tok-9
  // 断言点: 嵌套路径取值；防回归: extractNestedValue/toLong 被删即失败
  @Test
  void loginHappyPathWithNestedTokenPath() {
    loginBody = "{\"data\":{\"accessSession\":\"tok-9\",\"expires\":\"7200\"}}";
    HttpClient client = HttpClient.newHttpClient();
    AgentCredentialService service =
        new AgentCredentialService("agent-a", loginSchemeConfig("data.accessSession"), client);
    assertEquals("tok-9", service.getCredential("bearerAuth", null));
  }

  // TC-ENG-122 | 表单编码登录 + 默认 HttpClient 构造分支
  //
  // 输入: content_type=x-www-form-urlencoded；不传 HttpClient（默认构造）
  // 预期输出: getCredential 成功
  // 断言点: 令牌值；防回归: 表单编码或默认客户端分支被删即失败
  @Test
  void formEncodedLoginWithDefaultClient() {
    loginBody = "{\"accessSession\":\"tok-form\"}";
    Map<String, Map<String, Object>> config =
        Map.of(
            "bearerAuth",
            new HashMap<String, Object>(
                Map.ofEntries(
                    Map.entry("login_url", "http://127.0.0.1:" + port + "/login"),
                    Map.entry("token_field", "accessSession"),
                    Map.entry("content_type", "application/x-www-form-urlencoded"),
                    Map.entry("username", "u"),
                    Map.entry("password", "p"))));
    AgentCredentialService service = new AgentCredentialService("agent-a", config);
    assertEquals("tok-form", service.getCredential("bearerAuth", null));
  }

  // TC-ENG-123 | 贡献头快乐路径：自定义 auth_header/prefix/accept_header
  //
  // 输入: 登录成功 + scheme 配置 auth_header=X-Auth, prefix=Bearer , accept_header
  // 预期输出: 头含 X-Auth=Bearer tok-1 与 Accept
  // 断言点: 头键值；防回归: addCredentialHeader 分支被删即失败
  @Test
  void contributorBuildsCustomHeaders() {
    Map<String, Map<String, Object>> config =
        Map.of(
            "bearerAuth",
            new HashMap<String, Object>(
                Map.ofEntries(
                    Map.entry("login_url", "http://127.0.0.1:" + port + "/login"),
                    Map.entry("token_field", "accessSession"),
                    Map.entry("auth_header", "X-Auth"),
                    Map.entry("auth_header_prefix", "Bearer "),
                    Map.entry("accept_header", "application/json"),
                    Map.entry("username", "u"),
                    Map.entry("password", "p"))));
    AgentAuthManager authManager =
        new AgentAuthManager(
            typed(Map.of("agent-a", config)), (CredentialHttpTransport) null, null);
    CredentialHeaderContributor contributor = contributor(authManager);
    Map<String, String> headers =
        contributor.contribute(
            cardWithSchemes(schemes("bearerAuth")), "agent-a", Map.of(), Map.of());
    assertEquals("Bearer tok-1", headers.get("X-Auth"));
    assertEquals("application/json", headers.get("Accept"));
  }

  // TC-ENG-124 | 默认 Authorization: Bearer 头（无 auth_header 配置）
  //
  // 输入: scheme 配置仅 login 信息
  // 预期输出: Authorization=Bearer tok-1
  // 断言点: 默认头；防回归: 默认分支被删即失败
  @Test
  void contributorDefaultsToBearerAuthorization() {
    AgentAuthManager authManager =
        new AgentAuthManager(
            Map.of("agent-a", loginSchemeConfig("accessSession")),
            (CredentialHttpTransport) null,
            null);
    CredentialHeaderContributor contributor = contributor(authManager);
    Map<String, String> headers =
        contributor.contribute(
            cardWithSchemes(schemes("bearerAuth")), "agent-a", Map.of(), Map.of());
    assertEquals("Bearer tok-1", headers.get("Authorization"));
  }

  // TC-ENG-125 | 头冲突 fail-closed（同头不同值）
  //
  // 输入: 两个 scheme 均写 X-Auth 且登录返回不同 token；requirement 同时引用两者
  // 预期输出: SecurityException("conflicting values")
  // 断言点: putHeader 冲突守卫；防回归: 冲突被静默覆盖即失败
  @Test
  void conflictingHeaderValuesFailClosed() throws Exception {
    server.createContext(
        "/login2",
        exchange -> {
          byte[] bytes = "{\"accessSession\":\"tok-2\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    Map<String, Map<String, Object>> config =
        Map.of(
            "schemeOne",
            new HashMap<String, Object>(
                Map.ofEntries(
                    Map.entry("login_url", "http://127.0.0.1:" + port + "/login"),
                    Map.entry("token_field", "accessSession"),
                    Map.entry("auth_header", "X-Auth"),
                    Map.entry("auth_header_prefix", "One-"),
                    Map.entry("username", "u"),
                    Map.entry("password", "p"))),
            "schemeTwo",
            new HashMap<String, Object>(
                Map.ofEntries(
                    Map.entry("login_url", "http://127.0.0.1:" + port + "/login2"),
                    Map.entry("token_field", "accessSession"),
                    Map.entry("auth_header", "X-Auth"),
                    Map.entry("auth_header_prefix", "Two-"),
                    Map.entry("username", "u"),
                    Map.entry("password", "p"))));
    AgentAuthManager authManager =
        new AgentAuthManager(
            typed(Map.of("agent-a", config)), (CredentialHttpTransport) null, null);
    CredentialHeaderContributor contributor = contributor(authManager);
    AgentCard card =
        AgentCard.builder()
            .name("agent-a")
            .description("d")
            .url("http://x")
            .version("1")
            .capabilities(org.a2aproject.sdk.spec.AgentCapabilities.builder().build())
            .supportedInterfaces(
                List.of(
                    new org.a2aproject.sdk.spec.AgentInterface(
                        "http://x",
                        org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION)))
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .skills(List.of())
            .securitySchemes(
                Map.of(
                    "schemeOne",
                    new org.a2aproject.sdk.spec.HTTPAuthSecurityScheme(null, "basic", null),
                    "schemeTwo",
                    new org.a2aproject.sdk.spec.HTTPAuthSecurityScheme(null, "bearer", null)))
            .securityRequirements(
                List.of(
                    new org.a2aproject.sdk.spec.SecurityRequirement(
                        Map.of("schemeOne", List.of(), "schemeTwo", List.of()))))
            .build();
    SecurityException error =
        assertThrows(
            SecurityException.class,
            () -> contributor.contribute(card, "agent-a", Map.of(), Map.of()));
    assertTrue(error.getMessage().contains("conflicting values"));
  }

  // TC-ENG-126 | 加密凭据校验：合法密钥解密通过，坏密钥构造即抛
  //
  // 输入: 配置含 enc: 值；1) 正确密钥 2) 错误密钥
  // 预期输出: 1) 构造成功 2) IllegalStateException
  // 断言点: 构造结果；防回归: enc 预校验被删即失败
  @Test
  void encryptedCredentialValidationAtConstruction() {
    String key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String encrypted = CredentialCrypto.encrypt("plain-pw", key);
    Map<String, Map<String, Object>> config = Map.of("agent", Map.of("value", encrypted));
    AgentAuthManager ok = new AgentAuthManager(typed(config), (HttpClient) null, key);
    assertNotNull(ok);
    IllegalStateException badError =
        assertThrows(
            IllegalStateException.class,
            () ->
                new AgentAuthManager(
                    typed(Map.of("agent", Map.of("value", "enc:AAAA:BBBB"))),
                    (HttpClient) null,
                    key));
    assertTrue(
        badError.getMessage().contains("Credential decryption failed"), badError.getMessage());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Map<String, Object>>> typed(Object raw) {
    return (Map<String, Map<String, Map<String, Object>>>) (Map<?, ?>) raw;
  }

  private static Map<String, SecurityScheme> schemes(String name) {
    return Map.of(name, new org.a2aproject.sdk.spec.HTTPAuthSecurityScheme(null, "bearer", null));
  }

  private static void assertNotNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNotNull(value);
  }
}

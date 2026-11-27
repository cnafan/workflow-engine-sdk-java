/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-680…686：AgentCredentialService 嵌套提取/TTL/表单/状态码等分支。 */
class AgentCredentialServiceLoginTest {

  private static HttpServer server(String response, int status) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
  }

  private static Map<String, Map<String, Object>> scheme(String url, Map<String, Object> extra) {
    Map<String, Object> cfg =
        new java.util.HashMap<>(
            Map.of(
                "login_url",
                url,
                "token_field",
                "access_token",
                "request_fields",
                Map.of("username", "user", "password", "pass")));
    cfg.putAll(extra);
    return Map.of("bearer", cfg);
  }

  // TC-ENG-680 | 未知 scheme → null（缓存未命中且无配置）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unknownSchemeReturnsNull() {
    AgentCredentialService service = new AgentCredentialService("a", Map.of());
    assertNull(service.getCredential("missing", null));
  }

  // TC-ENG-681 | 无 login_url → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingLoginUrlReturnsNull() {
    AgentCredentialService service =
        new AgentCredentialService("a", Map.of("bearer", Map.of("token_field", "x")));
    assertNull(service.getCredential("bearer", null));
  }

  // TC-ENG-682 | 4xx/5xx 登录响应 → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void errorStatusReturnsNull() throws Exception {
    HttpServer server = server("{\"access_token\":\"t\"}", 401);
    try {
      AgentCredentialService service =
          new AgentCredentialService(
              "a", scheme(url(server), Map.of()), HttpClient.newHttpClient());
      assertNull(service.getCredential("bearer", null));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-683 | 字符串非法 TTL 回退 3600；Number TTL 直接使用
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void ttlParsingBranches() throws Exception {
    HttpServer server = server("{\"access_token\":\"t\"}", 200);
    try {
      AgentCredentialService badTtl =
          new AgentCredentialService(
              "a",
              scheme(url(server), Map.of("token_ttl", "not-a-number")),
              HttpClient.newHttpClient());
      assertEquals("t", badTtl.getCredential("bearer", null));
      AgentCredentialService numericTtl =
          new AgentCredentialService(
              "a", scheme(url(server), Map.of("token_ttl", 60)), HttpClient.newHttpClient());
      assertEquals("t", numericTtl.getCredential("bearer", null));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-684 | 表单编码登录：content_type=x-www-form-urlencoded
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void formEncodedLogin() throws Exception {
    HttpServer server = server("{\"access_token\":\"form-token\"}", 200);
    try {
      AgentCredentialService service =
          new AgentCredentialService(
              "a",
              scheme(url(server), Map.of("content_type", "application/x-www-form-urlencoded")),
              HttpClient.newHttpClient());
      assertEquals("form-token", service.getCredential("bearer", null));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-685 | extractNestedValue：null/空路径、非 Map 中断、嵌套命中
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void extractNestedValueBranches() throws Exception {
    Method m =
        AgentCredentialService.class.getDeclaredMethod(
            "extractNestedValue", Map.class, String.class);
    m.setAccessible(true);
    Map<String, Object> data = Map.of("data", Map.of("token", "deep"));
    assertEquals("deep", m.invoke(null, data, "data.token"));
    assertNull(m.invoke(null, data, null));
    assertNull(m.invoke(null, data, ""));
    assertNull(m.invoke(null, data, "data.token.missing"));
    assertNull(m.invoke(null, Map.of("data", "scalar"), "data.token"));
  }

  // TC-ENG-686 | toLong：Number/合法字符串/非法字符串/其他类型
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void toLongBranches() throws Exception {
    Method m = AgentCredentialService.class.getDeclaredMethod("toLong", Object.class);
    m.setAccessible(true);
    assertEquals(42L, m.invoke(null, 42));
    assertEquals(42L, m.invoke(null, "42"));
    assertEquals(3600L, m.invoke(null, "bad"));
    assertEquals(3600L, m.invoke(null, new Object()));
  }

  // TC-ENG-687 | sanitizeBody：密码字段打码
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sanitizeBodyMasksSensitiveKeys() throws Exception {
    Method m = AgentCredentialService.class.getDeclaredMethod("sanitizeBody", Map.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> sanitized =
        (Map<String, Object>)
            m.invoke(
                null,
                Map.of(
                    "password", "secret",
                    "Password", "secret2",
                    "username", "u"));
    assertEquals("***", sanitized.get("password"));
    assertEquals("***", sanitized.get("Password"));
    assertEquals("u", sanitized.get("username"));
    assertTrue(sanitized.containsValue("***"));
  }
}

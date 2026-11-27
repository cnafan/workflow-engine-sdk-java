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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** TC-ENG-1086…1089：AgentCredentialService 传输登录路径/表单体/预中断/构造器分支。 */
class AgentCredentialServiceTransportPathTest {

  private static HttpServer tokenServer(AtomicReference<String> capturedBody) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              "{\"access_token\":\"transport-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static Map<String, Map<String, Object>> scheme(String url, Map<String, Object> extra) {
    Map<String, Object> cfg =
        new HashMap<>(Map.of("login_url", url, "token_field", "access_token"));
    cfg.putAll(extra);
    Map<String, Map<String, Object>> config = new HashMap<>();
    config.put("bearer", cfg);
    return config;
  }

  // TC-ENG-1086 | login 走 CredentialHttpTransport（非 JDK HttpClient 分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void transportLoginPathReturnsToken() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = tokenServer(body);
    try {
      CredentialHttpTransport transport =
          CredentialHttpTransport.create(true, null, null, null, null, null, Duration.ofSeconds(5));
      AgentCredentialService service =
          new AgentCredentialService(
              "agent-t", scheme(url(server), Map.of()), null, transport, null);
      assertEquals("transport-token", service.getCredential("bearer", null));
      // 二次调用命中缓存（行为不变），不重复登录
      assertEquals("transport-token", service.getCredential("bearer", null));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-1087 | buildLoginBody：无 request_fields → username/password 表单体分支
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void usernamePasswordBodyBuiltWithoutRequestFields() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = tokenServer(body);
    try {
      AgentCredentialService service =
          new AgentCredentialService(
              "agent-u",
              scheme(
                  url(server),
                  Map.of(
                      "username", "u-1",
                      "password", "p-1",
                      "username_field", "login",
                      "password_field", "secret")),
              java.net.http.HttpClient.newHttpClient(),
              null,
              null);
      assertEquals("transport-token", service.getCredential("bearer", null));
      String sent = body.get();
      assertTrue(sent.contains("\"login\":\"u-1\""), sent);
      assertTrue(sent.contains("\"secret\":\"p-1\""), sent);
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-1088 | login 中断：预置中断标记 → InterruptedException 分支返回 null 且恢复标记
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void interruptedLoginReturnsNullAndRestoresFlag() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    HttpServer server = tokenServer(body);
    try {
      AgentCredentialService service =
          new AgentCredentialService(
              "agent-i",
              scheme(url(server), Map.of("username", "u", "password", "p")),
              java.net.http.HttpClient.newHttpClient(),
              null,
              null);
      Thread.currentThread().interrupt();
      try {
        assertNull(service.getCredential("bearer", null));
        assertTrue(Thread.currentThread().isInterrupted(), "flag must be restored");
      } finally {
        Thread.interrupted();
      }
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-1089 | 构造器分支：自动创建 client（两者皆 null）/transport 非 null 时不建 client
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorHttpClientSelectionBranches() {
    AgentCredentialService auto = new AgentCredentialService("agent-a", Map.of(), null, null, null);
    assertNull(auto.getCredential("missing", null));

    CredentialHttpTransport transport =
        CredentialHttpTransport.create(true, null, null, null, null, null, Duration.ofSeconds(5));
    AgentCredentialService withTransport =
        new AgentCredentialService("agent-b", Map.of(), null, transport, null);
    assertNull(withTransport.getCredential("missing", null));
  }

  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
  }
}

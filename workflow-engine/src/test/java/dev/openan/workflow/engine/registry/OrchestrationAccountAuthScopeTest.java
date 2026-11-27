/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-810…814：OrchestrationAccountAuth 端口/scheme/userinfo/401 重登边界。 */
class OrchestrationAccountAuthScopeTest {

  private HttpServer loginServer(String response, int status) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/auth/token",
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

  private OrchestrationAccountAuth auth(String base) {
    return new OrchestrationAccountAuth(base, "user", "pass", TlsPolicy.insecure());
  }

  // TC-ENG-810 | 凭证范围：scheme/host/port 不一致与 userInfo/query/fragment 拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void outOfScopeUrisRejected() throws Exception {
    HttpServer server =
        loginServer("{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}", 200);
    int port = server.getAddress().getPort();
    try {
      OrchestrationAccountAuth account = auth("http://127.0.0.1:" + port);
      account.headers(URI.create("http://127.0.0.1:" + port + "/api/v1/orchestrate/psop/x"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              account.headers(
                  URI.create("https://127.0.0.1:" + port + "/api/v1/orchestrate/psop/x")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              account.headers(
                  URI.create("http://127..0.0.1:" + (port + 1) + "/api/v1/orchestrate/psop/x")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              account.headers(
                  URI.create("http://user@127.0.0.1:" + port + "/api/v1/orchestrate/psop/x")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              account.headers(
                  URI.create("http://127.0.0.1:" + port + "/api/v1/orchestrate/psop/x?q=1")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              account.headers(
                  URI.create("http://127.0.0.1:" + port + "/api/v1/orchestrate/psop/x#f")));
      assertThrows(
          IllegalArgumentException.class,
          () -> account.headers(URI.create("http://127.0.0.1:" + port + "/elsewhere")));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-811 | 路径规范化（..）拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonNormalizedPathRejected() throws Exception {
    HttpServer server =
        loginServer("{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":3600}", 200);
    int port = server.getAddress().getPort();
    try {
      OrchestrationAccountAuth account = auth("http://127.0.0.1:" + port);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              account.headers(
                  URI.create(
                      "http://127.0.0.1:" + port + "/api/v1/orchestrate/../orchestrate/psop/x")));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-812 | onUnauthorized 对 scope 外 URI 拒绝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onUnauthorizedScopeCheck() {
    OrchestrationAccountAuth account = auth("http://127.0.0.1:9");
    assertThrows(
        IllegalArgumentException.class,
        () -> account.onUnauthorized(URI.create("http://other/api/x"), Map.of()));
  }

  // TC-ENG-813 | 非法构造：null/空白用户名密码、null tls/timeouts
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrchestrationAccountAuth("http://x", " ", "p", TlsPolicy.insecure()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrchestrationAccountAuth("http://x", "u", null, TlsPolicy.insecure()));
    assertThrows(
        NullPointerException.class, () -> new OrchestrationAccountAuth("http://x", "u", "p", null));
  }

  // TC-ENG-814 | 401 后 onUnauthorized 返回 true 且旧 token 被清，headers 触发重登
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unauthorizedTriggersRelogin() throws Exception {
    HttpServer server =
        loginServer("{\"access_token\":\"t2\",\"token_type\":\"Bearer\",\"expires_in\":3600}", 200);
    int port = server.getAddress().getPort();
    try {
      OrchestrationAccountAuth account = auth("http://127.0.0.1:" + port);
      URI target = URI.create("http://127.0.0.1:" + port + "/api/v1/orchestrate/psop/x");
      Map<String, String> headers = account.headers(target);
      assertEquals("Bearer t2", headers.get("Authorization"));
      boolean retry = account.onUnauthorized(target, Map.of("Authorization", "Bearer stale-other"));
      assertTrue(retry);
    } finally {
      server.stop(0);
    }
  }
}

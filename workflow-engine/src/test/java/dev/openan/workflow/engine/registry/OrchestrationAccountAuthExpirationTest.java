/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** TC-ENG-1090…1093：OrchestrationAccountAuth 非法 expires_in/TTL 校验/响应超限。 */
class OrchestrationAccountAuthExpirationTest {

  private HttpServer loginServer(String response) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/auth/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private void assertLoginFails(String response, Class<? extends Exception> type) throws Exception {
    HttpServer server = loginServer(response);
    try {
      OrchestrationAccountAuth account =
          new OrchestrationAccountAuth(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "user",
              "pass",
              TlsPolicy.insecure());
      URI target =
          URI.create(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/orchestrate/psop/x");
      Exception error = assertThrows(type, () -> account.headers(target));
      assertTrue(
          String.valueOf(error).contains("Orchestration account")
              || String.valueOf(error).contains("Orchestration center"),
          String.valueOf(error));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-1090 | expires_in=0 → TTL 校验 IAE → 包装为 HttpResponseException
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void zeroExpiresInRejected() throws Exception {
    assertLoginFails(
        "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":0}",
        HttpResponseException.class);
  }

  // TC-ENG-1091 | expires_in 超过 Long.MAX/2/1e9 → 同一守卫拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void overflowExpiresInRejected() throws Exception {
    assertLoginFails(
        "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":10000000000}",
        HttpResponseException.class);
  }

  // TC-ENG-1092 | expires_in 为字符串 → 类型守卫拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void stringExpiresInRejected() throws Exception {
    assertLoginFails(
        "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":\"3600\"}",
        HttpResponseException.class);
  }

  // TC-ENG-1093 | 登录响应超过 64KB 读取上限 → HttpResponseException（CenterHttpClient 限流分支）
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void oversizedLoginResponseRejected() throws Exception {
    StringBuilder padding = new StringBuilder();
    while (padding.length() < 70_000) {
      padding.append("x");
    }
    assertLoginFails(
        "{\"access_token\":\"" + padding + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}",
        HttpResponseException.class);
  }
}

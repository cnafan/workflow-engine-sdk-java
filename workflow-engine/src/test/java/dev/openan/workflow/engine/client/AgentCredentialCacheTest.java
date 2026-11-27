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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-195…197：AgentCredentialService 令牌缓存命中/过期重登/错误状态。 */
class AgentCredentialCacheTest {
  private HttpServer server;
  private String loginUrl;
  private final AtomicInteger loginHits = new AtomicInteger();
  private volatile int loginStatus = 200;
  private volatile String loginBody = "{\"accessSession\":\"cached-token\"}";

  @BeforeEach
  void startServer() throws Exception {
    loginHits.set(0);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    loginUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/login";
    server.createContext(
        "/login",
        exchange -> {
          loginHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = loginBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(loginStatus, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private Map<String, Map<String, Object>> schemeConfig(String ttl) {
    Map<String, Object> cfg = new HashMap<>();
    cfg.put("login_url", loginUrl);
    cfg.put("token_field", "accessSession");
    cfg.put("username", "u");
    cfg.put("password", "p");
    if (ttl != null) {
      cfg.put("token_ttl", ttl);
    }
    return Map.of("bearerAuth", cfg);
  }

  // TC-ENG-195 | 未过期 token 走缓存：两次 getCredential 只登录一次
  //
  // 输入: token_ttl=3600，连续两次取凭据
  // 断言点: 登录命中 1 次，两次返回同一 token
  // 防回归: 缓存命中分支被删即重复登录
  @Test
  void freshTokenIsServedFromCache() {
    AgentCredentialService service =
        new AgentCredentialService("agent-a", schemeConfig("3600"), HttpClient.newHttpClient());
    String first = service.getCredential("bearerAuth", null);
    String second = service.getCredential("bearerAuth", null);
    assertEquals("cached-token", first);
    assertEquals("cached-token", second);
    assertEquals(1, loginHits.get());
  }

  // TC-ENG-196 | token_ttl=0（配合 60 秒提前过期偏置）→ 每次重新登录
  //
  // 输入: token_ttl=0，连续两次取凭据
  // 断言点: 登录命中 2 次
  // 防回归: 过期判断被删即命中缓存
  @Test
  void expiredTokenTriggersRelogin() {
    AgentCredentialService service =
        new AgentCredentialService("agent-a", schemeConfig("0"), HttpClient.newHttpClient());
    service.getCredential("bearerAuth", null);
    service.getCredential("bearerAuth", null);
    assertEquals(2, loginHits.get());
  }

  // TC-ENG-197 | 登录 400 → 返回 null（错误状态码分支）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loginErrorStatusYieldsNullCredential() {
    loginStatus = 400;
    loginBody = "{\"error\":\"bad request\"}";
    AgentCredentialService service =
        new AgentCredentialService("agent-a", schemeConfig("3600"), HttpClient.newHttpClient());
    assertNull(service.getCredential("bearerAuth", null));
    assertTrue(loginHits.get() >= 1);
  }

  // TC-ENG-198 | 登录响应缺 token 字段 → 返回 null（字段缺失分支）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loginWithoutConfiguredTokenFieldYieldsNull() {
    loginBody = "{\"other\":\"value\"}";
    AgentCredentialService service =
        new AgentCredentialService("agent-a", schemeConfig("3600"), HttpClient.newHttpClient());
    assertNull(service.getCredential("bearerAuth", null));
  }

  // TC-ENG-199 | 默认构造分支（无 HTTP 客户端注入时使用工厂创建的客户端）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultHttpClientConstructorDoesNotThrow() {
    loginBody = "{\"accessSession\":\"x\"}";
    AgentCredentialService service = new AgentCredentialService("agent-a", schemeConfig("3600"));
    assertEquals("x", service.getCredential("bearerAuth", null));
  }
}

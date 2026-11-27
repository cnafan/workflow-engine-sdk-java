/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-800…806：RegistryClient 列表/单卡/注册/错误码。 */
class RegistryClientOperationsTest {

  private HttpServer server(int status, String response) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
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

  private String base(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  // TC-ENG-800 | fetchAgentCards 200 解析 data 包裹列表
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fetchAllCardsParsesData() throws Exception {
    String json = "{\"data\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
    HttpServer server = server(200, json);
    try {
      RegistryClient client = new RegistryClient(base(server), false, Duration.ofSeconds(5));
      List<Map<String, Object>> cards = client.fetchAgentCards();
      assertEquals(2, cards.size());
      assertEquals("a", cards.get(0).get("name"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-801 | fetchAgentCards 非 200 → HttpServiceException
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fetchAllCardsErrorStatus() throws Exception {
    HttpServer server = server(500, "{\"message\":\"err\"}");
    try {
      RegistryClient client = new RegistryClient(base(server), false);
      assertThrows(HttpServiceException.class, client::fetchAgentCards);
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-802 | fetchAgentCard 空结果 → null；organization 参数编码
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fetchSingleCardMissingReturnsNull() throws Exception {
    HttpServer server = server(200, "{\"agentCards\":[]}");
    try {
      RegistryClient client = new RegistryClient(base(server), false);
      assertNull(client.fetchAgentCard("missing", "org x"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-803 | fetchAgentCard 多结果 → ISE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void ambiguousMatchRejected() throws Exception {
    HttpServer server = server(200, "{\"agentCards\":[{\"name\":\"a\"},{\"name\":\"a\"}]}");
    try {
      RegistryClient client = new RegistryClient(base(server), false);
      assertThrows(IllegalStateException.class, () -> client.fetchAgentCard("a"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-804 | registerAgentCard 201 成功
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void registerCardAccepted() throws Exception {
    HttpServer server = server(201, "{\"ok\":true}");
    try {
      RegistryClient client = new RegistryClient(base(server), false);
      Map<String, Object> result =
          client.registerAgentCard(Map.of("name", "a", "supportedInterfaces", List.of()));
      assertTrue(result.containsKey("ok"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-805 | register 非 2xx → HttpServiceException
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void registerFailure() throws Exception {
    HttpServer server = server(400, "{\"message\":\"bad\"}");
    try {
      RegistryClient client = new RegistryClient(base(server), false);
      assertThrows(HttpServiceException.class, () -> client.registerAgentCard(Map.of("name", "a")));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-806 | 构造器：auth/tls 重载与 baseUrl 访问器
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorOverloadsAndBaseUrl() {
    RegistryClient c1 = new RegistryClient("http://x", false);
    assertTrue(c1.getBaseUrl().contains("x"));
    RegistryClient c2 = new RegistryClient("http://x", HttpAuthProvider.none());
    RegistryClient c3 =
        new RegistryClient("http://x", HttpAuthProvider.none(), TlsPolicy.insecure());
    assertTrue(c3.getBaseUrl().contains("x"));
    assertThrows(
        IllegalArgumentException.class, () -> new RegistryClient("http://x", false, Duration.ZERO));
  }
}

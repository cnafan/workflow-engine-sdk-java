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

package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CenterAuthenticationTest {
  // TC-ENG | allManagementOperationsUseCurrentBearerWithoutQueryTokens
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void allManagementOperationsUseCurrentBearerWithoutQueryTokens() throws Exception {
    var expected = new AtomicReference<>("first.token");
    var received = new ArrayList<String>();
    var queries = new ArrayList<String>();
    var methods = new ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          received.add(authorization);
          queries.add(exchange.getRequestURI().getRawQuery());
          methods.add(exchange.getRequestMethod());
          exchange.getRequestBody().readAllBytes();
          int status = ("Bearer " + expected.get()).equals(authorization) ? 200 : 401;
          String path = exchange.getRequestURI().getPath();
          String body =
              path.endsWith("/search")
                  ? "{\"data\":[{\"workflow_id\":\"flow\"}]}"
                  : path.contains("/psop/")
                      ? "{\"data\":{\"name\":\"flow\",\"steps\":[]}}"
                      : "{\"agentCards\":[{\"name\":\"Host Agent\"}]}";
          respond(exchange, status, body);
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var provider = HttpAuthProvider.bearer(expected::get);
      var registry = new RegistryClient(url, provider);
      assertEquals("flow", LoadPsop.load(url, "flow", "first.token", true).getName());
      assertEquals(
          "flow", LoadPsop.search(url, "intent", 3, "first.token", true).get(0).getWorkflowId());
      expected.set("second.token");
      assertEquals(
          "flow",
          LoadPsop.search(url, "intent", 3, provider, TlsPolicy.strict()).get(0).getWorkflowId());
      assertEquals("flow", LoadPsop.load(url, "flow", provider, TlsPolicy.strict()).getName());
      assertEquals(1, registry.fetchAgentCards().size());
      assertEquals("Host Agent", registry.fetchAgentCard("Host Agent", "Huawei").get("name"));
      expected.set("third.token");
      assertNotNull(registry.registerAgentCard(Map.of("name", "Host Agent")));
      assertEquals(
          List.of(
              "Bearer first.token",
              "Bearer first.token",
              "Bearer second.token",
              "Bearer second.token",
              "Bearer second.token",
              "Bearer second.token",
              "Bearer third.token"),
          received);
      assertTrue(queries.stream().noneMatch(q -> q != null && q.contains("access_token")));
      assertEquals("name=Host+Agent&organization=Huawei", queries.get(5));
      assertEquals(List.of("GET", "POST", "POST", "GET", "GET", "GET", "POST"), methods);

      var wrong = new RegistryClient(url, HttpAuthProvider.bearer("wrong.token"));
      var denied = assertThrows(HttpServiceException.class, wrong::fetchAgentCards);
      assertEquals(401, denied.getStatusCode());
      assertTrue(denied.isAuthenticationFailure());
      assertThrows(
          HttpServiceException.class, () -> new RegistryClient(url, true).fetchAgentCards());
      assertThrows(HttpServiceException.class, () -> LoadPsop.search(url, "intent"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | customHeadersAreInjectedOnLoadAndRegistryRequests
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void customHeadersAreInjectedOnLoadAndRegistryRequests() throws Exception {
    var received = new ArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(exchange.getRequestHeaders().getFirst("X-Customer-Credential"));
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 200, "{\"data\":{\"name\":\"flow\",\"steps\":[]},\"agentCards\":[]}");
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      HttpAuthProvider auth = uri -> Map.of("X-Customer-Credential", "opaque-secret");
      LoadPsop.load(url, "flow", auth, TlsPolicy.strict());
      new RegistryClient(url, auth).fetchAgentCards();
      assertEquals(List.of("opaque-secret", "opaque-secret"), received);
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | missingOrUnsafeCredentialsFailClosedWithoutPrintingValues
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingOrUnsafeCredentialsFailClosedWithoutPrintingValues() {
    for (String token : List.of("", " ", " secret", "secret\r\nInjected: value")) {
      var error =
          assertThrows(IllegalArgumentException.class, () -> HttpAuthProvider.bearer(token));
      assertFalse(error.getMessage().contains("secret"));
    }
    var uri = java.net.URI.create("http://localhost/request");
    var refreshed = HttpAuthProvider.bearer(() -> null);
    assertThrows(IllegalArgumentException.class, () -> refreshed.headers(uri));
    for (String reserved :
        List.of("Host", "Content-Length", "Content-Type", "Proxy-Authorization")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> HttpAuthentication.headers(u -> Map.of(reserved, "secret"), uri));
    }
    Map<String, String> duplicate = new LinkedHashMap<>();
    duplicate.put("Authorization", "Bearer first");
    duplicate.put("authorization", "Bearer second");
    assertThrows(
        IllegalArgumentException.class, () -> HttpAuthentication.headers(u -> duplicate, uri));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpAuthentication.headers(u -> Map.of("Authorization", "secret\r\nInjected: x"), uri));
    for (String invalid :
        List.of(
            "file:///tmp/config",
            "http://user:secret@localhost",
            "http://localhost?access_token=secret",
            "https://localhost/#fragment")) {
      assertThrows(IllegalArgumentException.class, () -> new RegistryClient(invalid, true));
    }
  }

  // TC-ENG | managementClientsNeverForwardCredentialsThroughRedirects
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void managementClientsNeverForwardCredentialsThroughRedirects() throws Exception {
    var forwarded = new AtomicInteger();
    var destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    destination.createContext(
        "/",
        exchange -> {
          forwarded.incrementAndGet();
          respond(exchange, 200, "{\"agentCards\":[]}");
        });
    destination.start();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange
              .getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + destination.getAddress().getPort() + "/");
          respond(exchange, 302, "");
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var auth = HttpAuthProvider.bearer("private.token");
      assertEquals(
          302,
          assertThrows(
                  HttpServiceException.class,
                  () -> LoadPsop.load(url, "flow", auth, TlsPolicy.strict()))
              .getStatusCode());
      assertEquals(
          302,
          assertThrows(
                  HttpServiceException.class, () -> new RegistryClient(url, auth).fetchAgentCards())
              .getStatusCode());
      assertEquals(0, forwarded.get());
    } finally {
      server.stop(0);
      destination.stop(0);
    }
  }

  // TC-ENG | failureMessagesDoNotExposeEchoedCredentials
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void failureMessagesDoNotExposeEchoedCredentials() {
    Map<String, String> auth = Map.of("Authorization", "Bearer private.token");
    for (int status : List.of(401, 403, 500)) {
      var error =
          new HttpServiceException(
              "Registry", status, "Echoed private.token and Bearer private.token\r\n", auth);
      assertFalse(error.getMessage().contains("private.token"));
      assertFalse(error.getMessage().contains("\r"));
      assertFalse(error.getMessage().contains("\n"));
      assertEquals(status == 401 || status == 403, error.isAuthenticationFailure());
    }
  }

  // TC-ENG | providerAndMalformedUrlFailuresDoNotRetainCredentialBearingCauses
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void providerAndMalformedUrlFailuresDoNotRetainCredentialBearingCauses() {
    String secret = "SyntheticProviderCredential123";
    HttpAuthProvider failing =
        uri -> {
          throw new IllegalArgumentException("IAM rejected " + secret);
        };
    var providerError =
        assertThrows(
            IllegalStateException.class,
            () -> HttpAuthentication.headers(failing, java.net.URI.create("https://localhost/")));
    assertNull(providerError.getCause());
    assertFalse(providerError.getMessage().contains(secret));
    assertTrue(providerError.getMessage().contains("IllegalArgumentException"));
    var urlError =
        assertThrows(
            IllegalArgumentException.class,
            () -> new RegistryClient("https://user:" + secret + " @localhost", true));
    assertNull(urlError.getCause());
    assertFalse(urlError.getMessage().contains(secret));
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (var output = exchange.getResponseBody()) {
      if (bytes.length > 0) output.write(bytes);
    } finally {
      exchange.close();
    }
  }
}

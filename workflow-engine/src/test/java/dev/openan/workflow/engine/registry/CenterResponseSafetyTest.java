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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CenterResponseSafetyTest {
  private static final String SECRET = "SyntheticResponseCredential12345";

  static Stream<Arguments> operations() {
    return Stream.of("load", "search", "list", "select", "register200", "register201")
        .flatMap(
            operation -> Stream.of(true, false).map(bearer -> Arguments.of(operation, bearer)));
  }

  @ParameterizedTest
  @MethodSource("operations")
  void invalidSuccessResponsesNeverExposeCredentialsEvenInExceptionChains(
      String operation, boolean bearer) throws Exception {
    var body = new AtomicReference<>(SECRET);
    var credential = new AtomicReference<String>();
    int status = operation.equals("register201") ? 201 : 200;
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          credential.set(
              exchange
                  .getRequestHeaders()
                  .getFirst(bearer ? "Authorization" : "X-Customer-Credential"));
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      HttpAuthProvider auth =
          bearer ? HttpAuthProvider.bearer(SECRET) : uri -> Map.of("X-Customer-Credential", SECRET);
      for (String invalid : List.of(SECRET, "\"" + SECRET + "\"", "[]", "null", "{} " + SECRET)) {
        body.set(invalid);
        assertSafe(
            assertThrows(HttpResponseException.class, () -> invoke(operation, url, auth)), status);
        assertEquals(bearer ? "Bearer " + SECRET : SECRET, credential.get());
      }
      if (!operation.startsWith("register")) {
        body.set("{\"data\":\"" + SECRET + "\",\"agentCards\":\"" + SECRET + "\"}");
        assertSafe(
            assertThrows(HttpResponseException.class, () -> invoke(operation, url, auth)), status);
      }
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | validBusinessPayloadIsNotRedactedBeforeParsing
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void validBusinessPayloadIsNotRedactedBeforeParsing() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes =
              ("{\"data\":{\"name\":\"flow\",\"description\":\"" + SECRET + "\",\"steps\":[]}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var workflow =
          LoadPsop.load(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "flow",
              HttpAuthProvider.bearer(SECRET),
              TlsPolicy.strict());
      assertEquals(SECRET, workflow.getDescription());
    } finally {
      server.stop(0);
    }
  }

  private static void invoke(String operation, String url, HttpAuthProvider auth) throws Exception {
    switch (operation) {
      case "load" -> LoadPsop.load(url, "flow", auth, TlsPolicy.strict());
      case "search" -> LoadPsop.search(url, "intent", 1, auth, TlsPolicy.strict());
      case "list" -> new RegistryClient(url, auth).fetchAgentCards();
      case "select" -> new RegistryClient(url, auth).fetchAgentCard("Host Agent", "Huawei");
      default -> new RegistryClient(url, auth).registerAgentCard(Map.of("name", "Host Agent"));
    }
  }

  private static void assertSafe(HttpResponseException error, int status) {
    assertEquals(status, error.getStatusCode());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    var stack = new StringWriter();
    error.printStackTrace(new PrintWriter(stack));
    assertFalse(stack.toString().contains(SECRET));
    assertTrue(error.getMessage().contains("invalid response"));
  }
}

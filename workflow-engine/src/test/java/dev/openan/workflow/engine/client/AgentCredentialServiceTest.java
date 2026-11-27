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
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.junit.jupiter.api.Test;

class AgentCredentialServiceTest {

  // TC-ENG | formLoginEncodesKeysAndValuesAsUtf8
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void formLoginEncodesKeysAndValuesAsUtf8() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response = "{\"access_token\":\"token-1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      Map<String, Object> scheme = new HashMap<>();
      scheme.put("login_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      scheme.put("content_type", "application/x-www-form-urlencoded");
      scheme.put("token_field", "access_token");
      scheme.put("request_fields", Map.of("user name", "a+b", "password", "密&码="));
      AgentCredentialService service =
          new AgentCredentialService("agent", Map.of("bearer", scheme), HttpClient.newHttpClient());

      String token =
          service.getCredential("bearer", new ClientCallContext(new HashMap<>(), new HashMap<>()));

      assertEquals("token-1", token);
      assertTrue(requestBody.get().contains("user+name=a%2Bb"));
      assertTrue(requestBody.get().contains("password=%E5%AF%86%26%E7%A0%81%3D"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | concurrentCacheMissesShareOneLogin
  //
  // 输入: 并发/竞争条件
  // 预期输出: 竞争条件下行为正确，无数据竞争
  // 断言点: 结果一致性 + 竞态窗口覆盖
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void concurrentCacheMissesShareOneLogin() throws Exception {
    AtomicInteger logins = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          logins.incrementAndGet();
          try {
            Thread.sleep(100);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          byte[] response = "{\"access_token\":\"shared-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    var executor = Executors.newFixedThreadPool(8);
    try {
      Map<String, Object> scheme = new HashMap<>();
      scheme.put("login_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      scheme.put("token_field", "access_token");
      scheme.put("request_fields", Map.of("username", "user", "password", "password"));
      AgentCredentialService service =
          new AgentCredentialService("agent", Map.of("bearer", scheme), HttpClient.newHttpClient());
      CountDownLatch ready = new CountDownLatch(8);
      CountDownLatch start = new CountDownLatch(1);
      var calls =
          java.util.stream.IntStream.range(0, 8)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            start.await();
                            return service.getCredential(
                                "bearer", new ClientCallContext(new HashMap<>(), new HashMap<>()));
                          }))
              .toList();
      assertTrue(ready.await(2, TimeUnit.SECONDS));
      start.countDown();

      for (var call : calls) assertEquals("shared-token", call.get(3, TimeUnit.SECONDS));
      assertEquals(1, logins.get());
    } finally {
      executor.shutdownNow();
      server.stop(0);
    }
  }

  // TC-ENG | configuredTokenFieldDoesNotSilentlyFallbackToAnotherResponseField
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void configuredTokenFieldDoesNotSilentlyFallbackToAnotherResponseField() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          byte[] response = "{\"token\":\"wrong-field\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      Map<String, Object> scheme = new HashMap<>();
      scheme.put("login_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
      scheme.put("token_field", "accessSession");
      scheme.put("request_fields", Map.of("username", "user", "password", "password"));
      AgentCredentialService service =
          new AgentCredentialService("agent", Map.of("bearer", scheme), HttpClient.newHttpClient());

      assertNull(
          service.getCredential("bearer", new ClientCallContext(new HashMap<>(), new HashMap<>())));
    } finally {
      server.stop(0);
    }
  }
}

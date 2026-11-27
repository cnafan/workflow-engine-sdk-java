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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CredentialHttpTransportTest {
  private HttpServer server;
  private int port;
  private final AtomicReference<String> receivedMethod = new AtomicReference<>();
  private final AtomicReference<String> receivedContentType = new AtomicReference<>();
  private final AtomicReference<String> receivedBody = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String responseBody = "{\"ok\":true}";
  private volatile boolean includeErrorBody = true;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/login",
        exchange -> {
          receivedMethod.set(exchange.getRequestMethod());
          receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          receivedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          if (includeErrorBody) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } else {
            exchange.sendResponseHeaders(status, -1);
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  // TC-ENG-070 | 登录请求按原样送达（方法/Content-Type/UTF-8 body），200 响应回读
  //
  // 输入: POST JSON body 到本地 HttpServer
  // 预期输出: Response(200, body)，服务端收到 POST+Content-Type+原始 body
  // 断言点: 状态码、响应体、服务端收到的三项；防回归: body 编码或头丢失即失败
  @Test
  void sendsLoginBodyAndReturnsSuccessResponse() throws Exception {
    CredentialHttpTransport transport =
        CredentialHttpTransport.create(true, null, null, null, null, null, Duration.ofSeconds(10));
    CredentialHttpTransport.Response response =
        transport.send(
            new URI("http://127.0.0.1:" + port + "/login"),
            "POST",
            "application/json",
            "{\"user\":\"omc\"}");

    assertEquals(200, response.statusCode());
    assertEquals("{\"ok\":true}", response.body());
    assertEquals("POST", receivedMethod.get());
    assertEquals("application/json", receivedContentType.get());
    assertEquals("{\"user\":\"omc\"}", receivedBody.get());
  }

  // TC-ENG-071 | 4xx/5xx 响应回读错误流（getErrorStream）
  //
  // 输入: 服务端返回 400 + 错误 JSON
  // 预期输出: Response(400, 错误内容)
  // 断言点: 状态码与错误流内容；防回归: 错误流分支被删即失败
  @Test
  void errorStatusReadsErrorStream() throws Exception {
    status = 400;
    responseBody = "{\"error\":\"bad_request\"}";
    CredentialHttpTransport transport =
        CredentialHttpTransport.create(true, null, null, null, null, null, Duration.ofSeconds(10));
    CredentialHttpTransport.Response response =
        transport.send(
            new URI("http://127.0.0.1:" + port + "/login"), "POST", "application/json", "{}");

    assertEquals(400, response.statusCode());
    assertEquals("{\"error\":\"bad_request\"}", response.body());
  }

  // TC-ENG-072 | 无错误体的 4xx 响应回读为空串
  //
  // 输入: 服务端 404 无响应体（sendResponseHeaders -1）
  // 预期输出: Response(404, "")
  // 断言点: 空串占位；防回归: null 输入流分支被删即 NPE
  @Test
  void errorStatusWithoutBodyYieldsEmptyString() throws Exception {
    status = 404;
    includeErrorBody = false;
    CredentialHttpTransport transport =
        CredentialHttpTransport.create(true, null, null, null, null, null, Duration.ofSeconds(10));
    CredentialHttpTransport.Response response =
        transport.send(
            new URI("http://127.0.0.1:" + port + "/login"), "GET", "application/json", "");

    assertEquals(404, response.statusCode());
    assertEquals("", response.body());
  }

  // TC-ENG-073 | 关闭校验时 create 仍产出信任所有证书的上下文（TLS 决策仅作用于连接）
  //
  // 输入: create(sslVerify=false, 全空路径)
  // 预期输出: 传输对象可用且请求成功（http 连接下 instanceof 分支为 false）
  // 断言点: 请求成功；防回归: create 装配被破坏即失败
  @Test
  void createWithoutVerificationStillProducesUsableTransport() throws Exception {
    CredentialHttpTransport transport =
        CredentialHttpTransport.create(false, null, null, null, null, null, Duration.ofSeconds(10));
    assertNotNull(transport);
    CredentialHttpTransport.Response response =
        transport.send(
            new URI("http://127.0.0.1:" + port + "/login"), "POST", "application/json", "{}");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("ok"));
  }
}

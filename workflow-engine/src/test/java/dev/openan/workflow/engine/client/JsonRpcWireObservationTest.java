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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.*;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;

class JsonRpcWireObservationTest {
  // TC-ENG | recordsSdkSerializedJsonRpcRequestAndRealErrorResponse
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void recordsSdkSerializedJsonRpcRequestAndRealErrorResponse() throws Exception {
    var json = new ObjectMapper().registerModule(new AgentCardJacksonModule());
    List<WireLog.Entry> logs = new CopyOnWriteArrayList<>();
    AtomicReference<String> received = new AtomicReference<>();
    AtomicReference<List<String>> version = new AtomicReference<>();
    AtomicReference<String> returned = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rpc",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.set(body);
          version.set(exchange.getRequestHeaders().get("A2A-Version"));
          String error =
              "{\"jsonrpc\":\"2.0\",\"id\":"
                  + json.readTree(body).get("id")
                  + ",\"error\":{\"code\":-32602,\"message\":\"invalid input\"}}";
          returned.set(error);
          byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    Client client = null;
    try {
      AgentCard card =
          json.readValue(
              """
                {"name":"rpc","description":"rpc","version":"1",
                 "capabilities":{"streaming":false,"extensions":[]},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
                 "supportedInterfaces":[{"protocolBinding":"JSONRPC","protocolVersion":"1.0","tenant":"",
                 "url":"http://127.0.0.1:%d/rpc"}]}
                """
                  .formatted(server.getAddress().getPort()),
              AgentCard.class);
      client =
          Client.builder(card)
              .withTransport(
                  JSONRPCTransport.class,
                  new JSONRPCTransportConfig(
                      new JdkA2AHttpClient(
                          new ObservedHttpClient(HttpClient.newHttpClient(), logs::add))))
              .build();
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      try {
        client.sendMessage(
            MessageSendParams.builder()
                .message(
                    Message.builder()
                        .messageId(UUID.randomUUID().toString())
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("诊断输入"))
                        .build())
                .build(),
            List.of((event, agent) -> done.countDown()),
            error -> {
              failure.set(error);
              done.countDown();
            },
            new ClientCallContext(Map.of(), Map.of("Authorization", "Bearer hidden")));
      } catch (A2AClientException error) {
        failure.set(error);
        done.countDown();
      }
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertNotNull(failure.get());
      var request = json.readTree(received.get());
      assertEquals("2.0", request.get("jsonrpc").asText());
      assertEquals(A2AMethods.SEND_MESSAGE_METHOD, request.get("method").asText());
      assertTrue(received.get().contains("诊断输入"));
      var body =
          logs.stream().filter(e -> e.direction().equals("REQUEST_BODY")).findFirst().orElseThrow();
      assertEquals(received.get(), body.body());
      var headers =
          logs.stream()
              .filter(e -> e.direction().equals("REQUEST_HEADERS"))
              .findFirst()
              .orElseThrow();
      assertEquals(version.get(), headers.headers().get("A2A-Version"));
      assertNotNull(version.get());
      assertTrue(
          logs.stream()
              .anyMatch(
                  e -> e.direction().equals("RESPONSE_BODY") && returned.get().equals(e.body())));
      assertFalse(logs.toString().contains("hidden"));
    } finally {
      if (client != null) client.close();
      server.stop(0);
    }
  }
}

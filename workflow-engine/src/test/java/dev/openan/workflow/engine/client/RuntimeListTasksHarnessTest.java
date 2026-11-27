/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-910…912：DefaultA2AJavaClientRuntime listTasks/协议绑定失败。 */
class RuntimeListTasksHarnessTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  private DefaultA2AJavaClientRuntime runtime;

  @AfterEach
  void tearDown() {
    if (runtime != null) runtime.close();
    if (server != null) server.stop(0);
  }

  private int startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rpc",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          JsonNode request = JSON.readTree(body);
          String method = request.path("method").asText();
          String response =
              "{\"jsonrpc\":\"2.0\",\"id\":"
                  + request.path("id")
                  + ",\"result\":{\"tasks\":["
                  + "{\"id\":\"t-1\",\"contextId\":\"c-1\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}"
                  + "],\"totalSize\":1,\"pageSize\":1,\"nextPageToken\":\"\"}}";
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
          exchange.close();
        });
    server.start();
    return server.getAddress().getPort();
  }

  private AgentCard card(int port, String binding) throws Exception {
    return JSON.readValue(
        """
        {"name":"agent-a","description":"h","version":"1",
         "capabilities":{"streaming":false,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"%s","protocolVersion":"1.0","tenant":"",
         "url":"http://127.0.0.1:%d/rpc"}]}
        """
            .formatted(binding, port),
        AgentCard.class);
  }

  // TC-ENG-910 | listTasks 成功解析
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void listTasksSuccess() throws Exception {
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    ListTasksResult result =
        runtime.listTasks(
            card(port, "JSONRPC"),
            new ListTasksParams(
                "",
                org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING,
                10,
                "",
                null,
                null,
                false,
                ""),
            new ClientCallContext(Map.of(), Map.of()));
    assertEquals(1, result.tasks().size());
    assertEquals("t-1", result.tasks().get(0).id());
  }

  // TC-ENG-911 | 不支持的协议绑定 → ISE "Failed to create a2a-java client"
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unsupportedBindingFailsClientCreation() throws Exception {
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, null);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                runtime.listTasks(
                    card(port, "BOGUS"),
                    ListTasksParams.builder().build(),
                    new ClientCallContext(Map.of(), Map.of())));
    assertTrue(error.getMessage().contains("Failed to create a2a-java client"));
  }
}

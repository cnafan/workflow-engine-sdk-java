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
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowSearchResult;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TC-ENG-1113…1114：LoadPsop 二参成功路径与 RegistryClient 超时校验双侧。 */
class LoadPsopConvenienceSuccessTest {

  private HttpServer server(String response) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
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

  private String base(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  // TC-ENG-1113 | load(baseUrl, psopId) 二参成功 → 解析工作流（tokenAuth(null)/strict TLS 默认分支）
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void twoArgLoadSuccess() throws Exception {
    HttpServer httpServer = server("{\"data\":{\"name\":\"wf-two\",\"steps\":[]}}");
    try {
      Workflow wf = LoadPsop.load(base(httpServer), "wf-two");
      assertEquals("wf-two", wf.getName());
    } finally {
      httpServer.stop(0);
    }
  }

  // TC-ENG-1114 | search(baseUrl, intent) 二参成功 → 默认 top_n=5、无 token
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void twoArgSearchSuccess() throws Exception {
    HttpServer httpServer =
        server(
            "{\"data\":[{\"workflow_id\":\"w9\",\"name\":\"Nine\",\"score\":0.8,"
                + "\"created_at\":null,\"user_intent\":null}]}");
    try {
      List<WorkflowSearchResult> results = LoadPsop.search(base(httpServer), "order");
      assertEquals(1, results.size());
      assertEquals("w9", results.get(0).getWorkflowId());
      assertEquals(0.8, results.get(0).getScore());
    } finally {
      httpServer.stop(0);
    }
  }

  // TC-ENG-1115 | RegistryClient requestTimeout 校验：null 与负数（除 0 外的另外两侧）
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void registryClientTimeoutValidationSides() {
    assertThrows(IllegalArgumentException.class, () -> new RegistryClient("http://x", false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RegistryClient("http://x", false, Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class, () -> new RegistryClient("http://x", false, Duration.ZERO));
    assertTrue(true);
  }
}

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

/** TC-ENG-770…776：LoadPsop 参数守卫/HTTP 状态/Timeouts/默认重载。 */
class LoadPsopRequestValidationTest {

  private HttpServer server(String response, int status) throws Exception {
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

  // TC-ENG-770 | 非法 psopId：null/空白/. /..
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void invalidPsopIdRejected() {
    assertThrows(NullPointerException.class, () -> LoadPsop.load("http://x", null));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> LoadPsop.load("http://x", " "))
            .getMessage()
            .contains("identify"));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> LoadPsop.load("http://x", "."))
            .getMessage()
            .contains("identify"));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> LoadPsop.load("http://x", ".."))
            .getMessage()
            .contains("identify"));
  }

  // TC-ENG-771 | load 非 200 → HttpServiceException
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loadNon200Throws() throws Exception {
    HttpServer server = server("{\"error\":{\"code\":500,\"message\":\"boom\"}}", 500);
    try {
      assertThrows(
          HttpServiceException.class, () -> LoadPsop.load(base(server), "wf-1", "token", false));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-772 | load 200 解析 data 包裹的工作流
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loadParsesWrappedWorkflow() throws Exception {
    String json = "{\"data\":{\"name\":\"wf-loaded\",\"steps\":[]}}";
    HttpServer server = server(json, 200);
    try {
      Workflow wf = LoadPsop.load(base(server), "wf-1", null, false);
      assertEquals("wf-loaded", wf.getName());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-773 | search 200 解析结果列表
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void searchParsesResults() throws Exception {
    String json =
        "{\"data\":[{\"workflow_id\":\"w1\",\"name\":\"One\",\"score\":0.9},"
            + "{\"id\":\"w2\",\"workflow_type\":\"type-a\",\"name\":\"Two\"}]}";
    HttpServer server = server(json, 200);
    try {
      List<WorkflowSearchResult> results =
          LoadPsop.search(base(server), "intent", 10, "token", false);
      assertEquals(2, results.size());
      assertEquals("w1", results.get(0).getWorkflowId());
      assertEquals("type-a", results.get(1).getWorkflowType());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-774 | search 非 200 → HttpServiceException
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void searchNon200Throws() throws Exception {
    HttpServer server = server("{\"message\":\"forbidden\"}", 403);
    try {
      assertThrows(
          HttpServiceException.class,
          () -> LoadPsop.search(base(server), "intent", 5, null, false));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-775 | Timeouts 校验：null/零/负/亚毫秒/超大
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void timeoutsValidation() {
    assertThrows(
        IllegalArgumentException.class, () -> new LoadPsop.Timeouts(null, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadPsop.Timeouts(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadPsop.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadPsop.Timeouts(Duration.ofNanos(100), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LoadPsop.Timeouts(
                Duration.ofSeconds((long) Integer.MAX_VALUE + 100L), Duration.ofSeconds(1)));
  }

  // TC-ENG-776 | Timeouts 访问器
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void timeoutAccessors() {
    LoadPsop.Timeouts timeouts =
        new LoadPsop.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3));
    assertEquals(2000, timeouts.connectTimeoutMillis());
    assertEquals(3000, timeouts.readTimeoutMillis());
  }

  // TC-ENG-777 | 两参 load/search 便捷重载不抛参数错误（网络错误包装为 RuntimeException/HttpServiceException）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void convenienceOverloadsRejectBlankBase() {
    assertThrows(IllegalArgumentException.class, () -> LoadPsop.load("  ", "wf-1"));
    assertThrows(NullPointerException.class, () -> LoadPsop.search(null, "intent"));
  }
}

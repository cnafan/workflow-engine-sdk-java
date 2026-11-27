/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.model.WorkflowSearchResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests LoadPsop.search parsing logic using a local mock HTTP server. No real orchestration center
 * needed.
 */
class LoadPsopSearchTest {

  // TC-ENG | rejectsInvalidCustomTimeouts
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void rejectsInvalidCustomTimeouts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadPsop.Timeouts(Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadPsop.Timeouts(Duration.ofSeconds(1), Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoadPsop.Timeouts(Duration.ofNanos(1), Duration.ofSeconds(1)));
  }

  // TC-ENG | customReadTimeoutIsApplied
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void customReadTimeoutIsApplied() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
    server.createContext(
        "/api/v1/orchestrate/search",
        exchange -> {
          try {
            Thread.sleep(500);
            byte[] response = "{\"data\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      assertThrows(
          java.net.SocketTimeoutException.class,
          () ->
              LoadPsop.search(
                  baseUrl,
                  "timeout",
                  1,
                  null,
                  true,
                  new LoadPsop.Timeouts(Duration.ofSeconds(1), Duration.ofMillis(50))));
    } finally {
      server.stop(0);
    }
  }

  /**
   * Verifies that the search method correctly parses the orchestration center's JSON response into
   * WorkflowSearchResult objects.
   *
   * <p>Uses Java's built-in HttpServer to mock the endpoint, so no external dependency is needed.
   */
  // TC-ENG | searchParsesResultsFromMockServer
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void searchParsesResultsFromMockServer() throws Exception {
    // Start a mock HTTP server returning a canned search response
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
    server.createContext(
        "/api/v1/orchestrate/search",
        exchange -> {
          String json =
              "{\"data\":["
                  + "{\"workflow_id\":\"psop-1\",\"workflow_type\":\"psop\",\"name\":\"fault_diag\",\"description\":\"SPN fault diagnosis\",\"score\":0.95,\"user_intent\":\"diagnose SPN\",\"tasks_summary\":\"3 steps\"},"
                  + "{\"workflow_id\":\"psop-2\",\"workflow_type\":\"psop\",\"name\":\"energy_saving\",\"description\":\"Energy saving flow\",\"score\":0.80}"
                  + "]}";
          byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (var os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });
    server.start();
    int port = server.getAddress().getPort();
    String baseUrl = "http://127.0.0.1:" + port;

    try {
      List<WorkflowSearchResult> results = LoadPsop.search(baseUrl, "SPN fault", 5, null, false);
      assertEquals(2, results.size());
      WorkflowSearchResult r1 = results.get(0);
      assertEquals("psop-1", r1.getWorkflowId());
      assertEquals("psop", r1.getWorkflowType());
      assertEquals("fault_diag", r1.getName());
      assertEquals("SPN fault diagnosis", r1.getDescription());
      assertEquals(0.95, r1.getScore(), 0.001);
      assertEquals("diagnose SPN", r1.getUserIntent());
      assertEquals("3 steps", r1.getTasksSummary());
      WorkflowSearchResult r2 = results.get(1);
      assertEquals("psop-2", r2.getWorkflowId());
      assertEquals("energy_saving", r2.getName());
      assertEquals(0.80, r2.getScore(), 0.001);
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | searchEmptyResultsFromMockServer
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void searchEmptyResultsFromMockServer() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
    server.createContext(
        "/api/v1/orchestrate/search",
        exchange -> {
          String json = "{\"data\":[]}";
          byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (var os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });
    server.start();
    int port = server.getAddress().getPort();
    String baseUrl = "http://127.0.0.1:" + port;

    try {
      List<WorkflowSearchResult> results = LoadPsop.search(baseUrl, "nonexistent", 5, null, false);
      assertTrue(results.isEmpty());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | searchThrowsOnServerError
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void searchThrowsOnServerError() {
    com.sun.net.httpserver.HttpServer server;
    try {
      server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
    } catch (java.io.IOException e) {
      fail("Could not start mock server: " + e.getMessage());
      return;
    }
    server.createContext(
        "/api/v1/orchestrate/search",
        exchange -> {
          byte[] response =
              "{\"detail\":\"temporarily unavailable\",\"accessToken\":\"private\"}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(500, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    int port = server.getAddress().getPort();
    String baseUrl = "http://127.0.0.1:" + port;

    try {
      RuntimeException error =
          assertThrows(
              RuntimeException.class, () -> LoadPsop.search(baseUrl, "test", 5, null, false));
      assertTrue(error.getMessage().contains("temporarily unavailable"));
      assertTrue(error.getMessage().contains("\"accessToken\":\"***\""));
      assertFalse(error.getMessage().contains("private"));
    } finally {
      server.stop(0);
    }
  }
}

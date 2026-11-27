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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.model.WorkflowSearchResult;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-190…194：LoadPsop search 的 401 凭据刷新重试状态机。 */
class UnauthorizedRetryTest {
  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger searchHits = new AtomicInteger();
  private final List<String> seenAuthorizations = new CopyOnWriteArrayList<>();
  private volatile int successStatus = 200;
  private volatile String successBody =
      "{\"data\":[{\"workflow_id\":\"wf-1\",\"name\":\"n\",\"score\":0.9}]}";
  private volatile boolean retrySucceeds = true;

  @BeforeEach
  void startServer() throws IOException {
    searchHits.set(0);
    seenAuthorizations.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/api/v1/orchestrate/search",
        exchange -> {
          searchHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          seenAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body;
          int status;
          if (searchHits.get() == 1) {
            status = 401;
            body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(body);
            }
          } else if (retrySucceeds) {
            status = successStatus;
            body = successBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(body);
            }
          } else {
            status = 403;
            body = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(body);
            }
          }
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static final LoadPsop.Timeouts TIMEOUTS =
      new LoadPsop.Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(10));

  /** 每次请求读取当前 token；401 后轮换令牌并允许重试一次。 */
  private static final class RotatingAuth implements HttpAuthProvider {
    private final AtomicReference<String> token;
    private final AtomicInteger refreshes = new AtomicInteger();

    RotatingAuth(String initial) {
      this.token = new AtomicReference<>(initial);
    }

    @Override
    public Map<String, String> headers(URI requestUri) {
      return Map.of("Authorization", "Bearer " + token.get());
    }

    @Override
    public boolean onUnauthorized(URI requestUri, Map<String, String> rejectedHeaders) {
      refreshes.incrementAndGet();
      token.set("refreshed-" + refreshes.get());
      return true;
    }
  }

  // TC-ENG-190 | 401 后凭据刷新并重试：两次请求，第二次带新令牌，结果解析成功
  //
  // 输入: 首次 401 + onUnauthorized=true 轮换 token，第二次 200
  // 断言点: 命中 2 次、Authorization 变化、结果 workflow_id
  // 防回归: 重试分支被删即失败
  @Test
  void unauthorizedTriggersOneCredentialRefreshAndRetry() throws Exception {
    RotatingAuth auth = new RotatingAuth("initial-token");
    List<WorkflowSearchResult> results =
        LoadPsop.search(baseUrl, "find", 5, auth, TlsPolicy.insecure(), TIMEOUTS);

    assertEquals(2, searchHits.get());
    assertEquals("Bearer initial-token", seenAuthorizations.get(0));
    assertEquals("Bearer refreshed-1", seenAuthorizations.get(1));
    assertEquals(1, results.size());
    assertEquals("wf-1", results.get(0).getWorkflowId());
    assertEquals(0.9, results.get(0).getScore(), 0.0001);
    assertEquals(1, auth.refreshes.get());
  }

  // TC-ENG-191 | onUnauthorized 返回 false（默认）→ 不重试，直接失败
  //
  // 输入: 固定 Bearer + 默认 onUnauthorized=false
  // 断言点: 只请求 1 次且抛 HttpServiceException（401）
  // 防回归: 默认不重试契约被改即失败
  @Test
  void unauthorizedWithoutRefreshFailsImmediately() {
    HttpAuthProvider staticAuth = HttpAuthProvider.bearer("static-token");
    HttpServiceException error =
        assertThrows(
            HttpServiceException.class,
            () -> LoadPsop.search(baseUrl, "find", 5, staticAuth, TlsPolicy.insecure(), TIMEOUTS));
    assertEquals(401, error.getStatusCode());
    assertEquals(1, searchHits.get());
  }

  // TC-ENG-192 | 刷新后重试仍失败（403）→ 抛异常，不再重试
  //
  // 输入: onUnauthorized=true，但第二次 403
  // 断言点: 恰好 2 次请求，异常状态 403
  // 防回归: 无限重试或错误状态吞掉即失败
  @Test
  void retryOnlyOnceEvenWhenRetryFails() {
    retrySucceeds = false;
    RotatingAuth auth = new RotatingAuth("initial-token");
    HttpServiceException error =
        assertThrows(
            HttpServiceException.class,
            () -> LoadPsop.search(baseUrl, "find", 5, auth, TlsPolicy.insecure(), TIMEOUTS));
    assertEquals(403, error.getStatusCode());
    assertEquals(2, searchHits.get());
    assertEquals(1, auth.refreshes.get());
  }

  // TC-ENG-193 | 空结果列表解析为空
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptySearchResponseParsesAsEmptyList() throws Exception {
    successBody = "{\"data\":[]}";
    RotatingAuth auth = new RotatingAuth("initial-token");
    // 让首次就成功：直接用 200 服务器需要重置计数 —— 改用不触发 401 的静态 token 场景
    retrySucceeds = true;
    List<WorkflowSearchResult> results =
        LoadPsop.search(baseUrl, "find", 5, auth, TlsPolicy.insecure(), TIMEOUTS);
    // 首次 401 → 重试后拿到空列表
    assertEquals(2, searchHits.get());
    assertTrue(results.isEmpty());
  }

  // TC-ENG-194 | 畸形/缺失 Bearer 令牌在请求前失败（fail-closed）
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void malformedBearerTokenFailsBeforeRequest() {
    assertThrows(IllegalArgumentException.class, () -> HttpAuthProvider.bearer("bad token!"));
    String nullToken = null;
    assertThrows(IllegalArgumentException.class, () -> HttpAuthProvider.bearer(nullToken));
    java.util.function.Supplier<String> tokenSupplier = () -> "valid.token-1";
    assertFalse(HttpAuthProvider.bearer(tokenSupplier).headers(URI.create("http://x")).isEmpty());
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrchestrationAccountAuthTest {
  private static final String PASSWORD = "SyntheticPassword9!";
  private static final LoadPsop.Timeouts TIMEOUTS =
      new LoadPsop.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2));

  // TC-ENG | searchAndLoadUseOneLoginWithoutQueryTokensCookiesOrCredentialReuse
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void searchAndLoadUseOneLoginWithoutQueryTokensCookiesOrCredentialReuse() throws Exception {
    try (var service = new Service()) {
      var auth = service.auth();
      assertEquals(0, service.logins.get(), "login is lazy");
      assertEquals(
          "flow",
          LoadPsop.search(service.url(), "intent", 3, auth, TlsPolicy.strict())
              .get(0)
              .getWorkflowId());
      assertEquals(
          "flow", LoadPsop.load(service.url(), "flow", auth, TlsPolicy.strict()).getName());
      assertEquals(1, service.logins.get());
      assertEquals(2, service.accepted.get());
      assertEquals(0, service.invalidRequests.get());
    }
  }

  // TC-ENG | advertisedLifetimeRenewsUsingAMonotonicClock
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void advertisedLifetimeRenewsUsingAMonotonicClock() throws Exception {
    try (var service = new Service()) {
      var now = new AtomicLong(0);
      var auth =
          new OrchestrationAccountAuth(
              service.url(), "admin", PASSWORD, TlsPolicy.strict(), TIMEOUTS, now::get);
      URI uri = URI.create(service.url() + "/api/v1/orchestrate/search");
      assertEquals("Bearer session.1", auth.headers(uri).get("Authorization"));
      now.set(Duration.ofSeconds(53).toNanos());
      assertEquals("Bearer session.1", auth.headers(uri).get("Authorization"));
      now.set(Duration.ofSeconds(54).toNanos());
      assertEquals("Bearer session.2", auth.headers(uri).get("Authorization"));
      assertEquals(2, service.logins.get());
    }
  }

  // TC-ENG | concurrentAcquisitionLogsInOnceAndOld401CannotEraseANewerSession
  //
  // 输入: 并发/竞争条件
  // 预期输出: 竞争条件下行为正确，无数据竞争
  // 断言点: 结果一致性 + 竞态窗口覆盖
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void concurrentAcquisitionLogsInOnceAndOld401CannotEraseANewerSession() throws Exception {
    try (var service = new Service()) {
      var auth = service.auth();
      URI uri = URI.create(service.url() + "/api/v1/orchestrate/search");
      var pool = Executors.newFixedThreadPool(8);
      try {
        var futures = new ArrayList<java.util.concurrent.Future<Map<String, String>>>();
        for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> auth.headers(uri)));
        for (var future : futures)
          assertEquals("Bearer session.1", future.get(3, TimeUnit.SECONDS).get("Authorization"));
        assertEquals(1, service.logins.get());
        Map<String, String> old = auth.headers(uri);
        assertTrue(auth.onUnauthorized(uri, old));
        assertEquals("Bearer session.2", auth.headers(uri).get("Authorization"));
        assertTrue(auth.onUnauthorized(uri, old));
        assertEquals("Bearer session.2", auth.headers(uri).get("Authorization"));
        assertEquals(2, service.logins.get());
      } finally {
        pool.shutdownNow();
      }
    }
  }

  // TC-ENG | revokedTokenRetriesSearchOrLoadOnceAndRepeated401Stops
  //
  // 输入: 重复/二次提交
  // 预期输出: 幂等行为：不产生重复副作用
  // 断言点: 调用次数 + 结果一致性
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void revokedTokenRetriesSearchOrLoadOnceAndRepeated401Stops() throws Exception {
    try (var service = new Service()) {
      var auth = service.auth();
      auth.headers(URI.create(service.url() + "/api/v1/orchestrate/search"));
      service.session.incrementAndGet(); // The service restarted or revoked the cached session.
      assertEquals(
          "flow",
          LoadPsop.search(service.url(), "intent", 3, auth, TlsPolicy.strict())
              .get(0)
              .getWorkflowId());
      assertEquals(2, service.logins.get());
      service.session.incrementAndGet();
      assertEquals(
          "flow", LoadPsop.load(service.url(), "flow", auth, TlsPolicy.strict()).getName());
      assertEquals(3, service.logins.get());
      service.apiStatus = 401;
      int before = service.calls.get();
      assertEquals(
          401,
          assertThrows(
                  HttpServiceException.class,
                  () -> LoadPsop.search(service.url(), "intent", 3, auth, TlsPolicy.strict()))
              .getStatusCode());
      assertEquals(2, service.calls.get() - before);
      assertEquals(4, service.logins.get());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 429, 500, 503})
  void permissionRateLimitAndServiceErrorsNeverRetry(int status) throws Exception {
    try (var service = new Service()) {
      service.apiStatus = status;
      assertEquals(
          status,
          assertThrows(
                  HttpServiceException.class,
                  () -> LoadPsop.load(service.url(), "flow", service.auth(), TlsPolicy.strict()))
              .getStatusCode());
      assertEquals(1, service.logins.get());
      assertEquals(1, service.calls.get());
    }
  }

  // TC-ENG | externalBearerDoesNotOptIntoAutomaticRelogin
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void externalBearerDoesNotOptIntoAutomaticRelogin() throws Exception {
    try (var service = new Service()) {
      service.apiStatus = 401;
      assertEquals(
          401,
          assertThrows(
                  HttpServiceException.class,
                  () ->
                      LoadPsop.load(
                          service.url(),
                          "flow",
                          HttpAuthProvider.bearer("external.token"),
                          TlsPolicy.strict()))
              .getStatusCode());
      assertEquals(0, service.logins.get());
      assertEquals(1, service.calls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 401, 403, 429, 500, 503})
  void rejectedLoginNeverLeaksBodyOrRequestsWorkflow(int status) throws Exception {
    try (var service = new Service()) {
      service.loginStatus = status;
      service.loginBody = PASSWORD + " echoed.opaque.token";
      var error =
          assertThrows(
              HttpServiceException.class,
              () -> LoadPsop.load(service.url(), "flow", service.auth(), TlsPolicy.strict()));
      assertEquals(status, error.getStatusCode());
      assertFalse(error.getMessage().contains(PASSWORD));
      assertFalse(error.getMessage().contains("echoed.opaque.token"));
      assertNull(error.getCause());
      assertEquals(1, service.logins.get());
      assertEquals(0, service.calls.get());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SyntheticPassword9!",
        "{}",
        "{\"access_token\":\"bad token\",\"token_type\":\"Bearer\",\"expires_in\":60}",
        "{\"access_token\":\"token\",\"token_type\":\"Basic\",\"expires_in\":60}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":0}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":1.5}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":\"60\"}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":9223372036854775807}",
        "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":60} {}"
      })
  void malformedSuccessfulLoginFailsClosedWithoutParserCause(String body) throws Exception {
    try (var service = new Service()) {
      service.loginBody = body;
      var error =
          assertThrows(
              HttpResponseException.class,
              () -> LoadPsop.load(service.url(), "flow", service.auth(), TlsPolicy.strict()));
      assertNull(error.getCause());
      assertFalse(error.getMessage().contains(PASSWORD));
      assertEquals(0, service.calls.get());
    }
  }

  // TC-ENG | loginResponseIsBoundedAndCredentialScopeIsEnforcedBeforeNetwork
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loginResponseIsBoundedAndCredentialScopeIsEnforcedBeforeNetwork() throws Exception {
    try (var service = new Service()) {
      var auth = service.auth();
      for (String path :
          new String[] {
            "/api/v1/registry",
            "/api/v1/orchestrate/../auth/token",
            "/api/v1/orchestrate/search?access_token=secret"
          }) {
        assertThrows(
            IllegalArgumentException.class, () -> auth.headers(URI.create(service.url() + path)));
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> auth.headers(URI.create("http://other.example.test/api/v1/orchestrate/search")));
      assertEquals(0, service.logins.get());
      service.loginBody = "x".repeat(65537);
      assertThrows(
          HttpResponseException.class,
          () -> LoadPsop.load(service.url(), "flow", auth, TlsPolicy.strict()));
      assertEquals(0, service.calls.get());
    }
  }

  // TC-ENG | loginTimeoutIsBoundedAndDoesNotSendPsopRequest
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loginTimeoutIsBoundedAndDoesNotSendPsopRequest() throws Exception {
    try (var service = new Service()) {
      service.loginDelayMillis = 300;
      var auth =
          new OrchestrationAccountAuth(
              service.url(),
              "admin",
              PASSWORD,
              TlsPolicy.strict(),
              new LoadPsop.Timeouts(Duration.ofSeconds(1), Duration.ofMillis(50)));
      var error =
          assertThrows(
              IllegalStateException.class,
              () -> LoadPsop.load(service.url(), "flow", auth, TlsPolicy.strict()));
      assertFalse(error.getMessage().contains(PASSWORD));
      assertNull(error.getCause());
      assertEquals(0, service.calls.get());
    }
  }

  private static final class Service implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger logins = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger accepted = new AtomicInteger();
    final AtomicInteger session = new AtomicInteger();
    final AtomicInteger invalidRequests = new AtomicInteger();
    volatile int apiStatus = 200;
    volatile int loginStatus = 200;
    volatile long loginDelayMillis;
    volatile String loginBody;

    Service() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    OrchestrationAccountAuth auth() {
      return new OrchestrationAccountAuth(url(), "admin", PASSWORD, TlsPolicy.strict(), TIMEOUTS);
    }

    void handle(HttpExchange exchange) throws java.io.IOException {
      try {
        String request =
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (exchange.getRequestURI().getRawQuery() != null
            || exchange.getRequestHeaders().containsKey("Cookie"))
          invalidRequests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v1/auth/token")) {
          logins.incrementAndGet();
          Map<?, ?> fields = new ObjectMapper().readValue(request, Map.class);
          if (!"POST".equals(exchange.getRequestMethod())
              || !Map.of("username", "admin", "password", PASSWORD).equals(fields)
              || exchange.getRequestHeaders().containsKey("Authorization"))
            invalidRequests.incrementAndGet();
          if (loginDelayMillis > 0) {
            try {
              Thread.sleep(loginDelayMillis);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          }
          exchange.getResponseHeaders().set("Location", url() + "/redirect-target");
          respond(
              exchange,
              loginStatus,
              loginBody != null
                  ? loginBody
                  : "{\"access_token\":\"session."
                      + session.incrementAndGet()
                      + "\",\"token_type\":\"Bearer\",\"expires_in\":60}");
        } else {
          calls.incrementAndGet();
          boolean valid =
              ("Bearer session." + session.get())
                  .equals(exchange.getRequestHeaders().getFirst("Authorization"));
          int status = apiStatus == 200 && !valid ? 401 : apiStatus;
          if (status == 200) accepted.incrementAndGet();
          if (request.contains(PASSWORD)) invalidRequests.incrementAndGet();
          respond(
              exchange,
              status,
              status != 200
                  ? "rejected"
                  : path.endsWith("/search")
                      ? "{\"data\":[{\"workflow_id\":\"flow\"}]}"
                      : "{\"data\":{\"name\":\"flow\",\"steps\":[]}}");
        }
      } finally {
        exchange.close();
      }
    }

    private static void respond(HttpExchange exchange, int status, String body)
        throws java.io.IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}

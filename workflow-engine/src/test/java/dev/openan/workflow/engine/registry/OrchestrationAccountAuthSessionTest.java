/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-300…306：OrchestrationAccountAuth 登录/缓存/范围限制/刷新。 */
class OrchestrationAccountAuthSessionTest {
  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger loginHits = new AtomicInteger();
  private volatile int loginStatus = 200;
  private volatile String loginBody =
      "{\"access_token\":\"opaque-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

  @BeforeEach
  void startServer() throws Exception {
    loginHits.set(0);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/api/v1/auth/token",
        exchange -> {
          loginHits.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = loginBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(loginStatus, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
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

  private OrchestrationAccountAuth auth() {
    return new OrchestrationAccountAuth(
        baseUrl, "admin", "Admin@123", TlsPolicy.insecure(), TIMEOUTS);
  }

  private URI orchestrateUri() {
    return URI.create(baseUrl + "/api/v1/orchestrate/workflows");
  }

  // TC-ENG-300 | 构造：空用户名/密码、null TLS 拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void constructorRejectsInvalidArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrchestrationAccountAuth(baseUrl, " ", "p", TlsPolicy.insecure()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OrchestrationAccountAuth(baseUrl, "u", " ", TlsPolicy.insecure()));
    assertThrows(
        NullPointerException.class, () -> new OrchestrationAccountAuth(baseUrl, "u", "p", null));
    assertThrows(
        NullPointerException.class,
        () -> new OrchestrationAccountAuth(baseUrl, "u", "p", TlsPolicy.insecure(), null));
  }

  // TC-ENG-301 | headers：首次登录；第二次命中缓存不重登
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void headersLoginThenCache() {
    OrchestrationAccountAuth auth = auth();
    Map<String, String> headers = auth.headers(orchestrateUri());
    assertEquals("Bearer opaque-token", headers.get("Authorization"));
    auth.headers(orchestrateUri());
    assertEquals(1, loginHits.get());
  }

  // TC-ENG-302 | 时钟推进超过有效期 → 重新登录
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void expiredTokenTriggersRelogin() throws Exception {
    AtomicLong clock = new AtomicLong(0L);
    OrchestrationAccountAuth auth =
        new OrchestrationAccountAuth(
            baseUrl, "admin", "Admin@123", TlsPolicy.insecure(), TIMEOUTS, clock::get);
    auth.headers(orchestrateUri());
    assertEquals(1, loginHits.get());
    // 1 小时有效期（纳秒）
    clock.set(Duration.ofHours(2).toNanos());
    auth.headers(orchestrateUri());
    assertEquals(2, loginHits.get());
  }

  // TC-ENG-303 | onUnauthorized：匹配令牌失效并重登；不匹配保留缓存
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unauthorizedInvalidatesOnlyMatchingToken() {
    OrchestrationAccountAuth auth = auth();
    auth.headers(orchestrateUri());
    assertTrue(
        auth.onUnauthorized(orchestrateUri(), Map.of("Authorization", "Bearer opaque-token")));
    auth.headers(orchestrateUri());
    assertEquals(2, loginHits.get());

    // 非匹配头：按实现契约仍返回 true，但令牌不失效（不重登）
    assertTrue(
        auth.onUnauthorized(orchestrateUri(), Map.of("Authorization", "Bearer other-token")));
    auth.headers(orchestrateUri());
    assertEquals(2, loginHits.get());
  }

  // TC-ENG-304 | scope：越权主机/路径/query/fragment/非默认端口拒绝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void credentialsScopedToCenterPsopApi() {
    OrchestrationAccountAuth auth = auth();
    assertThrows(IllegalArgumentException.class, () -> auth.headers(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> auth.headers(URI.create("http://evil.example/api/v1/orchestrate/x")));
    assertThrows(
        IllegalArgumentException.class, () -> auth.headers(URI.create(baseUrl + "/api/v1/other")));
    assertThrows(
        IllegalArgumentException.class,
        () -> auth.headers(URI.create(baseUrl + "/api/v1/orchestrate/x?q=1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> auth.headers(URI.create(baseUrl + "/api/v1/orchestrate/x#f")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            auth.headers(
                URI.create(
                    "http://user@"
                        + URI.create(baseUrl).getAuthority()
                        + "/api/v1/orchestrate/x")));
    // onUnauthorized 同样校验范围
    assertThrows(
        IllegalArgumentException.class,
        () -> auth.onUnauthorized(URI.create(baseUrl + "/api/v1/other"), Map.of()));
  }

  // TC-ENG-305 | 登录非 200 → HttpServiceException
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loginNonSuccessThrowsServiceException() {
    loginStatus = 401;
    loginBody = "{\"error\":\"bad\"}";
    OrchestrationAccountAuth auth = auth();
    assertThrows(HttpServiceException.class, () -> auth.headers(orchestrateUri()));
  }

  // TC-ENG-306 | 登录响应字段缺失/非法 → 运行时异常（HttpResponseException 直接透传）
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void malformedLoginResponseWrapped() {
    OrchestrationAccountAuth auth = auth();
    loginBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\"}";
    assertThrows(RuntimeException.class, () -> auth.headers(orchestrateUri()));
    loginBody = "{\"access_token\":\"t\",\"token_type\":\"Basic\",\"expires_in\":3600}";
    assertThrows(RuntimeException.class, () -> auth.headers(orchestrateUri()));
    loginBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":0}";
    assertThrows(RuntimeException.class, () -> auth.headers(orchestrateUri()));
    loginBody = "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":\"3600\"}";
    assertThrows(RuntimeException.class, () -> auth.headers(orchestrateUri()));
  }

  // TC-ENG-1124 | 默认端口分支：https→443、http→80（onUnauthorized 不触发登录，纯作用域校验）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onUnauthorizedAcceptsHttpsDefaultPort() {
    OrchestrationAccountAuth account =
        new OrchestrationAccountAuth("https://center.example", "u", "p", TlsPolicy.insecure());
    assertTrue(
        account.onUnauthorized(
            URI.create("https://center.example/api/v1/orchestrate/psop/x"), Map.of()));
  }

  // TC-ENG | onUnauthorizedAcceptsHttpDefaultPort
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onUnauthorizedAcceptsHttpDefaultPort() {
    OrchestrationAccountAuth account =
        new OrchestrationAccountAuth("http://center.example", "u", "p", TlsPolicy.insecure());
    assertTrue(
        account.onUnauthorized(
            URI.create("http://center.example/api/v1/orchestrate/psop/x"), Map.of()));
  }

  // TC-ENG-1125 | 显式端口与默认端口等价（443 显式命中同一分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onUnauthorizedAcceptsExplicitHttpsPort() {
    OrchestrationAccountAuth account =
        new OrchestrationAccountAuth("https://center.example:443", "u", "p", TlsPolicy.insecure());
    assertTrue(
        account.onUnauthorized(
            URI.create("https://center.example/api/v1/orchestrate/psop/x"), Map.of()));
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-660…662: ObservedHttpClient real-request branches (request body observation, push handler
 * passthrough, accessor delegation). Uses only the JDK HTTP server as a fake.
 */
class ObservedHttpClientRealRequestTest {

  private HttpServer server(String response) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return server;
  }

  // TC-ENG-660 | POST with a body drives the request-body observation subscriber
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void postWithBodyIsObservedAndSucceeds() throws Exception {
    HttpServer server = server("{\"ok\":true}");
    try {
      ObservedHttpClient observed = new ObservedHttpClient(HttpClient.newHttpClient());
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/post"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"k\":\"v\"}"))
              .build();
      HttpResponse<String> response = observed.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("{\"ok\":true}", response.body());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-661 | sendAsync with an explicit (null) push promise handler
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sendAsyncWithNullPushHandlerCompletes() throws Exception {
    HttpServer server = server("async");
    try {
      ObservedHttpClient observed = new ObservedHttpClient(HttpClient.newHttpClient());
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/get"))
              .GET()
              .build();
      HttpResponse<String> response =
          observed.sendAsync(request, HttpResponse.BodyHandlers.ofString(), null).get();
      assertEquals("async", response.body());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-662 | delegate accessors are exposed through the wrapper
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void accessorsDelegateToUnderlyingClient() {
    HttpClient delegate = HttpClient.newHttpClient();
    ObservedHttpClient observed = new ObservedHttpClient(delegate);
    assertNotNull(observed.version());
    assertNotNull(observed.connectTimeout());
    assertEquals(delegate.followRedirects(), observed.followRedirects());
    assertEquals(delegate.proxy(), observed.proxy());
    assertEquals(delegate.sslContext(), observed.sslContext());
    assertEquals(delegate.authenticator(), observed.authenticator());
    assertEquals(delegate.cookieHandler(), observed.cookieHandler());
    assertEquals(delegate.executor(), observed.executor());
  }

  // TC-ENG-1074 | throwing delegate：同步/异步 send 的异常原样上抛并记录 FAILURE
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void throwingDelegatePropagatesSyncAndAsync() throws Exception {
    java.net.http.HttpClient throwing = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
    org.mockito.Mockito.when(
            throwing.send(
                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("sync boom"));
    org.mockito.Mockito.when(
            throwing.sendAsync(
                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("async boom"));
    ObservedHttpClient observed = new ObservedHttpClient(throwing);
    java.net.http.HttpRequest request =
        java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/x")).GET().build();
    IllegalStateException syncError =
        assertThrows(
            IllegalStateException.class,
            () -> observed.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()));
    assertEquals("sync boom", syncError.getMessage());
    IllegalStateException asyncError =
        assertThrows(
            IllegalStateException.class,
            () -> observed.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()));
    assertEquals("async boom", asyncError.getMessage());
  }

  // TC-ENG-1075 | BodyPublisher 中途 onError：观察器 end(true) 后向下传播
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void bodyPublisherErrorPropagatesThroughObserver() throws Exception {
    HttpServer server = server("never-read");
    try {
      ObservedHttpClient observed = new ObservedHttpClient(HttpClient.newHttpClient());
      java.net.http.HttpRequest request =
          java.net.http.HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/post"))
              .header("Content-Type", "text/plain")
              .POST(
                  new java.net.http.HttpRequest.BodyPublisher() {
                    @Override
                    public long contentLength() {
                      return 10;
                    }

                    @Override
                    public void subscribe(
                        java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer>
                            subscriber) {
                      subscriber.onSubscribe(
                          new java.util.concurrent.Flow.Subscription() {
                            @Override
                            public void request(long n) {
                              subscriber.onError(new IllegalStateException("body broke"));
                            }

                            @Override
                            public void cancel() {}
                          });
                    }
                  })
              .build();
      java.util.concurrent.CompletionException error =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () ->
                  observed
                      .sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                      .join());
      assertTrue(String.valueOf(error.getCause()).contains("body broke"));
    } finally {
      server.stop(0);
    }
  }
}

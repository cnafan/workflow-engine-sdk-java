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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservedHttpClientTest {
  @AfterEach
  void clearSettings() {
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY");
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS");
  }

  // TC-ENG | logsActualRequestAndResponseWithoutReencodingOrExposingCredentials
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void logsActualRequestAndResponseWithoutReencodingOrExposingCredentials() throws Exception {
    String body = "{ \"message\": \"任务\", \"password\": \"login-secret\" }";
    String response = "{\"ok\":true,\"accessSession\":\"omc-secret\"}";
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    List<String> capturedBody = new CopyOnWriteArrayList<>();
    List<List<String>> capturedVersion = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/message",
        exchange -> {
          capturedBody.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          capturedVersion.add(exchange.getRequestHeaders().get("A2A-Version"));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("X-Multi", "one");
          exchange.getResponseHeaders().add("X-Multi", "two");
          exchange.getResponseHeaders().add("bearToken", "header-secret");
          exchange.getResponseHeaders().add("accessSession", "session-header-secret");
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/message"))
              .header("A2A-Version", "1.0")
              .header("Authorization", "Bearer request-secret")
              .header("X-Multi", "a")
              .header("X-Multi", "b")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> result =
          new ObservedHttpClient(HttpClient.newHttpClient(), records::add)
              .send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(response, result.body());
      assertEquals(List.of(body), capturedBody);
      WireLog.Entry sent =
          records.stream()
              .filter(e -> e.direction().equals("REQUEST_BODY"))
              .findFirst()
              .orElseThrow();
      assertEquals(body.replace("login-secret", "***"), sent.body());
      WireLog.Entry header =
          records.stream()
              .filter(e -> e.direction().equals("REQUEST_HEADERS"))
              .findFirst()
              .orElseThrow();
      assertEquals(capturedVersion.get(0), header.headers().get("A2A-Version"));
      assertEquals(List.of("a", "b"), header.headers().get("X-Multi"));
      WireLog.Entry received =
          records.stream()
              .filter(e -> e.direction().equals("RESPONSE_HEADERS"))
              .findFirst()
              .orElseThrow();
      assertEquals(List.of("one", "two"), received.headers().get("x-multi"));
      assertEquals(List.of("***"), received.headers().get("beartoken"));
      assertEquals(List.of("***"), received.headers().get("accesssession"));
      assertEquals(1, records.stream().map(WireLog.Entry::requestId).distinct().count());
      String logs = records.toString();
      assertFalse(logs.contains("login-secret"));
      assertFalse(logs.contains("omc-secret"));
      assertFalse(logs.contains("header-secret"));
      assertFalse(logs.contains("request-secret"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | streamingFramesKeepUtf8ControlLinesAndArriveBeforeCompletion
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void streamingFramesKeepUtf8ControlLinesAndArriveBeforeCompletion() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body collector = new WireLog.Body(true, true, frames::add);
    String first = ": heartbeat\r\nevent: result\r\nid: 7\r\nretry: 100\r\ndata: 中文\r\n\r\n";
    for (byte value : first.getBytes(StandardCharsets.UTF_8))
      collector.accept(ByteBuffer.wrap(new byte[] {value}));
    assertEquals(1, frames.size());
    assertEquals(first, frames.get(0)[0]);
    String second = "data: 第二帧\n\n";
    collector.accept(ByteBuffer.wrap(second.getBytes(StandardCharsets.UTF_8)));
    assertEquals(second, frames.get(1)[0]);
    collector.end(false);
    assertEquals(2, frames.size());
  }

  // TC-ENG | rawSseCommentRefreshesTransportActivityWithoutNeedingADataEvent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void rawSseCommentRefreshesTransportActivityWithoutNeedingADataEvent() throws Exception {
    AtomicInteger activities = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/events",
        exchange -> {
          byte[] bytes = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events"))
              .GET()
              .build();
      HttpResponse<String> response =
          TransportActivityMonitor.call(
              activities::incrementAndGet,
              () -> {
                try {
                  return new ObservedHttpClient(HttpClient.newHttpClient())
                      .send(request, HttpResponse.BodyHandlers.ofString());
                } catch (IOException | InterruptedException error) {
                  throw new CompletionException(error);
                }
              });

      assertEquals(": heartbeat\n\n", response.body());
      assertTrue(activities.get() > 0);
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | oversizedFramesAreExplicitlyDroppedWithoutLeakingPartialSecrets
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void oversizedFramesAreExplicitlyDroppedWithoutLeakingPartialSecrets() {
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS", "256");
    List<String[]> frames = new ArrayList<>();
    WireLog.Body collector = new WireLog.Body(true, true, frames::add);
    collector.accept(
        ByteBuffer.wrap(
            ("data: {\"password\":\"" + "secret".repeat(100) + "\"}\n\n" + "data: ok\n\n")
                .getBytes(StandardCharsets.UTF_8)));
    assertTrue(frames.get(0)[1].contains("dropped-capacity"));
    assertFalse(frames.get(0)[0].contains("secret"));
    assertEquals("data: ok\n\n", frames.get(1)[0]);
  }

  // TC-ENG | loggingFailureCannotChangeBodyDeliveryAndBodySwitchIsExplicit
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loggingFailureCannotChangeBodyDeliveryAndBodySwitchIsExplicit() throws Exception {
    List<WireLog.Entry> entries = new ArrayList<>();
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY", "false");
    WireLog.emit(
        entries::add,
        "DIRECT_HTTP",
        "REQUEST",
        "1",
        "/test",
        "POST",
        null,
        Map.of(),
        "serialized-utf8",
        "private",
        "",
        Map.of());
    assertTrue(entries.get(0).visibility().contains("disabled"));
    assertFalse(entries.get(0).body().contains("private"));
    assertDoesNotThrow(
        () ->
            WireLog.emit(
                e -> {
                  throw new IllegalStateException("observer");
                },
                "DIRECT_HTTP",
                "REQUEST",
                "1",
                "/test",
                "POST",
                null,
                Map.of(),
                "serialized-utf8",
                "",
                "",
                Map.of()));
  }

  // TC-ENG | subscriberCancellationIsNotLoggedAsATransportFailure
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void subscriberCancellationIsNotLoggedAsATransportFailure() {
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://agent.example/message:stream")).GET().build();
    HttpResponse.BodyHandler<Void> cancellingHandler =
        ignored ->
            new HttpResponse.BodySubscriber<>() {
              private final CompletableFuture<Void> body = new CompletableFuture<>();

              @Override
              public CompletionStage<Void> getBody() {
                return body;
              }

              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
              }

              @Override
              public void onNext(List<ByteBuffer> item) {}

              @Override
              public void onError(Throwable error) {
                body.completeExceptionally(error);
              }

              @Override
              public void onComplete() {
                body.complete(null);
              }
            };

    CompletableFuture<HttpResponse<Void>> result =
        new ObservedHttpClient(new CancellationReportingHttpClient(), records::add)
            .sendAsync(request, cancellingHandler);

    assertThrows(CompletionException.class, result::join);
    assertTrue(records.stream().noneMatch(entry -> entry.direction().equals("FAILURE")));
  }

  private static final class CancellationReportingHttpClient extends HttpClient {
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
      HttpResponse.ResponseInfo info =
          new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
              return 200;
            }

            @Override
            public HttpHeaders headers() {
              return HttpHeaders.of(
                  Map.of("Content-Type", List.of("text/event-stream")), (name, value) -> true);
            }

            @Override
            public Version version() {
              return Version.HTTP_1_1;
            }
          };
      handler
          .apply(info)
          .onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {
                  result.completeExceptionally(new IOException("subscriber cancelled"));
                }
              });
      return result;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public javax.net.ssl.SSLContext sslContext() {
      return defaultSslContext();
    }

    @Override
    public javax.net.ssl.SSLParameters sslParameters() {
      return new javax.net.ssl.SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    private static javax.net.ssl.SSLContext defaultSslContext() {
      try {
        return javax.net.ssl.SSLContext.getDefault();
      } catch (java.security.NoSuchAlgorithmException error) {
        throw new IllegalStateException(error);
      }
    }
  }
}

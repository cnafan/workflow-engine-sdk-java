/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpHeaders;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-520…527：A2AErrorDetectingHttpClient 同步 GET/POST/DELETE 与 GET SSE 路径。
 *
 * <p>既有测试只覆盖 Post.postAsyncSSE；本类补齐 createGet/createDelete、Wrapped 头透传、 同步错误信封检测，以及 Get.getAsyncSSE
 * 的错误帧/空流/正常帧分支。
 */
class A2AErrorDetectingHttpClientSyncTest {

  private static final String ERROR_BODY =
      "{\"error\":{\"code\":429,\"message\":\"Task limit reached\","
          + "\"status\":\"RESOURCE_EXHAUSTED\"}}";

  private static A2AHttpResponse response(int status, String body) {
    return new A2AHttpResponse() {
      @Override
      public int status() {
        return status;
      }

      @Override
      public boolean success() {
        return status >= 200 && status < 300;
      }

      @Override
      public String body() {
        return body;
      }

      @Override
      public A2AHttpHeaders headers() {
        return A2AHttpHeaders.of(Map.of("x-trace", java.util.List.of("trace-1")));
      }
    };
  }

  // TC-ENG-520 | 同步 GET 2xx：URL/单头/多头调用全部透传到 delegate，响应原样返回
  //
  // 输入: GET 200 + 普通 JSON 体
  // 预期输出: delegate 收到 url/header/headers；返回同一响应实例
  // 断言点: 透传值 + 响应身份；防回归: Wrapped 转发被删即失败
  @Test
  void getSuccessForwardsBuilderCalls() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    Map<String, String> seenHeaders = new LinkedHashMap<>();
    A2AHttpResponse ok = response(200, "{\"ok\":true}");
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(syncClient("GET", url, seenHeaders, ok));

    A2AHttpResponse result =
        client
            .createGet()
            .url("http://agent.test/a2a")
            .addHeader("X-One", "1")
            .addHeaders(Map.of("X-Two", "2"))
            .get();
    assertSame(ok, result);
    assertEquals("http://agent.test/a2a", url.get());
    assertEquals("1", seenHeaders.get("X-One"));
    assertEquals("2", seenHeaders.get("X-Two"));
  }

  // TC-ENG-521 | 同步 GET 500 + A2A 错误信封 → 抛 RemoteA2AErrorException
  //
  // 输入: GET 500，体为标准 error 信封
  // 预期输出: RemoteA2AErrorException，httpStatus=500，消息透传
  // 断言点: 状态码+消息；防回归: check() 被旁路即漏检
  @Test
  void getErrorEnvelopeThrows() {
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            syncClient(
                "GET", new AtomicReference<>(), new LinkedHashMap<>(), response(500, ERROR_BODY)));
    RemoteA2AErrorException error =
        assertThrows(RemoteA2AErrorException.class, () -> client.createGet().url("http://x").get());
    assertEquals(500, error.getHttpStatus());
    assertTrue(error.getMessage().contains("Task limit reached"));
  }

  // TC-ENG-522 | 同步 POST 2xx：body/url/头透传
  //
  // 输入: POST 200
  // 预期输出: delegate 收到 body 与 url
  // 断言点: 透传值 + 响应体；防回归: Post.body 转发被删即失败
  @Test
  void postSuccessForwardsBody() throws Exception {
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    A2AHttpResponse ok = response(200, "{\"task\":{}}");
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            new A2AHttpClient() {
              @Override
              public GetBuilder createGet() {
                throw new UnsupportedOperationException();
              }

              @Override
              public DeleteBuilder createDelete() {
                throw new UnsupportedOperationException();
              }

              @Override
              public PostBuilder createPost() {
                return new PostBuilder() {
                  @Override
                  public PostBuilder url(String u) {
                    url.set(u);
                    return this;
                  }

                  @Override
                  public PostBuilder body(String b) {
                    body.set(b);
                    return this;
                  }

                  @Override
                  public PostBuilder addHeader(String key, String value) {
                    return this;
                  }

                  @Override
                  public PostBuilder addHeaders(Map<String, String> values) {
                    return this;
                  }

                  @Override
                  public A2AHttpResponse post() {
                    return ok;
                  }

                  @Override
                  public CompletableFuture<Void> postAsyncSSE(
                      Consumer<ServerSentEvent> messages,
                      Consumer<Throwable> errors,
                      Runnable complete) {
                    throw new UnsupportedOperationException();
                  }
                };
              }
            });
    A2AHttpResponse result = client.createPost().url("http://x/stream").body("{\"id\":1}").post();
    assertSame(ok, result);
    assertEquals("http://x/stream", url.get());
    assertEquals("{\"id\":1}", body.get());
  }

  // TC-ENG-523 | 同步 DELETE 404 + 错误信封 → RemoteA2AErrorException
  //
  // 输入: DELETE 404 + error 信封
  // 预期输出: RemoteA2AErrorException 404
  // 断言点: 状态码；防回归: Delete.check 被删即失败
  @Test
  void deleteErrorEnvelopeThrows() {
    String body = "{\"error\":{\"code\":404,\"message\":\"task gone\",\"status\":\"NOT_FOUND\"}}";
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            syncClient(
                "DELETE", new AtomicReference<>(), new LinkedHashMap<>(), response(404, body)));
    RemoteA2AErrorException error =
        assertThrows(
            RemoteA2AErrorException.class,
            () -> client.createDelete().url("http://x/tasks/1").delete());
    assertEquals(404, error.getHttpStatus());
    assertEquals("task gone", error.getMessage());
  }

  // TC-ENG-524 | 同步 DELETE 204 无体 → 正常返回（null body 不抛）
  //
  // 输入: DELETE 204 body=null
  // 预期输出: 响应透传
  // 断言点: 身份；防回归: 空体误判为错误
  @Test
  void deleteEmptyBodyPassesThrough() throws Exception {
    A2AHttpResponse noContent = response(204, null);
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            syncClient("DELETE", new AtomicReference<>(), new LinkedHashMap<>(), noContent));
    assertSame(noContent, client.createDelete().url("http://x").delete());
  }

  // TC-ENG-525 | GET SSE 正常帧后完成 → future 正常完成，complete 恰好一次
  //
  // 输入: getAsyncSSE 推送一帧 task 数据后 complete
  // 预期输出: 数据原文到达 observer，future 正常完成
  // 断言点: 数据 + 完成次数；防回归: Get SSE 未走 stream() 包装
  @Test
  void getAsyncSseHappyPath() throws Exception {
    String data = "{\"task\":{\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}";
    AtomicReference<String> seen = new AtomicReference<>();
    AtomicInteger completions = new AtomicInteger();
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            sseClient(
                "GET",
                (messages, errors, complete) -> {
                  messages.accept(new ServerSentEvent(data));
                  complete.run();
                  return CompletableFuture.completedFuture(null);
                }));
    client
        .createGet()
        .url("http://x/subscribe")
        .getAsyncSSE(
            event -> seen.set(event.data()),
            error -> {
              throw new AssertionError(error);
            },
            completions::incrementAndGet)
        .join();
    assertEquals(data, seen.get());
    assertEquals(1, completions.get());
  }

  // TC-ENG-526 | GET SSE 错误帧 → future 异常完成且 upstream 取消
  //
  // 输入: getAsyncSSE 首帧为 A2A 错误信封
  // 预期输出: join 抛 CompletionException 包装 RemoteA2AErrorException；upstream 被取消
  // 断言点: 错误类型/状态码 + upstream 取消；防回归: GET SSE 漏检错误帧
  @Test
  void getAsyncSseErrorFrameFails() throws Exception {
    CompletableFuture<Void> upstream = new CompletableFuture<>();
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            sseClient(
                "GET",
                (messages, errors, complete) -> {
                  messages.accept(new ServerSentEvent(ERROR_BODY));
                  return upstream;
                }));
    CompletableFuture<Void> result =
        client
            .createGet()
            .url("http://x")
            .getAsyncSSE(
                event -> {
                  throw new AssertionError("must not deliver error frame");
                },
                error -> {},
                () -> {
                  throw new AssertionError("must not complete");
                });
    CompletionException failure = assertThrows(CompletionException.class, result::join);
    assertTrue(RemoteA2AErrorException.findIn(failure) != null);
    assertEquals(429, RemoteA2AErrorException.findIn(failure).getHttpStatus());
    assertTrue(upstream.isCancelled());
  }

  // TC-ENG-527 | GET SSE 无事件即关流 → IOException
  //
  // 输入: getAsyncSSE 直接 complete，未推送任何事件
  // 预期输出: 原因 IOException "closed without an event"
  // 断言点: 异常类型与字面消息；防回归: 空流误当成功
  @Test
  void getAsyncSseClosedWithoutEventFails() throws Exception {
    A2AErrorDetectingHttpClient client =
        new A2AErrorDetectingHttpClient(
            sseClient(
                "GET",
                (messages, errors, complete) -> {
                  complete.run();
                  return CompletableFuture.completedFuture(null);
                }));
    CompletableFuture<Void> result =
        client
            .createGet()
            .url("http://x")
            .getAsyncSSE(
                event -> {
                  throw new AssertionError();
                },
                error -> {},
                () -> {
                  throw new AssertionError();
                });
    CompletionException failure = assertThrows(CompletionException.class, result::join);
    assertTrue(failure.getCause() instanceof IOException);
    assertEquals("A2A streaming response closed without an event", failure.getCause().getMessage());
  }

  private interface SseStart {
    CompletableFuture<Void> run(
        Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete);
  }

  private static A2AHttpClient sseClient(String method, SseStart start) {
    return new A2AHttpClient() {
      @Override
      public GetBuilder createGet() {
        if (!"GET".equals(method)) throw new UnsupportedOperationException();
        return new GetBuilder() {
          @Override
          public GetBuilder url(String url) {
            return this;
          }

          @Override
          public GetBuilder addHeader(String key, String value) {
            return this;
          }

          @Override
          public GetBuilder addHeaders(Map<String, String> headers) {
            return this;
          }

          @Override
          public A2AHttpResponse get() {
            throw new UnsupportedOperationException();
          }

          @Override
          public CompletableFuture<Void> getAsyncSSE(
              Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete) {
            try {
              return start.run(messages, errors, complete);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
      }

      @Override
      public PostBuilder createPost() {
        throw new UnsupportedOperationException();
      }

      @Override
      public DeleteBuilder createDelete() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static A2AHttpClient syncClient(
      String method,
      AtomicReference<String> urlSink,
      Map<String, String> headerSink,
      A2AHttpResponse response) {
    return new A2AHttpClient() {
      @Override
      public GetBuilder createGet() {
        if (!"GET".equals(method)) throw new UnsupportedOperationException();
        return new GetBuilder() {
          @Override
          public GetBuilder url(String u) {
            urlSink.set(u);
            return this;
          }

          @Override
          public GetBuilder addHeader(String k, String v) {
            headerSink.put(k, v);
            return this;
          }

          @Override
          public GetBuilder addHeaders(Map<String, String> values) {
            headerSink.putAll(values);
            return this;
          }

          @Override
          public A2AHttpResponse get() {
            return response;
          }

          @Override
          public CompletableFuture<Void> getAsyncSSE(
              Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete) {
            throw new UnsupportedOperationException();
          }
        };
      }

      @Override
      public PostBuilder createPost() {
        if (!"POST".equals(method)) throw new UnsupportedOperationException();
        return new PostBuilder() {
          @Override
          public PostBuilder url(String u) {
            urlSink.set(u);
            return this;
          }

          @Override
          public PostBuilder body(String b) {
            return this;
          }

          @Override
          public PostBuilder addHeader(String k, String v) {
            headerSink.put(k, v);
            return this;
          }

          @Override
          public PostBuilder addHeaders(Map<String, String> values) {
            headerSink.putAll(values);
            return this;
          }

          @Override
          public A2AHttpResponse post() {
            return response;
          }

          @Override
          public CompletableFuture<Void> postAsyncSSE(
              Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete) {
            throw new UnsupportedOperationException();
          }
        };
      }

      @Override
      public DeleteBuilder createDelete() {
        if (!"DELETE".equals(method)) throw new UnsupportedOperationException();
        return new DeleteBuilder() {
          @Override
          public DeleteBuilder url(String u) {
            urlSink.set(u);
            return this;
          }

          @Override
          public DeleteBuilder addHeader(String k, String v) {
            headerSink.put(k, v);
            return this;
          }

          @Override
          public DeleteBuilder addHeaders(Map<String, String> values) {
            headerSink.putAll(values);
            return this;
          }

          @Override
          public A2AHttpResponse delete() {
            return response;
          }
        };
      }
    };
  }
}

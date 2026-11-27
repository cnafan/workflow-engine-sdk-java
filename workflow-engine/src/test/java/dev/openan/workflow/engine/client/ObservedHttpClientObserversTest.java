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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-060…065：ObservedHttpClient 的失败路径、pushHandler 透传与配置 getter 透传。 */
class ObservedHttpClientObserversTest {
  private static final HttpRequest REQUEST =
      HttpRequest.newBuilder(URI.create("http://boundary.example/message")).GET().build();

  @AfterEach
  void clearSettings() {
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY");
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS");
  }

  // TC-ENG-060 | 同步 send 失败时发出 FAILURE 事件并原样抛出
  //
  // 输入: delegate.send 抛 IOException（消息含 "connection refused: token=secret"）
  // 预期输出: IOException 原样抛出；观察者收到 FAILURE，errorType=IOException，secret 被脱敏
  // 断言点: 异常类型 + FAILURE 内容脱敏；防回归: catch 分支或脱敏被删即失败
  @Test
  void synchronousFailureEmitsRedactedFailureEvent() {
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    HttpClient throwing =
        new StubClient() {
          @Override
          public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
              throws IOException {
            throw new IOException("connection refused: token=secret");
          }
        };

    IOException error =
        assertThrows(
            IOException.class,
            () -> new ObservedHttpClient(throwing, records::add).send(REQUEST, noopHandler()));

    assertTrue(error.getMessage().contains("connection refused"));
    WireLog.Entry failure =
        records.stream()
            .filter(entry -> entry.direction().equals("FAILURE"))
            .findFirst()
            .orElseThrow();
    assertTrue(failure.visibility().contains("errorType=IOException"));
    assertTrue(failure.visibility().contains("token=***"));
    assertFalse(failure.visibility().contains("secret"));
  }

  // TC-ENG-061 | 异步 sendAsync 成功时结果透传且不产生 FAILURE
  //
  // 输入: delegate.sendAsync 返回完成态 future，ResponseInfo 状态 200
  // 预期输出: 结果透传；观察者收到 REQUEST_HEADERS + RESPONSE_HEADERS，无 FAILURE
  // 断言点: 状态码 + 事件方向集合；防回归: whenComplete 误标失败即失败
  @Test
  void asynchronousSuccessForwardsResultWithoutFailure() {
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    HttpClient completing =
        new StubClient() {
          @Override
          public <T> CompletableFuture<HttpResponse<T>> sendAsync(
              HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            handler.apply(
                info(
                    200,
                    HttpHeaders.of(
                        Map.of("Content-Type", List.of("application/json")),
                        (name, value) -> true)));
            return CompletableFuture.completedFuture(cast(newResponse(200)));
          }
        };

    HttpResponse<Void> result =
        new ObservedHttpClient(completing, records::add).sendAsync(REQUEST, noopHandler()).join();

    assertEquals(200, result.statusCode());
    assertTrue(records.stream().anyMatch(e -> e.direction().equals("REQUEST_HEADERS")));
    assertTrue(records.stream().anyMatch(e -> e.direction().equals("RESPONSE_HEADERS")));
    assertTrue(records.stream().noneMatch(e -> e.direction().equals("FAILURE")));
  }

  // TC-ENG-062 | 异步 sendAsync 抛运行时异常时发出 FAILURE 并上抛
  //
  // 输入: delegate.sendAsync 抛 IllegalStateException("boom secret-value")
  // 预期输出: IllegalStateException 上抛；FAILURE 含脱敏后的消息
  // 断言点: 异常类型 + FAILURE 脱敏；防回归: catch(RuntimeException) 被删即失败
  @Test
  void asynchronousRuntimeExceptionEmitsFailure() {
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    HttpClient throwing =
        new StubClient() {
          @Override
          public <T> CompletableFuture<HttpResponse<T>> sendAsync(
              HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new IllegalStateException("boom secret-value");
          }
        };

    assertThrows(
        IllegalStateException.class,
        () -> new ObservedHttpClient(throwing, records::add).sendAsync(REQUEST, noopHandler()));

    WireLog.Entry failure =
        records.stream()
            .filter(entry -> entry.direction().equals("FAILURE"))
            .findFirst()
            .orElseThrow();
    assertTrue(failure.visibility().contains("errorType=IllegalStateException"));
    assertTrue(failure.visibility().contains("boom secret-value"));
  }

  // TC-ENG-063 | 三参 sendAsync（pushHandler）透传处理器与结果
  //
  // 输入: 三参 sendAsync + 标记 pushHandler；delegate 返回失败 future 触发 whenComplete
  // 预期输出: pushHandler 原引用到达 delegate；FAILURE 恰好发出一次（幂等守卫）
  // 断言点: 引用相等 + FAILURE 去重；防回归: 重载丢失 pushHandler 即失败
  @Test
  void pushPromiseHandlerIsForwardedAndFailureIsLoggedOnce() {
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    HttpResponse.PushPromiseHandler<Void> pushHandler =
        HttpResponse.PushPromiseHandler.<Void>of(
            request -> noopHandler(), new ConcurrentHashMap<>());
    AtomicReference<HttpResponse.PushPromiseHandler<?>> captured = new AtomicReference<>();
    HttpClient failing =
        new StubClient() {
          @Override
          public <T> CompletableFuture<HttpResponse<T>> sendAsync(
              HttpRequest request,
              HttpResponse.BodyHandler<T> handler,
              HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            captured.set(pushPromiseHandler);
            return CompletableFuture.failedFuture(new IOException("push failed secret"));
          }
        };

    CompletableFuture<HttpResponse<Void>> result =
        new ObservedHttpClient(failing, records::add)
            .sendAsync(REQUEST, noopHandler(), (HttpResponse.PushPromiseHandler<Void>) pushHandler);

    assertTrue(result.isCompletedExceptionally());
    assertEquals(pushHandler, captured.get());
    long failures = records.stream().filter(entry -> entry.direction().equals("FAILURE")).count();
    assertEquals(1, failures);
  }

  // TC-ENG-064 | 全部配置 getter 透传 delegate 的非空值
  //
  // 输入: delegate 返回固定非空配置
  // 预期输出: ObservedHttpClient 逐项返回相同值
  // 断言点: 每个 getter 的相等断言；防回归: 任一透传被改写即失败
  @Test
  void configurationGettersDelegate() throws Exception {
    CookieHandler cookieHandler = new CookieManager();
    Duration connectTimeout = Duration.ofSeconds(7);
    ProxySelector proxy = ProxySelector.getDefault();
    SSLContext sslContext = SSLContext.getDefault();
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setProtocols(new String[] {"TLSv1.3"});
    HttpClient delegate =
        new StubClient() {
          @Override
          public Optional<CookieHandler> cookieHandler() {
            return Optional.of(cookieHandler);
          }

          @Override
          public Optional<Duration> connectTimeout() {
            return Optional.of(connectTimeout);
          }

          @Override
          public HttpClient.Redirect followRedirects() {
            return HttpClient.Redirect.ALWAYS;
          }

          @Override
          public Optional<ProxySelector> proxy() {
            return Optional.of(proxy);
          }

          @Override
          public SSLContext sslContext() {
            return sslContext;
          }

          @Override
          public SSLParameters sslParameters() {
            return sslParameters;
          }

          @Override
          public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
          }

          @Override
          public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
          }

          @Override
          public Optional<java.util.concurrent.Executor> executor() {
            return Optional.of(Runnable::run);
          }
        };

    ObservedHttpClient observed = new ObservedHttpClient(delegate);
    assertEquals(Optional.of(cookieHandler), observed.cookieHandler());
    assertEquals(Optional.of(connectTimeout), observed.connectTimeout());
    assertEquals(HttpClient.Redirect.ALWAYS, observed.followRedirects());
    assertEquals(Optional.of(proxy), observed.proxy());
    assertEquals(sslContext, observed.sslContext());
    assertEquals(sslParameters, observed.sslParameters());
    assertEquals(HttpClient.Version.HTTP_2, observed.version());
    assertEquals(Optional.empty(), observed.authenticator());
    assertTrue(observed.executor().isPresent());
  }

  // TC-ENG-065 | 无 Content-Type 头的响应不产生 body 事件
  //
  // 输入: delegate 应答 info 无 Content-Type；handler 立即返回空 body
  // 预期输出: RESPONSE_HEADERS 存在，RESPONSE_BODY 不存在
  // 断言点: Content-Type 空分支；防回归: 类型判断空值分支被删即失败
  @Test
  void responseWithoutContentTypeEmitsHeadersOnly() {
    List<WireLog.Entry> records = new CopyOnWriteArrayList<>();
    HttpClient noType =
        new StubClient() {
          @Override
          public <T> CompletableFuture<HttpResponse<T>> sendAsync(
              HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            handler.apply(info(204, HttpHeaders.of(Map.of(), (name, value) -> true)));
            return CompletableFuture.completedFuture(cast(newResponse(204)));
          }
        };

    HttpResponse<Void> result =
        new ObservedHttpClient(noType, records::add).sendAsync(REQUEST, noopHandler()).join();

    assertEquals(204, result.statusCode());
    assertTrue(records.stream().anyMatch(e -> e.direction().equals("RESPONSE_HEADERS")));
    assertTrue(records.stream().noneMatch(e -> e.direction().equals("RESPONSE_BODY")));
  }

  @SuppressWarnings("unchecked")
  private static <T> HttpResponse<T> cast(HttpResponse<?> response) {
    return (HttpResponse<T>) response;
  }

  private static HttpResponse.ResponseInfo info(int statusCode, HttpHeaders headers) {
    return new HttpResponse.ResponseInfo() {
      @Override
      public int statusCode() {
        return statusCode;
      }

      @Override
      public HttpHeaders headers() {
        return headers;
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }

  private static <T> HttpResponse<T> newResponse(int statusCode) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return statusCode;
      }

      @Override
      public HttpRequest request() {
        return REQUEST;
      }

      @Override
      public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
      }

      @Override
      public T body() {
        return null;
      }

      @Override
      public URI uri() {
        return REQUEST.uri();
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }

      @Override
      public Optional<javax.net.ssl.SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }

  private static HttpResponse.BodyHandler<Void> noopHandler() {
    return info ->
        new HttpResponse.BodySubscriber<>() {
          @Override
          public CompletionStage<Void> getBody() {
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(1);
          }

          @Override
          public void onNext(List<ByteBuffer> item) {}

          @Override
          public void onError(Throwable error) {}

          @Override
          public void onComplete() {}
        };
  }

  private abstract static class StubClient extends HttpClient {
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      throw new IOException("not expected in this test");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      return CompletableFuture.failedFuture(new IOException("not expected in this test"));
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
    public HttpClient.Redirect followRedirects() {
      return HttpClient.Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return defaultSslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<java.net.Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
      return Optional.empty();
    }

    private static SSLContext defaultSslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }
  }
}

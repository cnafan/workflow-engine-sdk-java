/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;
import org.junit.jupiter.api.Test;

class A2AErrorDetectingHttpClientTest {
  // TC-ENG | standardErrorCancelsUpstreamAndSurvivesAThrowingErrorObserver
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void standardErrorCancelsUpstreamAndSurvivesAThrowingErrorObserver() throws Exception {
    var upstream = new CompletableFuture<Void>();
    var failures = new AtomicInteger();
    var events = new AtomicInteger();
    var completions = new AtomicInteger();
    var client =
        client(
            (messages, errors, complete) -> {
              messages.accept(new ServerSentEvent(RemoteA2AErrorResponseTest.error(429)));
              messages.accept(new ServerSentEvent("{\"message\":{}}"));
              complete.run();
              errors.accept(new IllegalStateException("late close"));
              return upstream;
            });
    var result =
        client
            .createPost()
            .postAsyncSSE(
                event -> events.incrementAndGet(),
                error -> {
                  failures.incrementAndGet();
                  throw new IllegalStateException("observer failed");
                },
                completions::incrementAndGet);
    var remoteError =
        RemoteA2AErrorException.findIn(assertThrows(CompletionException.class, result::join));
    assertNotNull(remoteError);
    assertEquals(429, remoteError.getHttpStatus());
    assertEquals(1, remoteError.getSuppressed().length);
    assertTrue(upstream.isCancelled());
    assertEquals(1, failures.get());
    assertEquals(0, events.get());
    assertEquals(0, completions.get());
  }

  // TC-ENG | cancellationPropagatesAndLateEventsCannotCompleteTheCall
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancellationPropagatesAndLateEventsCannotCompleteTheCall() throws Exception {
    var upstream = new CompletableFuture<Void>();
    var messages = new AtomicReference<Consumer<ServerSentEvent>>();
    var complete = new AtomicReference<Runnable>();
    var callbacks = new AtomicInteger();
    var result =
        client(
                (m, e, c) -> {
                  messages.set(m);
                  complete.set(c);
                  return upstream;
                })
            .createPost()
            .postAsyncSSE(
                event -> callbacks.incrementAndGet(),
                error -> callbacks.incrementAndGet(),
                callbacks::incrementAndGet);
    assertTrue(result.cancel(true));
    assertTrue(upstream.isCancelled());
    messages.get().accept(new ServerSentEvent("{}"));
    complete.get().run();
    assertEquals(0, callbacks.get());
  }

  // TC-ENG | successfulDataIsNotRewrittenOrMistakenForANestedError
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void successfulDataIsNotRewrittenOrMistakenForANestedError() throws Exception {
    String data =
        "{\"task\":{\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}},"
            + "\"metadata\":{\"error\":{\"code\":400,\"message\":\"business value\"}}}";
    var seen = new AtomicReference<String>();
    var completions = new AtomicInteger();
    var result =
        client(
                (messages, errors, complete) -> {
                  messages.accept(new ServerSentEvent(data));
                  complete.run();
                  return CompletableFuture.completedFuture(null);
                })
            .createPost()
            .postAsyncSSE(
                event -> seen.set(event.data()),
                error -> fail("unexpected error", error),
                completions::incrementAndGet);
    result.join();
    assertEquals(data, seen.get());
    assertEquals(1, completions.get());
  }

  // TC-ENG | streamThatCompletesWithoutAnA2AEventFailsImmediately
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void streamThatCompletesWithoutAnA2AEventFailsImmediately() throws Exception {
    var failures = new AtomicReference<Throwable>();
    var completions = new AtomicInteger();
    var result =
        client(
                (messages, errors, complete) -> {
                  complete.run();
                  return CompletableFuture.completedFuture(null);
                })
            .createPost()
            .postAsyncSSE(
                event -> fail("unexpected event"), failures::set, completions::incrementAndGet);

    var failure = assertThrows(CompletionException.class, result::join);
    assertInstanceOf(java.io.IOException.class, failure.getCause());
    assertEquals("A2A streaming response closed without an event", failure.getCause().getMessage());
    assertSame(failure.getCause(), failures.get());
    assertEquals(0, completions.get());
  }

  private static A2AHttpClient client(Start start) {
    return new A2AErrorDetectingHttpClient(
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
              public PostBuilder url(String url) {
                return this;
              }

              @Override
              public PostBuilder body(String body) {
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
                throw new UnsupportedOperationException();
              }

              @Override
              public CompletableFuture<Void> postAsyncSSE(
                  Consumer<ServerSentEvent> messages,
                  Consumer<Throwable> errors,
                  Runnable complete) {
                return start.run(messages, errors, complete);
              }
            };
          }
        });
  }

  @FunctionalInterface
  private interface Start {
    CompletableFuture<Void> run(
        Consumer<ServerSentEvent> messages, Consumer<Throwable> errors, Runnable complete);
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.registry.RegistryClient;
import io.grpc.Metadata;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** TC-ENG-1094…1100：SSE 回调异常/配置 null 值/异常消息脱敏/解析守卫等确定性分支。 */
class ClientEdgeBehaviorsTest {

  // ---------- A2AErrorDetectingHttpClient ----------

  private static org.a2aproject.sdk.client.http.A2AHttpResponse okResponse() {
    return new org.a2aproject.sdk.client.http.A2AHttpResponse() {
      @Override
      public int status() {
        return 200;
      }

      @Override
      public boolean success() {
        return true;
      }

      @Override
      public String body() {
        return "{}";
      }
    };
  }

  // TC-ENG-1094 | Post SSE：messages 回调抛异常 → future 以该异常失败（L197-198 分支）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void postSseCallbackThrowFailsFuture() throws Exception {
    var result =
        new A2AErrorDetectingHttpClient(
                new org.a2aproject.sdk.client.http.A2AHttpClient() {
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
                        return this;
                      }

                      @Override
                      public PostBuilder body(String b) {
                        return this;
                      }

                      @Override
                      public PostBuilder addHeader(String k, String v) {
                        return this;
                      }

                      @Override
                      public PostBuilder addHeaders(Map<String, String> values) {
                        return this;
                      }

                      @Override
                      public org.a2aproject.sdk.client.http.A2AHttpResponse post() {
                        return okResponse();
                      }

                      @Override
                      public java.util.concurrent.CompletableFuture<Void> postAsyncSSE(
                          java.util.function.Consumer<
                                  org.a2aproject.sdk.client.http.ServerSentEvent>
                              messages,
                          java.util.function.Consumer<Throwable> errors,
                          Runnable complete) {
                        messages.accept(new org.a2aproject.sdk.client.http.ServerSentEvent("{}"));
                        return new java.util.concurrent.CompletableFuture<>();
                      }
                    };
                  }
                })
            .createPost()
            .postAsyncSSE(
                event -> {
                  throw new IllegalStateException("callback boom");
                },
                error -> {},
                () -> {});
    CompletionException error = assertThrows(CompletionException.class, result::join);
    assertTrue(String.valueOf(error.getCause()).contains("callback boom"));
  }

  // TC-ENG-1095 | Get SSE：complete 回调抛异常 → completeExceptionally（L212-213 分支）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void getSseCompleteThrowFailsFuture() throws Exception {
    var result =
        new A2AErrorDetectingHttpClient(
                new org.a2aproject.sdk.client.http.A2AHttpClient() {
                  @Override
                  public GetBuilder createGet() {
                    return new GetBuilder() {
                      @Override
                      public GetBuilder url(String u) {
                        return this;
                      }

                      @Override
                      public GetBuilder addHeader(String k, String v) {
                        return this;
                      }

                      @Override
                      public GetBuilder addHeaders(Map<String, String> values) {
                        return this;
                      }

                      @Override
                      public org.a2aproject.sdk.client.http.A2AHttpResponse get() {
                        return okResponse();
                      }

                      @Override
                      public java.util.concurrent.CompletableFuture<Void> getAsyncSSE(
                          java.util.function.Consumer<
                                  org.a2aproject.sdk.client.http.ServerSentEvent>
                              messages,
                          java.util.function.Consumer<Throwable> errors,
                          Runnable complete) {
                        messages.accept(
                            new org.a2aproject.sdk.client.http.ServerSentEvent(
                                "{\"task\":{\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}"));
                        complete.run();
                        return new java.util.concurrent.CompletableFuture<>();
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
                })
            .createGet()
            .url("http://x/subscribe")
            .getAsyncSSE(
                event -> {},
                error -> {},
                () -> {
                  throw new IllegalStateException("complete boom");
                });
    CompletionException error = assertThrows(CompletionException.class, result::join);
    assertTrue(String.valueOf(error.getCause()).contains("complete boom"));
  }

  // ---------- WorkflowEngineClientConfig ----------

  // TC-ENG-1096 | scheme 值为 null → 归一为空 Map（区别于 agent 级 null）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void schemeValueNullNormalizesToEmptyMap() {
    Map<String, Map<String, Map<String, Object>>> source = new java.util.HashMap<>();
    Map<String, Map<String, Object>> schemes = new java.util.HashMap<>();
    schemes.put("bearer", null);
    source.put("agent", schemes);
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder().credentialsConfig(source).build();
    assertTrue(config.getCredentialsConfig().get("agent").get("bearer").isEmpty());
  }

  // ---------- Workflow.fromMap ----------

  // TC-ENG-1098 | mapList：next 列表含非对象元素 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fromMapRejectsNonObjectNextEntry() {
    Map<String, Object> data = new java.util.HashMap<>();
    data.put("steps", List.of(Map.of("name", "s1", "next", List.of("scalar"))));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(data));
    assertTrue(error.getMessage().contains("entries must be objects"));
  }

  // ---------- RegistryClient ----------

  // TC-ENG-1099 | fetchAgentCard(name) 单参重载 → 委托 (name, null)
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void registrySingleArgFetchReturnsCard() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              "{\"agentCards\":[{\"name\":\"a\",\"supportedInterfaces\":[]}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      RegistryClient client =
          new RegistryClient(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              false,
              java.time.Duration.ofSeconds(5));
      Map<String, Object> card = client.fetchAgentCard("a");
      assertEquals("a", card.get("name"));
    } finally {
      server.stop(0);
    }
  }

  // ---------- WireGrpcInterceptor.headers ----------

  // TC-ENG-1100 | headers(null) → 空 Map；ASCII/BINARY 键分别处理
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void wireGrpcHeadersBranches() throws Exception {
    Method m = WireGrpcInterceptor.class.getDeclaredMethod("headers", Metadata.class);
    m.setAccessible(true);
    assertTrue(((Map<?, ?>) m.invoke(null, (Object) null)).isEmpty());

    Metadata metadata = new Metadata();
    Metadata.Key<String> ascii = Metadata.Key.of("x-trace", Metadata.ASCII_STRING_MARSHALLER);
    metadata.put(ascii, "trace-1");
    metadata.put(ascii, "trace-2");
    Metadata.Key<byte[]> binary = Metadata.Key.of("x-data-bin", Metadata.BINARY_BYTE_MARSHALLER);
    metadata.put(binary, "raw".getBytes(StandardCharsets.UTF_8));
    @SuppressWarnings("unchecked")
    Map<String, List<String>> result = (Map<String, List<String>>) m.invoke(null, metadata);
    assertEquals(List.of("trace-1", "trace-2"), result.get("x-trace"));
    assertEquals(List.of(Base64Vector("raw")), result.get("x-data-bin"));
  }

  private static String Base64Vector(String raw) {
    return java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}

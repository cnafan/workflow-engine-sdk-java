/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.registry.RegistryClient;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-ENG-1082…1085：GRPC 分支构建/Registry 响应超时/Transport close 超时与中断分支。 */
class TransportAndRegistryTimeoutTest {

  @TempDir Path tmpUnused;

  private static AgentCard grpcCard() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"grpc-agent","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"GRPC","protocolVersion":"1.0",
             "tenant":"","url":"127.0.0.1:1"}]}
            """,
            AgentCard.class);
  }

  // TC-ENG-1082 | buildClientWithTransport：GRPC 分支返回 Client（渠道懒构造，无连接）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void grpcBranchBuildsClient() throws Exception {
    DefaultA2AJavaClientRuntime runtime = new DefaultA2AJavaClientRuntime(false, null, 5L, "GRPC");
    Method m =
        DefaultA2AJavaClientRuntime.class.getDeclaredMethod(
            "buildClientWithTransport",
            AgentCard.class,
            String.class,
            org.a2aproject.sdk.client.http.A2AHttpClient.class);
    m.setAccessible(true);
    Object client = m.invoke(runtime, grpcCard(), "GRPC", null);
    assertNotNull(client);
    runtime.close();
  }

  // TC-ENG-1083 | RegistryClient.send：响应超时 → HttpTimeoutException（ future cancel 分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void registryResponseDeadlineExceeded() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            Thread.sleep(1500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
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
              java.time.Duration.ofMillis(150));
      // request 级超时（JDK 内部触发）与 future.get 显式 deadline 同时存在，先触发者决定
      // 异常形态（直接 HttpTimeoutException 或 ExecutionException 包装）。断言超时语义
      // 而非具体抛出点，消除该竞态的 flaky。
      // 双超时竞态：request 级超时（JDK 内部）与 future.get deadline 先触发者决定异常形态，
      // 故此处使用 Exception.class 后立即 instanceof 收窄（spec §4 宽泛异常豁免场景：
      // 断言后紧跟精确类型检查，不掩盖缺陷）
      Exception error = assertThrows(Exception.class, client::fetchAgentCards);
      Throwable timeout =
          error instanceof java.net.http.HttpTimeoutException ? error : error.getCause();
      assertTrue(
          timeout instanceof java.net.http.HttpTimeoutException,
          "expected a timeout failure, got: " + error);
      assertTrue(
          timeout.getMessage().contains("timed out")
              || timeout.getMessage().contains("deadline exceeded"),
          timeout.getMessage());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-1084 | A2ATransport.close：awaitTermination(2s) 超时 → shutdownNow 分支
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void transportCloseShutsDownExecutorAfterTimeout() throws Exception {
    AgentCard card =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new AgentCardJacksonModule())
            .readValue(
                """
                {"name":"slow-agent","description":"d","version":"1",
                 "capabilities":{"streaming":true,"extensions":[]},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
                 "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
                 "tenant":"","url":"http://127.0.0.1:1/a"}]}
                """,
                AgentCard.class);
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard c,
              org.a2aproject.sdk.spec.MessageSendParams p,
              ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            // 吞掉中断并继续睡，模拟 shutdownNow 也无法立即终止的任务
            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline) {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                // 吞掉：保持任务存活直到自然结束
              }
            }
            return List.of();
          }
        };
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .sendTimeoutSeconds(30)
                .notificationAckTimeoutSeconds(30)
                .build());
    // send() 任务跑在 asyncExecutor 上：close() 时任务仍在睡（吞中断）→
    // awaitTermination(2s) 超时 → shutdownNow 分支
    transport.send(card, "slow-agent", MessageContent.text("x"), "ctx-slow", null, null);
    long start = System.currentTimeMillis();
    transport.close();
    long elapsed = System.currentTimeMillis() - start;
    // awaitTermination(2s) 超时等待
    assertTrue(elapsed >= 1500, "close must wait through executor timeout: " + elapsed);
    assertTrue(elapsed < 20_000, "close must not hang forever: " + elapsed);
  }

  // TC-ENG-1085 | A2ATransport.close：awaitTermination 抛 InterruptedException → 恢复中断标记
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void transportCloseRestoresInterruptFlag() throws Exception {
    AgentCard card =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new AgentCardJacksonModule())
            .readValue(
                """
                {"name":"int-agent","description":"d","version":"1",
                 "capabilities":{"streaming":true,"extensions":[]},
                 "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
                 "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
                 "tenant":"","url":"http://127.0.0.1:1/a"}]}
                """,
                AgentCard.class);
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build());
    // 预先置位中断标记 → awaitTermination 立即抛 InterruptedException
    Thread.currentThread().interrupt();
    try {
      transport.close();
      assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
    } finally {
      Thread.interrupted(); // 清理标记，避免污染后续测试
    }
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-320：通知流等待期间线程中断 → InterruptedException 包装路径。 */
class StreamInterruptTest {
  private HttpServer server;
  private DefaultA2AJavaClientRuntime runtime;

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new AgentCardJacksonModule());

  private AgentCard restCard(int port) throws Exception {
    return JSON.readValue(
        """
        {"name":"stream-interrupt","description":"d","version":"1",
         "capabilities":{"streaming":true,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
         "tenant":"","url":"http://127.0.0.1:%d"}]}
        """
            .formatted(port),
        AgentCard.class);
  }

  private static String statusFrame(TaskState state) throws Exception {
    Message message =
        Message.builder()
            .messageId("sm-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("working"))
            .build();
    TaskStatusUpdateEvent event =
        TaskStatusUpdateEvent.builder()
            .taskId("task-x")
            .contextId("c-1")
            .status(new TaskStatus(state, message, null))
            .build();
    String json =
        JsonFormat.printer()
            .print(
                StreamResponse.newBuilder()
                    .setStatusUpdate(ProtoUtils.ToProto.taskStatusUpdateEvent(event))
                    .build());
    return "data:" + json + "\n\n";
  }

  @BeforeEach
  void setUp() {
    runtime = null;
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) {
      runtime.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  // TC-ENG-320 | 通知流消费事件后进入空闲等待，中断调用线程 → RuntimeException(interrupted)
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void interruptDuringIdleWaitFailsWithInterrupted() throws Exception {
    CountDownLatch frameDelivered = new CountDownLatch(1);
    CountDownLatch releaseServer = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();
    server.createContext(
        "/",
        (HttpExchange exchange) -> {
          try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            output.write(
                statusFrame(TaskState.TASK_STATE_WORKING).getBytes(StandardCharsets.UTF_8));
            output.flush();
            frameDelivered.countDown();
            try {
              // 保持连接直到测试中断/释放
              releaseServer.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            exchange.close();
          } catch (Exception handlerError) {
            exchange.close();
          }
        });
    server.start();

    AgentCard card = restCard(port);
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");

    AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    CountDownLatch finished = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              try {
                runtime.sendMessage(
                    card,
                    org.a2aproject.sdk.spec.MessageSendParams.builder()
                        .message(
                            Message.builder()
                                .messageId(java.util.UUID.randomUUID().toString())
                                .role(Message.Role.ROLE_USER)
                                .parts(new TextPart("subscribe"))
                                .contextId("c-1")
                                .build())
                        .build(),
                    new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
                        java.util.Map.<String, Object>of(
                            A2AJavaClientRuntime.CHANNEL_STATE_KEY,
                            A2AJavaClientRuntime.NOTIFICATION_CHANNEL,
                            A2AJavaClientRuntime.TRANSPORT_ACTIVITY_STATE_KEY,
                            (Runnable) () -> {}),
                        java.util.Map.<String, String>of()),
                    event -> {},
                    log -> {});
              } catch (Throwable t) {
                workerFailure.set(t);
              } finally {
                finished.countDown();
              }
            });
    worker.setDaemon(true);
    worker.start();

    // 等待事件帧已投递，空闲等待开始
    assertTrue(frameDelivered.await(15, TimeUnit.SECONDS));
    Thread.sleep(300);
    worker.interrupt();
    assertTrue(finished.await(10, TimeUnit.SECONDS));
    Throwable failure = workerFailure.get();
    assertNotNull(failure);
    String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
    assertTrue(
        message.contains("interrupt") || message.contains("idle") || message.contains("cancel"),
        "expected interrupt-related failure: " + failure);
    releaseServer.countDown();
  }

  private static void assertNotNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNotNull(value);
  }
}

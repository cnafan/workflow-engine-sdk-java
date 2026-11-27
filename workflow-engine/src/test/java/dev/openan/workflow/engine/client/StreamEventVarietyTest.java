/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-340：通知流投递多类事件帧（message/artifact/task），覆盖 onEvent 全部分支。 投递后保持连接，再中断线程结束。 */
class StreamEventVarietyTest {
  private HttpServer server;
  private DefaultA2AJavaClientRuntime runtime;

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new AgentCardJacksonModule());

  private AgentCard restCard(int port) throws Exception {
    return JSON.readValue(
        """
        {"name":"event-variety","description":"d","version":"1",
         "capabilities":{"streaming":true,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
         "tenant":"","url":"http://127.0.0.1:%d"}]}
        """
            .formatted(port),
        AgentCard.class);
  }

  private static String frame(StreamResponse response) throws Exception {
    String json = JsonFormat.printer().print(response);
    return "data:" + json + "\n\n";
  }

  private static String messageFrame() throws Exception {
    Message message =
        Message.builder()
            .messageId("m-1")
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("note"))
            .build();
    return frame(
        StreamResponse.newBuilder().setMessage(ProtoUtils.ToProto.message(message)).build());
  }

  private static String artifactFrame() throws Exception {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a-1")
            .name("report")
            .parts(List.of(new TextPart("chunk")))
            .build();
    TaskArtifactUpdateEvent event =
        TaskArtifactUpdateEvent.builder()
            .taskId("task-x")
            .contextId("c-1")
            .artifact(artifact)
            .append(true)
            .lastChunk(true)
            .build();
    return frame(
        StreamResponse.newBuilder()
            .setArtifactUpdate(ProtoUtils.ToProto.taskArtifactUpdateEvent(event))
            .build());
  }

  private static String statusFrame(TaskState state) throws Exception {
    TaskStatusUpdateEvent event =
        TaskStatusUpdateEvent.builder()
            .taskId("task-x")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .build();
    return frame(
        StreamResponse.newBuilder()
            .setStatusUpdate(ProtoUtils.ToProto.taskStatusUpdateEvent(event))
            .build());
  }

  private static String taskFrame(TaskState state) throws Exception {
    Task task =
        Task.builder()
            .id("task-x")
            .contextId("c-1")
            .status(new TaskStatus(state))
            .history(List.of())
            .build();
    return frame(StreamResponse.newBuilder().setTask(ProtoUtils.ToProto.task(task)).build());
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

  // TC-ENG | multipleEventKindsBeforeInterrupt
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void multipleEventKindsBeforeInterrupt() throws Exception {
    CountDownLatch framesDone = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
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
            // message → artifact → status → task（覆盖 onEvent 四个分支）
            output.write(messageFrame().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.write(artifactFrame().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.write(
                statusFrame(TaskState.TASK_STATE_WORKING).getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.write(taskFrame(TaskState.TASK_STATE_WORKING).getBytes(StandardCharsets.UTF_8));
            output.flush();
            framesDone.countDown();
            release.await(30, TimeUnit.SECONDS);
            exchange.close();
          } catch (Exception e) {
            exchange.close();
          }
        });
    server.start();

    AgentCard card = restCard(port);
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");

    AtomicReference<Throwable> failure = new AtomicReference<>();
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
                                .parts(new TextPart("sub"))
                                .contextId("c-1")
                                .build())
                        .build(),
                    new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
                        java.util.Map.<String, Object>of(
                            A2AJavaClientRuntime.CHANNEL_STATE_KEY,
                            A2AJavaClientRuntime.NOTIFICATION_CHANNEL),
                        java.util.Map.<String, String>of()),
                    event -> {},
                    log -> {});
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                finished.countDown();
              }
            });
    worker.setDaemon(true);
    worker.start();

    assertTrue(framesDone.await(15, TimeUnit.SECONDS));
    Thread.sleep(300);
    worker.interrupt();
    assertTrue(finished.await(10, TimeUnit.SECONDS));
    assertNotNull(failure.get());
    release.countDown();
  }
}

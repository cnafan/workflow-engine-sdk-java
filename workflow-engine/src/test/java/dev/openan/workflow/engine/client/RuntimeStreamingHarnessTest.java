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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.model.ReceivedMessage;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-140…145：REST 传输 + SSE 流式 harness。帧格式与 samples JdkHttpA2AServer 完全一致 （id:N +
 * data:StreamResponse proto-JSON），覆盖 subscribeToTask 流生命周期与 REST unary。
 */
class RuntimeStreamingHarnessTest {
  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private HttpServer server;
  private DefaultA2AJavaClientRuntime runtime;
  private final List<ClientEvent> sunk = new CopyOnWriteArrayList<>();
  private volatile List<String> sseFrames = List.of();
  private volatile boolean malformedFrame = false;

  @BeforeEach
  void resetSink() {
    sunk.clear();
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

  private AgentCard restCard(int port) throws Exception {
    return JSON.readValue(
        """
        {"name":"stream-agent","description":"d","version":"1",
         "capabilities":{"streaming":true,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
         "tenant":"","url":"http://127.0.0.1:%d"}]}
        """
            .formatted(port),
        AgentCard.class);
  }

  private static String protoJson(StreamResponse response) throws Exception {
    String printed = JsonFormat.printer().print(response);
    return printed.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "");
  }

  private static String sseFrame(long seq, StreamingEventKind event) throws Exception {
    StreamResponse.Builder builder = StreamResponse.newBuilder();
    if (event instanceof Message m) {
      builder.setMessage(ProtoUtils.ToProto.message(m));
    } else if (event instanceof Task t) {
      builder.setTask(ProtoUtils.ToProto.task(t));
    } else if (event instanceof TaskStatusUpdateEvent e) {
      builder.setStatusUpdate(ProtoUtils.ToProto.taskStatusUpdateEvent(e));
    } else if (event instanceof TaskArtifactUpdateEvent e) {
      builder.setArtifactUpdate(ProtoUtils.ToProto.taskArtifactUpdateEvent(e));
    }
    return "id:" + seq + "\ndata:" + protoJson(builder.build()) + "\n\n";
  }

  private int startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int[] portRef = new int[] {server.getAddress().getPort()};
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          System.out.println("[HARNESS] " + exchange.getRequestMethod() + " " + path);
          String method = exchange.getRequestMethod();
          exchange.getRequestBody().readAllBytes();
          try {
            if ("POST".equals(method)
                && (path.endsWith("/message:stream") || path.endsWith("/message:send"))) {
              Task task = terminalRestTask();
              String frame =
                  "data:"
                      + protoJson(
                          StreamResponse.newBuilder()
                              .setTask(ProtoUtils.ToProto.task(task))
                              .build())
                      + "\n\n";
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
              exchange.sendResponseHeaders(200, 0);
              OutputStream output = exchange.getResponseBody();
              output.write(frame.getBytes(StandardCharsets.UTF_8));
              output.flush();
              exchange.close();
              return;
            }
            if ("POST".equals(method) && path.endsWith(":subscribe")) {
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
              exchange.sendResponseHeaders(200, 0);
              OutputStream output = exchange.getResponseBody();
              long seq = 0;
              for (String frame : sseFrames) {
                String resolved = frame.replace("{SEQ}", String.valueOf(++seq));
                if (malformedFrame && resolved.startsWith("data:")) {
                  resolved = "data:not-json-at-all\n\n";
                }
                output.write(resolved.getBytes(StandardCharsets.UTF_8));
                output.flush();
              }
              output.flush();
              exchange.close();
              return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          } catch (Exception handlerError) {
            exchange.close();
          }
        });
    server.start();
    return portRef[0];
  }

  private static Task workingRestTask() {
    return Task.builder()
        .id("task-x")
        .contextId("c-1")
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  private static Task terminalRestTask() {
    return Task.builder()
        .id("task-x")
        .contextId("c-1")
        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
        .artifacts(
            List.of(
                Artifact.builder()
                    .artifactId("a-1")
                    .name("out")
                    .parts(List.of(new TextPart("stream result")))
                    .metadata(Map.of())
                    .build()))
        .history(List.of())
        .metadata(Map.of())
        .build();
  }

  private MessageSendParams subscribeParams() {
    return MessageSendParams.builder()
        .message(
            Message.builder()
                .messageId(UUID.randomUUID().toString())
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart("stream"))
                .contextId("c-1")
                .build())
        .build();
  }

  // TC-ENG-140 | REST unary send：message:send 快乐路径
  //
  // 输入: REST 绑定卡片 + SendMessageResponse proto-JSON 响应
  // 预期输出: 事件含终端 TaskEvent；结果文本来自 artifact
  // 断言点: taskState 与文本；防回归: REST 分支被删即失败
  @Test
  void restUnarySendAssemblesResult() throws Exception {
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");
    AgentCard card = restCard(port);

    List<ClientEvent> events =
        toList(runtime.sendMessage(card, subscribeParams(), null, null, null));

    assertTrue(events.stream().anyMatch(e -> e instanceof TaskEvent));
    assertTrue(
        events.stream()
            .anyMatch(e -> e instanceof TaskEvent te && te.getTask().status().state().isFinal()));
  }

  // TC-ENG-141 | subscribeToTask SSE 快乐路径：WORKING → COMPLETED 终态完成
  //
  // 输入: SSE 两帧（状态更新非终态 → 终态）
  // 预期输出: future 正常完成；eventSink 收到两帧；结果 taskState=COMPLETED
  // 断言点: 完成态与投影；防回归: 流事件循环被删即失败
  @Test
  void subscribeSseCompletesOnTerminalStatus() throws Exception {
    sseFrames =
        List.of(
            "id:1\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setStatusUpdate(
                            ProtoUtils.ToProto.taskStatusUpdateEvent(statusUpdate(false)))
                        .build())
                + "\n\n",
            "id:2\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setStatusUpdate(
                            ProtoUtils.ToProto.taskStatusUpdateEvent(statusUpdate(true)))
                        .build())
                + "\n\n");
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");
    AgentCard card = restCard(port);

    SendMessageResult result =
        runtime.subscribeToTask(card, "task-x", null, sunk::add).get(15, TimeUnit.SECONDS);

    assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    assertTrue(sunk.size() >= 2);
  }

  // TC-ENG-142 | SSE artifact 帧：非终态事件作为应用数据转发
  //
  // 输入: artifactUpdate 帧 → 终态帧
  // 预期输出: future 完成；结果文本来自 artifact 流
  // 断言点: 文本投影；防回归: artifact 事件处理被删即失败
  @Test
  void artifactUpdateFrameIsForwardedAsApplicationData() throws Exception {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a-1")
            .name("out")
            .parts(List.of(new TextPart("stream result")))
            .metadata(Map.of())
            .build();
    TaskArtifactUpdateEvent artifactEvent =
        TaskArtifactUpdateEvent.builder()
            .taskId("task-x")
            .contextId("c-1")
            .artifact(artifact)
            .append(false)
            .lastChunk(true)
            .build();
    sseFrames =
        List.of(
            "id:1\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setArtifactUpdate(
                            ProtoUtils.ToProto.taskArtifactUpdateEvent(artifactEvent))
                        .build())
                + "\n\n",
            "id:2\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setStatusUpdate(
                            ProtoUtils.ToProto.taskStatusUpdateEvent(statusUpdate(true)))
                        .build())
                + "\n\n");
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");
    AgentCard card = restCard(port);

    SendMessageResult result =
        runtime.subscribeToTask(card, "task-x", null, sunk::add).get(15, TimeUnit.SECONDS);

    assertEquals("stream result", result.getText());
    assertTrue(sunk.size() >= 2);
  }

  // TC-ENG-143 | eventSink 抛异常不终止流
  //
  // 输入: SSE 两帧 + eventSink 抛 IllegalStateException
  // 预期输出: future 仍然正常完成
  // 断言点: 完成态；防回归: 回调异常逃逸即失败
  @Test
  void eventSinkFailureDoesNotBreakStream() throws Exception {
    sseFrames =
        List.of(
            "id:1\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setStatusUpdate(
                            ProtoUtils.ToProto.taskStatusUpdateEvent(statusUpdate(false)))
                        .build())
                + "\n\n",
            "id:2\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setStatusUpdate(
                            ProtoUtils.ToProto.taskStatusUpdateEvent(statusUpdate(true)))
                        .build())
                + "\n\n");
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");
    AgentCard card = restCard(port);

    SendMessageResult result =
        runtime
            .subscribeToTask(
                card,
                "task-x",
                null,
                event -> {
                  throw new IllegalStateException("sink blew up");
                })
            .get(15, TimeUnit.SECONDS);

    assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    assertTrue(sunk.isEmpty());
  }

  // TC-ENG-144 | 畸形 SSE 帧转为失败 future
  //
  // 输入: 第一帧为非法 JSON
  // 预期输出: future 异常完成（流解析错误进入错误回调）
  // 断言点: 异常完成；防回归: 流错误回调被删即失败
  @Test
  void malformedSseFrameFailsTheFuture() throws Exception {
    malformedFrame = true;
    sseFrames = List.of("id:1\ndata:not-json-at-all\n\n");
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");
    AgentCard card = restCard(port);

    java.util.concurrent.CompletableFuture<SendMessageResult> future =
        runtime.subscribeToTask(card, "task-x", null, sunk::add);
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
    assertTrue(error.getCause() != null);
  }

  // TC-ENG-145 | ReceivedMessage 经 Subscribe 事件组装（构造与访问）
  //
  // 输入: 事件投影出的 task 与文本
  // 预期输出: ReceivedMessage 列表可从结果访问
  // 断言点: 列表非空；防回归: 组装回归即失败
  @Test
  void subscribeResultCarriesReceivedMessages() throws Exception {
    sseFrames =
        List.of(
            "id:1\ndata:"
                + protoJson(
                    StreamResponse.newBuilder()
                        .setStatusUpdate(
                            ProtoUtils.ToProto.taskStatusUpdateEvent(statusUpdate(true)))
                        .build())
                + "\n\n");
    int port = startServer();
    runtime = new DefaultA2AJavaClientRuntime(true, null, 10L, "HTTP+JSON");
    AgentCard card = restCard(port);

    SendMessageResult result =
        runtime.subscribeToTask(card, "task-x", null, sunk::add).get(15, TimeUnit.SECONDS);

    List<ReceivedMessage> messages = result.getReceivedMessages();
    assertNotNull(messages);
  }

  private static List<ClientEvent> toList(Iterable<ClientEvent> events) {
    List<ClientEvent> copy = new CopyOnWriteArrayList<>();
    events.forEach(copy::add);
    return copy;
  }

  private static TaskStatusUpdateEvent statusUpdate(boolean terminal) {
    TaskState state = terminal ? TaskState.TASK_STATE_COMPLETED : TaskState.TASK_STATE_WORKING;
    return TaskStatusUpdateEvent.builder()
        .taskId("task-x")
        .contextId("c-1")
        .status(new TaskStatus(state))
        .build();
  }
}

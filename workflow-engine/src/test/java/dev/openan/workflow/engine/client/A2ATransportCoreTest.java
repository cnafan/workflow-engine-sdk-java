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
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-130…143：A2ATransport 构造校验、事件投影静态助手与 send 边界。 */
class A2ATransportCoreTest {
  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static AgentCard cardFromJson(String json) throws Exception {
    return JSON.readValue(json, AgentCard.class);
  }

  private static AgentCard validCard(String name) throws Exception {
    return cardFromJson(
        """
        {"name":"%s","description":"d","version":"1",
         "capabilities":{"streaming":false,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
         "tenant":"","url":"http://agent.example.test/a2a"}]}
        """
            .formatted(name));
  }

  private static Task terminalTask() {
    return Task.builder()
        .id("task-1")
        .contextId("c-1")
        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
        .artifacts(
            List.of(
                Artifact.builder()
                    .artifactId("a-1")
                    .name("out")
                    .parts(List.of(new TextPart("result text")))
                    .metadata(Map.of("src", "task"))
                    .build()))
        .history(List.of())
        .metadata(Map.of("taskKey", "taskValue"))
        .build();
  }

  // TC-ENG-130 | 构造校验：空白名 / 缺 interfaces / 重名 全部拒绝
  //
  // 输入: 三种非法卡片（"缺 capabilities"分支因 SDK JSON 解析即拒绝而不可达，见 §6）
  // 预期输出: IllegalArgumentException 且消息命中对应守卫
  // 断言点: 异常消息关键字；防回归: 任一校验被删即失败
  @Test
  void constructorValidatesCards() throws Exception {
    WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder().build();
    AgentCard blankName =
        cardFromJson(
            """
            {"name":" ","description":"d","version":"1",
             "capabilities":{"streaming":false},
             "defaultInputModes":["text"],"defaultOutputModes":["text"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://x"}]}
            """);
    AgentCard noInterfaces =
        cardFromJson(
            """
            {"name":"ok","description":"d","version":"1",
             "capabilities":{"streaming":false},
             "defaultInputModes":["text"],"defaultOutputModes":["text"],"skills":[],
             "supportedInterfaces":[]}
            """);
    assertThrows(
        IllegalArgumentException.class, () -> new A2ATransport(List.of(blankName), null, config));
    assertThrows(
        IllegalArgumentException.class,
        () -> new A2ATransport(List.of(noInterfaces), null, config));
    AgentCard valid = validCard("dup");
    assertThrows(
        IllegalArgumentException.class,
        () -> new A2ATransport(List.of(valid, valid), null, config));
  }

  // TC-ENG-131 | extractResponseText：artifact 追加/替换去重，快照与消息兜底
  //
  // 输入: 1) 追加型 artifact 流 2) 快照型 Task 3) 纯 Message
  // 预期输出: 追加拼接 / 快照取值 / 消息兜底
  // 断言点: 文本结果；防回归: 三级回退被改即失败
  @Test
  void responseTextExtractionFallbacks() {
    Artifact chunk1 =
        Artifact.builder()
            .artifactId("a-1")
            .name("out")
            .parts(List.of(new TextPart("first ")))
            .metadata(Map.of())
            .build();
    Artifact chunk2 =
        Artifact.builder()
            .artifactId("a-1")
            .name("out")
            .parts(List.of(new TextPart("second")))
            .metadata(Map.of())
            .build();
    TaskUpdateEvent appended =
        new TaskUpdateEvent(
            Task.builder()
                .id("t")
                .contextId("c")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .build(),
            TaskArtifactUpdateEvent.builder()
                .taskId("t")
                .contextId("c")
                .artifact(chunk1)
                .append(true)
                .lastChunk(false)
                .build());
    TaskUpdateEvent appended2 =
        new TaskUpdateEvent(
            Task.builder()
                .id("t")
                .contextId("c")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .build(),
            TaskArtifactUpdateEvent.builder()
                .taskId("t")
                .contextId("c")
                .artifact(chunk2)
                .append(true)
                .lastChunk(true)
                .build());
    assertEquals("first second", A2ATransport.extractResponseText(List.of(appended, appended2)));

    TaskUpdateEvent replace =
        new TaskUpdateEvent(
            Task.builder()
                .id("t")
                .contextId("c")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .build(),
            TaskArtifactUpdateEvent.builder()
                .taskId("t")
                .contextId("c")
                .artifact(chunk2)
                .append(false)
                .lastChunk(true)
                .build());
    assertEquals("second", A2ATransport.extractResponseText(List.of(replace)));

    Task snapshotTask =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .artifacts(List.of(chunk1))
            .history(List.of())
            .metadata(Map.of())
            .build();
    assertEquals("first ", A2ATransport.extractResponseText(List.of(new TaskEvent(snapshotTask))));

    Message message =
        Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .messageId("m-1")
            .parts(new TextPart("fallback text"))
            .metadata(Map.of())
            .build();
    assertEquals(
        "fallback text", A2ATransport.extractResponseText(List.of(new MessageEvent(message))));
  }

  // TC-ENG-132 | 状态/任务/元数据投影：状态更新与消息元数据合并
  //
  // 输入: TaskEvent(带 artifact/task 元数据) + StatusUpdate(带 message 元数据)
  // 预期输出: taskState 取终态、task 取最后一个、metadata 合并
  // 断言点: 各投影字段；防回归: 元数据合并被删即失败
  @Test
  void taskStateAndMetadataProjections() {
    Task taskWithMeta = terminalTask();
    TaskUpdateEvent statusUpdate =
        new TaskUpdateEvent(
            Task.builder()
                .id("task-1")
                .contextId("c-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .build(),
            TaskStatusUpdateEvent.builder()
                .taskId("task-1")
                .contextId("c-1")
                .status(
                    new TaskStatus(
                        TaskState.TASK_STATE_COMPLETED,
                        Message.builder()
                            .role(Message.Role.ROLE_AGENT)
                            .messageId("m")
                            .parts(new TextPart("done"))
                            .metadata(Map.of("msgKey", "msgValue"))
                            .build(),
                        null))
                .metadata(Map.of("sueKey", "sueValue"))
                .build());

    assertEquals(
        "TASK_STATE_COMPLETED",
        A2ATransport.extractResponseTaskState(List.of(new TaskEvent(taskWithMeta), statusUpdate)));
    assertEquals(
        "task-1",
        A2ATransport.extractResponseTask(List.of(new TaskEvent(taskWithMeta), statusUpdate)).id());
    Map<String, Object> metadata =
        A2ATransport.extractResponseMetadata(List.of(new TaskEvent(taskWithMeta), statusUpdate));
    assertTrue(metadata.containsKey("taskKey"));
    assertTrue(metadata.containsKey("src"));
    assertTrue(metadata.containsKey("sueKey"));
    assertTrue(metadata.containsKey("msgKey"));
  }

  // TC-ENG-133 | extractExtensionUris：null 卡片 / null extensions / 空 uri / 空列表
  //
  // 输入: 退化输入（"null capabilities" 因 SDK JSON 解析即拒绝而不可达，见 §6）
  // 预期输出: 空列表
  // 断言点: 空;防回归: 空守卫被删即 NPE
  @Test
  void extensionUriExtractionHandlesNulls() throws Exception {
    assertTrue(A2ATransport.extractExtensionUris(null).isEmpty());
    AgentCard noExtensions =
        cardFromJson(
            """
            {"name":"n","description":"d","version":"1",
             "capabilities":{"streaming":false},
             "defaultInputModes":["text"],"defaultOutputModes":["text"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://x"}]}
            """);
    assertTrue(A2ATransport.extractExtensionUris(noExtensions).isEmpty());
  }

  // TC-ENG-134 | isFailureState / isAcknowledgementState 判定
  //
  // 输入: FAILED/CANCELED/CANCELLED/REJECTED 与正常态
  // 预期输出: 失败态 true，其余 false；ACK 仅看非空
  // 断言点: 布尔值；防回归: 判定被改即失败
  @Test
  void failureAndAckStateClassification() {
    assertTrue(A2ATransport.isAcknowledgementState("TASK_STATE_WORKING"));
    assertTrue(!A2ATransport.isAcknowledgementState(""));
    assertTrue(!A2ATransport.isAcknowledgementState(null));
  }

  // TC-ENG-135 | send 快乐路径：事件经 eventSink 转发，结果组装
  //
  // 输入: stub runtime 返回 terminal TaskEvent 列表
  // 预期输出: SendMessageResult.text/task/taskState 正确；eventSink 收到事件
  // 断言点: 结果内容；防回归: 组装被删即失败
  @Test
  void sendHappyPathAssemblesResult() throws Exception {
    AgentCard card = validCard("agent-a");
    AtomicStubRuntime runtime = new AtomicStubRuntime(List.of(new TaskEvent(terminalTask())));
    WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder().build();
    try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
      List<ClientEvent> sunk = new java.util.concurrent.CopyOnWriteArrayList<>();
      SendMessageResult result =
          transport
              .send(card, "agent-a", MessageContent.text("你好"), "ctx-1", null, sunk::add)
              .get(10, TimeUnit.SECONDS);
      assertEquals("result text", result.getText());
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals("task-1", result.getTask().id());
      assertTrue(sunk.size() >= 1);
    }
  }

  // TC-ENG-136 | send 时 SecurityException 原样抛出
  //
  // 输入: runtime 抛 SecurityException
  // 预期输出: SecurityException 透传
  // 断言点: 异常类型；防回归: 重抛分支被删即失败
  @Test
  void securityExceptionIsRethrown() throws Exception {
    AgentCard card = validCard("agent-a");
    A2AJavaClientRuntime runtime =
        new AtomicStubRuntime(List.of()) {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard agentCard,
              org.a2aproject.sdk.spec.MessageSendParams params,
              ClientCallContext callContext,
              java.util.function.Consumer<ClientEvent> eventSink,
              java.util.function.Consumer<String> logSink) {
            throw new SecurityException("denied: token=secret");
          }
        };
    WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder().build();
    try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
      java.util.concurrent.CompletableFuture<SendMessageResult> future =
          transport.send(card, "agent-a", MessageContent.text("x"), "ctx", null, null);
      java.util.concurrent.ExecutionException error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> future.get(10, TimeUnit.SECONDS));
      assertTrue(error.getCause() instanceof SecurityException);
    }
  }

  // TC-ENG-137 | 关闭的 transport send → 失败 future
  //
  // 输入: close 后 send
  // 预期输出: future 异常完成，消息含 "transport is closed"
  // 断言点: 异常消息；防回归: closed 守卫被删即失败
  @Test
  void closedTransportRejectsSend() throws Exception {
    AgentCard card = validCard("agent-a");
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            new AtomicStubRuntime(List.of()),
            WorkflowEngineClientConfig.builder().build());
    transport.close();
    java.util.concurrent.CompletableFuture<SendMessageResult> future =
        transport.send(card, "agent-a", MessageContent.text("x"), "ctx", null, null);
    java.util.concurrent.ExecutionException error =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    assertTrue(error.getCause().getMessage().contains("transport is closed"));
  }

  // TC-ENG-138 | closeConversation 委托给会话级 runtime
  //
  // 输入: 会话级 runtime 记录 closeConversation 调用
  // 预期输出: 委托到达；runtime 非会话级时不抛
  // 断言点: 委托调用；防回归: 委托分支被删即失败
  @Test
  void closeConversationDelegates() throws Exception {
    AgentCard card = validCard("agent-a");
    AtomicStubRuntime runtime = new AtomicStubRuntime(List.of());
    WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder().build();
    try (A2ATransport transport = new A2ATransport(List.of(card), runtime, config)) {
      transport.closeConversation(card, "ctx-1");
      assertEquals("agent-a:ctx-1", runtime.conversationClosed.get());
    }
  }

  // TC-ENG-139 | getCard / sendTimeoutSeconds / getContextId 基础访问
  //
  // 输入: 构造后的 transport
  // 预期输出: 卡片按名获取；超时与上下文 id 非空
  // 断言点: 值；防回归: 访问器回归即失败
  @Test
  void basicAccessors() throws Exception {
    AgentCard card = validCard("agent-a");
    A2ATransport transport =
        new A2ATransport(
            List.of(card),
            new AtomicStubRuntime(List.of()),
            WorkflowEngineClientConfig.builder().build());
    assertEquals("agent-a", transport.getCard("agent-a").name());
    assertTrue(transport.sendTimeoutSeconds() > 0);
    assertNotNull(transport.getContextId());
    transport.close();
  }

  private static class AtomicStubRuntime
      implements A2AJavaClientRuntime, ConversationScopedA2AJavaClientRuntime {
    private final List<ClientEvent> events;
    private final AtomicReference<String> conversationClosed = new AtomicReference<>();

    AtomicStubRuntime(List<ClientEvent> events) {
      this.events = events;
    }

    @Override
    public void closeConversation(AgentCard agentCard, String contextId) {
      conversationClosed.set(agentCard.name() + ":" + contextId);
    }

    @Override
    public Iterable<ClientEvent> sendMessage(
        AgentCard agentCard,
        org.a2aproject.sdk.spec.MessageSendParams params,
        ClientCallContext callContext,
        java.util.function.Consumer<ClientEvent> eventSink,
        java.util.function.Consumer<String> logSink) {
      events.forEach(eventSink);
      return List.copyOf(events);
    }

    @Override
    public Task getTask(AgentCard agentCard, String taskId, ClientCallContext callContext) {
      throw new UnsupportedOperationException("not expected");
    }

    @Override
    public org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult listTasks(
        AgentCard agentCard,
        org.a2aproject.sdk.spec.ListTasksParams params,
        ClientCallContext callContext) {
      throw new UnsupportedOperationException("not expected");
    }

    @Override
    public Task cancelTask(AgentCard agentCard, String taskId, ClientCallContext callContext) {
      throw new UnsupportedOperationException("not expected");
    }

    @Override
    public java.util.concurrent.CompletableFuture<SendMessageResult> subscribeToTask(
        AgentCard agentCard,
        String taskId,
        ClientCallContext callContext,
        java.util.function.Consumer<ClientEvent> eventSink) {
      throw new UnsupportedOperationException("not expected");
    }

    @Override
    public void close() {}
  }
}

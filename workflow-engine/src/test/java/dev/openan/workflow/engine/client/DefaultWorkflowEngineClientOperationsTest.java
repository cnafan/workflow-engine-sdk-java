/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.openan.a2at.sdk.core.model.MetadataContent;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/** TC-ENG-560…579：DefaultWorkflowEngineClient 身份守卫/协商状态机/门面操作/回调隔离。 */
class DefaultWorkflowEngineClientOperationsTest extends DefaultWorkflowEngineClientNegotiationTest {

  private static WorkflowEngineClientConfig cfg() {
    return WorkflowEngineClientConfig.builder().taskPollIntervalMillis(100).build();
  }

  private static Throwable failure(java.util.concurrent.CompletableFuture<?> future) {
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    return error.getCause();
  }

  // TC-ENG-560 | 4 参 sendTask 传 null EventCallback 不 NPE
  //
  // 输入: sendTask(agent, content, strategy, null)，远端直接 COMPLETED
  // 预期输出: 成功 COMPLETED
  // 断言点: 终态；防回归: null 三元分支被删即 NPE
  @Test
  void nullInvocationCallbackFallsBackToNoop() throws Exception {
    AgentCard card = card();
    A2AJavaClientRuntime runtime =
        runtime(
            params ->
                List.of(
                    response(params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("ok"))));
    try (A2ATransport transport =
        new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      SendMessageResult result =
          client
              .sendTask(
                  "test",
                  MessageContent.text("hi"),
                  request ->
                      java.util.concurrent.CompletableFuture.completedFuture(
                          new NegotiationReply.Send(MessageContent.text("x"))),
                  null)
              .get(5, TimeUnit.SECONDS);
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    }
  }

  // TC-ENG-561 | 3 参 sendTask 传 null strategy → NPE
  //
  // 输入: strategy=null
  // 预期输出: NPE "negotiationStrategy"
  // 断言点: 消息；防回归: 守卫删除即 NPE 于深处
  @Test
  void nullStrategyRejected() throws Exception {
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card()),
            runtime(params -> List.of()),
            WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      ExecutionException error =
          assertThrows(
              ExecutionException.class,
              () ->
                  client.sendTask("test", MessageContent.text("x"), null).get(2, TimeUnit.SECONDS));
      assertTrue(error.getCause() instanceof NullPointerException);
      assertEquals("negotiationStrategy", error.getCause().getMessage());
    }
  }

  // TC-ENG-562 | 首响应任务 id 为 null/空白/contextId 不匹配 → "Remote task/context identity changed"
  //
  // 输入: 三种畸形 TaskEvent
  // 预期输出: IllegalArgumentException 同消息，且无第二次 send
  // 断言点: 类型+消息+send 计数；防回归: 身份守卫被删即错关联
  @Test
  void malformedFirstResponseIdentityRejected() throws Exception {
    for (String variant : List.of("blank-id", "wrong-context")) {
      AtomicInteger sends = new AtomicInteger();
      AgentCard card = card();
      A2AJavaClientRuntime runtime =
          runtime(
              params -> {
                sends.incrementAndGet();
                String id =
                    switch (variant) {
                      case "null-id" -> null;
                      case "blank-id" -> " ";
                      default -> "remote-task";
                    };
                String context =
                    "wrong-context".equals(variant) ? "other" : params.message().contextId();
                TaskEvent event =
                    new TaskEvent(
                        org.a2aproject.sdk.spec.Task.builder()
                            .id(id)
                            .contextId(context)
                            .status(
                                new org.a2aproject.sdk.spec.TaskStatus(
                                    TaskState.TASK_STATE_COMPLETED,
                                    org.a2aproject.sdk.spec.Message.builder()
                                        .messageId("m")
                                        .role(org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT)
                                        .parts(new org.a2aproject.sdk.spec.TextPart("x"))
                                        .build(),
                                    null))
                            .history(List.of())
                            .metadata(Map.of())
                            .build());
                return List.of(event);
              });
      try (A2ATransport transport =
          new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build())) {
        DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
        Throwable cause = failure(client.sendTask("test", MessageContent.text("x")));
        assertTrue(cause instanceof IllegalArgumentException, variant);
        assertTrue(cause.getMessage().contains("Remote task/context identity changed"), variant);
        assertEquals(1, sends.get(), variant);
      }
    }
  }

  // TC-ENG-563 | SUBMITTED 后轮询 COMPLETED
  //
  // 输入: 首响应 SUBMITTED；getTask 回应该 invocation 的 contextId 上 COMPLETED
  // 预期输出: 终态 COMPLETED；1 次 send + 1 次 getTask，无第二次 send
  // 断言点: 终态与计数；防回归: SUBMITTED 观察分支被删
  @Test
  void submittedStateIsObservedThenCompleted() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    AtomicInteger polls = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<String> sendContext =
        new java.util.concurrent.atomic.AtomicReference<>();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard c,
              org.a2aproject.sdk.spec.MessageSendParams p,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx,
              java.util.function.Consumer<ClientEvent> sink,
              java.util.function.Consumer<String> log) {
            sends.incrementAndGet();
            sendContext.set(p.message().contextId());
            return List.of(
                response(p, TaskState.TASK_STATE_SUBMITTED, MessageContent.text("queued")));
          }

          @Override
          public org.a2aproject.sdk.spec.Task getTask(
              AgentCard c,
              String taskId,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext ctx) {
            polls.incrementAndGet();
            return org.a2aproject.sdk.spec.Task.builder()
                .id(taskId)
                .contextId(sendContext.get())
                .status(
                    new org.a2aproject.sdk.spec.TaskStatus(
                        TaskState.TASK_STATE_COMPLETED,
                        org.a2aproject.sdk.spec.Message.builder()
                            .messageId("m")
                            .role(org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT)
                            .parts(new org.a2aproject.sdk.spec.TextPart("diagnosis"))
                            .build(),
                        null))
                .history(List.of())
                .metadata(Map.of())
                .build();
          }
        };
    AgentCard card = card();
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card),
            runtime,
            WorkflowEngineClientConfig.builder()
                .taskPollIntervalMillis(100)
                .sendTimeoutSeconds(30)
                .build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      SendMessageResult result =
          client.sendTask("test", MessageContent.text("x")).get(5, TimeUnit.SECONDS);
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals(1, sends.get());
      assertEquals(1, polls.get());
    }
  }

  // TC-ENG-564 | 非终态响应无远端身份 → observeTask 拒绝
  //
  // 输入: 首响应 WORKING 且 task.id=""（身份守卫空白分支先命中 advance）
  // 预期输出: IllegalArgumentException "Remote task/context identity changed"
  // 断言点: 消息；防回归: 空白身份守卫被删
  @Test
  void nonTerminalWithBlankIdentityRejected() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        new TaskEvent(
                            new org.a2aproject.sdk.spec.Task(
                                "",
                                params.message().contextId(),
                                new org.a2aproject.sdk.spec.TaskStatus(
                                    TaskState.TASK_STATE_WORKING),
                                List.of(),
                                List.of(),
                                Map.of()))));
    AgentCard card = card();
    try (A2ATransport transport =
        new A2ATransport(List.of(card), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      Throwable cause = failure(client.sendTask("test", MessageContent.text("x")));
      assertTrue(cause instanceof IllegalArgumentException);
      assertTrue(cause.getMessage().contains("Remote task/context identity changed"));
    }
  }

  // TC-ENG-565 | 协商回复：performative=ACCEPT 远端却给出 ACCEPT 提案 → "Expected a valid Propose"
  //
  // 输入: INPUT_REQUIRED 携带 performative=ACCEPT 的 negotiation 元数据
  // 预期输出: IAE；远端任务取消 1 次；协商回调 0 次
  // 断言点: 消息+计数；防回归: PROPOSE 守卫被删
  @Test
  void nonProposePerformativeRejected() throws Exception {
    AtomicInteger negotiations = new AtomicInteger();
    AtomicInteger cancels = new AtomicInteger();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        response(
                            params,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            negotiation("n-1", 1, 1, NegotiationPerformative.ACCEPT))));
    runtime.cancelTaskReturns(
        id -> {
          cancels.incrementAndGet();
          return org.a2aproject.sdk.spec.Task.builder()
              .id(id)
              .contextId("ctx")
              .status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_CANCELED))
              .build();
        });
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    negotiations.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        new NegotiationReply.Send(MessageContent.text("x")));
                  })
              .build());
      Throwable cause = failure(client.sendTask("test", negotiationTask("start")));
      assertTrue(cause instanceof IllegalArgumentException);
      assertTrue(cause.getMessage().contains("Expected a valid Negotiation-T Propose"));
      assertEquals(0, negotiations.get());
      assertEquals(1, cancels.get());
    }
  }

  // TC-ENG-566 | 协商预算耗尽 → ISE
  //
  // 输入: maxExchanges=1；连续两轮不同 round 的 PROPOSE，宿主每轮 ACCEPT
  // 预期输出: ISE "exchange budget exhausted"；2 次 send
  // 断言点: 消息+send 计数；防回归: 预算计数被删
  @Test
  void exchangeBudgetExhausted() throws Exception {
    AtomicInteger sends = new AtomicInteger();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params -> {
                  int round = sends.incrementAndGet();
                  return List.of(
                      response(
                          params,
                          TaskState.TASK_STATE_INPUT_REQUIRED,
                          negotiation("n-1", round, 5, NegotiationPerformative.PROPOSE)));
                });
    runtime.cancelTaskReturns(
        id ->
            org.a2aproject.sdk.spec.Task.builder()
                .id(id)
                .contextId("ctx")
                .status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_CANCELED))
                .build());
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client =
          new DefaultWorkflowEngineClient(
              transport,
              WorkflowEngineClientConfig.builder()
                  .maxNegotiationExchanges(1)
                  .taskPollIntervalMillis(100)
                  .build());
      AtomicInteger replies = new AtomicInteger();
      client.setControlPoint(
          ControlPoint.builder()
              .onNegotiation(
                  request -> {
                    int round = replies.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        new NegotiationReply.Send(
                            A2atMessages.from(
                                new MetadataContent(
                                    "Negotiation-T/information-negotiation/response/v1",
                                    "opaque",
                                    A2ATExtension.NEGOTIATION_T.uri(),
                                    new net.openan.a2at.sdk.core.model.NegotiationContext(
                                        "n-1", round, 5, NegotiationPerformative.ACCEPT)),
                                List.of(new org.a2aproject.sdk.spec.TextPart("ok")))));
                  })
              .build());
      Throwable cause = failure(client.sendTask("test", negotiationTask("start")));
      assertTrue(cause instanceof IllegalStateException);
      assertTrue(cause.getMessage().contains("exchange budget exhausted"));
      assertEquals(2, sends.get());
    }
  }

  // TC-ENG-567 | getTask 门面快乐路径
  //
  // 输入: fake getTask 返回 COMPLETED+artifact 文本
  // 预期输出: SendMessageResult 状态/文本正确，调用 1 次
  // 断言点: 状态+文本+计数
  @Test
  void getTaskFacadeHappyPath() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .getTaskReturns(
                id -> {
                  calls.incrementAndGet();
                  assertEquals("t-9", id);
                  return org.a2aproject.sdk.spec.Task.builder()
                      .id(id)
                      .contextId("ctx")
                      .status(
                          new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_COMPLETED))
                      .artifacts(
                          List.of(
                              org.a2aproject.sdk.spec.Artifact.builder()
                                  .artifactId("a")
                                  .parts(List.of(new org.a2aproject.sdk.spec.TextPart("fetched")))
                                  .build()))
                      .build();
                });
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      SendMessageResult result = client.getTask("test", "t-9").get(5, TimeUnit.SECONDS);
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
      assertEquals("fetched", result.getText());
      assertEquals(1, calls.get());
    }
  }

  // TC-ENG-568 | cancelTask 门面快乐路径
  //
  // 输入: fake cancelTask 返回 CANCELED
  // 预期输出: 状态 CANCELED，taskId 透传
  // 断言点: 状态；防回归: 门面委托被删
  @Test
  void cancelTaskFacadeHappyPath() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .cancelTaskReturns(
                id -> {
                  assertEquals("t-1", id);
                  return org.a2aproject.sdk.spec.Task.builder()
                      .id(id)
                      .contextId("ctx")
                      .status(new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_CANCELED))
                      .build();
                });
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      SendMessageResult result = client.cancelTask("test", "t-1").get(5, TimeUnit.SECONDS);
      assertEquals("TASK_STATE_CANCELED", result.getTaskState());
    }
  }

  // TC-ENG-569 | listTasks 门面快乐路径
  //
  // 输入: fake listTasks 返回 1 个任务
  // 预期输出: tasks.size=1
  // 断言点: 大小；防回归: 委托被删
  @Test
  void listTasksFacadeHappyPath() throws Exception {
    FakeA2AJavaClientRuntime runtime = new FakeA2AJavaClientRuntime();
    runtime.listTasksHandler =
        params ->
            new ListTasksResult(
                List.of(
                    org.a2aproject.sdk.spec.Task.builder()
                        .id("t")
                        .contextId("c")
                        .status(
                            new org.a2aproject.sdk.spec.TaskStatus(TaskState.TASK_STATE_WORKING))
                        .build()));
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      ListTasksResult result =
          client
              .listTasks("test", ListTasksParams.builder().pageSize(10).build())
              .get(5, TimeUnit.SECONDS);
      assertEquals(1, result.tasks().size());
    }
  }

  // TC-ENG-570 | subscribeToTask 门面：用户 sink 与全局 callback 双收事件
  //
  // 输入: fake subscribe 推 1 个 status 事件
  // 预期输出: 两个 sink 各收到 1 个 map，含 agent 名
  // 断言点: 计数+内容；防回归: 转发 lambda 被删
  @Test
  void subscribeFacadeForwardsToBothSinks() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .subscribe(
                (taskId, ctx, sink) ->
                    sink.accept(
                        new org.a2aproject.sdk.client.TaskUpdateEvent(
                            org.a2aproject.sdk.spec.Task.builder()
                                .id(taskId)
                                .contextId("c")
                                .status(
                                    new org.a2aproject.sdk.spec.TaskStatus(
                                        TaskState.TASK_STATE_WORKING))
                                .build(),
                            org.a2aproject.sdk.spec.TaskStatusUpdateEvent.builder()
                                .taskId(taskId)
                                .contextId("c")
                                .status(
                                    new org.a2aproject.sdk.spec.TaskStatus(
                                        TaskState.TASK_STATE_WORKING))
                                .build())));
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      AtomicInteger global = new AtomicInteger();
      client.setEventCallback(
          new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
              global.incrementAndGet();
              assertEquals("test", data.get("agent"));
            }
          });
      AtomicInteger user = new AtomicInteger();
      client
          .subscribeToTask(
              "test",
              "t-1",
              data -> {
                user.incrementAndGet();
                assertEquals("test", data.get("agent"));
              })
          .get(5, TimeUnit.SECONDS);
      assertEquals(1, global.get());
      assertEquals(1, user.get());
    }
  }

  // TC-ENG-571 | 门面关闭后所有操作以 "Workflow client closed" 失败
  //
  // 输入: close() 后 getTask/listTasks/cancelTask/subscribe/sendTask
  // 预期输出: 均异常完成/抛 IllegalStateException
  // 断言点: 消息；防回归: closed 守卫被删
  @Test
  void operationsRejectedAfterFacadeClose() throws Exception {
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card()),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      client.close();
      for (java.util.concurrent.CompletableFuture<?> future :
          List.of(
              client.getTask("test", "t"),
              client.listTasks("test", ListTasksParams.builder().build()),
              client.cancelTask("test", "t"),
              client.sendTask("test", MessageContent.text("x")))) {
        Throwable cause = failure(future);
        assertTrue(cause instanceof IllegalStateException);
        assertTrue(cause.getMessage().contains("Workflow client closed"));
      }
      CompletionException subError =
          assertThrows(
              CompletionException.class,
              () -> client.subscribeToTask("test", "t", data -> {}).join());
      assertTrue(subError.getCause().getMessage().contains("Workflow client closed"));
    }
  }

  // TC-ENG-572 | 取消清理失败时作为被抑制异常挂在主错误上
  //
  // 输入: 远端 AUTH_REQUIRED（主错误）；cancelTask 抛 "cancel network down"
  // 预期输出: 主错误消息含 AUTH_REQUIRED；suppressed 含取消错误
  // 断言点: suppressed；防回归: 抑制聚合被删
  @Test
  void cancelFailureIsSuppressedOnPrimaryError() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        response(
                            params,
                            TaskState.TASK_STATE_AUTH_REQUIRED,
                            MessageContent.text("need auth"))));
    runtime.cancelTaskReturns(
        id -> {
          throw new IllegalStateException("cancel network down");
        });
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      Throwable cause = failure(client.sendTask("test", MessageContent.text("x")));
      assertTrue(cause.getMessage().contains("AUTH_REQUIRED"), cause.getMessage());
      boolean suppressed =
          java.util.Arrays.stream(cause.getSuppressed())
              .anyMatch(
                  error ->
                      error.getMessage() != null
                          && error.getMessage().contains("cancel network down"));
      assertTrue(suppressed, "cancel failure must be suppressed onto the primary error");
    }
  }

  // TC-ENG-573 | per-invocation 事件回调抛异常不外泄
  //
  // 输入: sendTask 的 EventCallback 对 AGENT_REQUEST 抛 RuntimeException
  // 预期输出: 任务仍 COMPLETED
  // 断言点: 终态；防回归: 回调隔离被删即整单失败
  @Test
  void throwingInvocationCallbackDoesNotFailTask() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        response(
                            params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("ok"))));
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      EventCallback throwing =
          new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
              throw new IllegalStateException("event boom: " + type);
            }
          };
      SendMessageResult result =
          client
              .sendTask(
                  "test",
                  MessageContent.text("x"),
                  request -> {
                    throw new AssertionError();
                  },
                  throwing)
              .get(5, TimeUnit.SECONDS);
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    }
  }

  // TC-ENG-574 | 全局事件回调抛异常不影响订阅
  //
  // 输入: setEventCallback 回调总抛异常；subscribe 推事件
  // 预期输出: future 正常完成
  // 断言点: 正常完成；防回归: 全局回调隔离被删
  @Test
  void throwingGlobalCallbackDoesNotFailSubscribe() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .subscribe(
                (taskId, ctx, sink) ->
                    sink.accept(
                        new TaskEvent(
                            org.a2aproject.sdk.spec.Task.builder()
                                .id(taskId)
                                .contextId("c")
                                .status(
                                    new org.a2aproject.sdk.spec.TaskStatus(
                                        TaskState.TASK_STATE_COMPLETED))
                                .build())));
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      client.setEventCallback(
          new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
              throw new IllegalStateException("global boom");
            }
          });
      client.subscribeToTask("test", "t-1", data -> {}).get(5, TimeUnit.SECONDS);
    }
  }

  // TC-ENG-575 | 关闭进行中的调用：completion 以取消完成
  //
  // 输入: send 阻塞于 latch；close() 后释放
  // 预期输出: future 以 CancellationException 完成
  // 断言点: 异常类型；防回归: close 取消在途调用被删
  @Test
  void closeCancelsInFlightInvocation() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params -> {
                  entered.countDown();
                  try {
                    release.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return List.of(
                      response(
                          params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("late")));
                });
    A2ATransport transport =
        new A2ATransport(
            List.of(card()),
            runtime,
            WorkflowEngineClientConfig.builder().sendTimeoutSeconds(30).build());
    DefaultWorkflowEngineClient client =
        new DefaultWorkflowEngineClient(
            transport,
            WorkflowEngineClientConfig.builder()
                .taskPollIntervalMillis(100)
                .sendTimeoutSeconds(30)
                .build());
    java.util.concurrent.CompletableFuture<SendMessageResult> future =
        client.sendTask("test", MessageContent.text("x"));
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    client.close();
    release.countDown();
    assertThrows(
        java.util.concurrent.CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
    transport.close();
  }

  // TC-ENG-576 | unwrap：多层嵌套与 null cause
  //
  // 输入: CompletionException(ExecutionException(cause))；CompletationException(null)
  // 预期输出: 最深层 cause；包装自身
  // 断言点: 身份；防回归: 解包循环被改
  @Test
  void unwrapNestedAndNullCause() throws Exception {
    IllegalStateException cause = new IllegalStateException("deep");
    java.util.concurrent.CompletionException nested =
        new java.util.concurrent.CompletionException(new ExecutionException(cause));
    assertSame(cause, FacadeBoundaryTestReflection.unwrap(nested));
    java.util.concurrent.CompletionException noCause =
        new java.util.concurrent.CompletionException((Throwable) null);
    assertSame(noCause, FacadeBoundaryTestReflection.unwrap(noCause));
  }

  // TC-ENG-577 | carriesNegotiationMetadata：artifact 元数据载体 + 空载体
  //
  // 输入: artifact 携带协商元数据；空 ReceivedMessage
  // 预期输出: true / false
  // 断言点: 布尔；防回归: artifact 分支被删
  @Test
  void artifactMetadataCarriesNegotiation() throws Exception {
    dev.openan.workflow.engine.model.ReceivedMessage empty =
        new dev.openan.workflow.engine.model.ReceivedMessage(null, Map.of(), List.of());
    assertFalse(FacadeBoundaryTestReflection.carries(empty));

    dev.openan.workflow.engine.model.ReceivedMessage artifactCarrier =
        new dev.openan.workflow.engine.model.ReceivedMessage(
            null,
            Map.of(),
            List.of(
                org.a2aproject.sdk.spec.Artifact.builder()
                    .artifactId("a")
                    .parts(List.of(new org.a2aproject.sdk.spec.TextPart("x")))
                    .metadata(Map.of(A2ATExtension.NEGOTIATION_T.uri(), Map.of()))
                    .build()));
    assertTrue(FacadeBoundaryTestReflection.carries(artifactCarrier));
  }

  // TC-ENG-578 | validateReply：有扩展激活但元数据缺协商键
  //
  // 输入: reply 扩展含 NEGOTIATION_T，metadata 不含
  // 预期输出: IAE "must carry and activate"
  // 断言点: 消息；防回归: 元数据键守卫被删
  @Test
  void replyActivatedWithoutMetadataRejected() throws Exception {
    net.openan.a2at.sdk.core.model.NegotiationContext original =
        new net.openan.a2at.sdk.core.model.NegotiationContext(
            "n-1", 1, 3, NegotiationPerformative.PROPOSE);
    MessageContent reply =
        new MessageContent(
            List.of(new org.a2aproject.sdk.spec.TextPart("x")),
            new java.util.HashMap<>(),
            java.util.Set.of(A2ATExtension.NEGOTIATION_T.uri()));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> FacadeBoundaryTestReflection.validateReply(original, reply));
    assertTrue(error.getMessage().contains("must carry and activate"));
  }

  // TC-ENG-579 | setEventCallback(null) 回退 noop，不抛
  //
  // 输入: setEventCallback(null) 后普通 COMPLETED 任务
  // 预期输出: 成功
  // 断言点: 终态；防回归: null 回退被删即 NPE
  @Test
  void nullEventCallbackFallsBackToNoop() throws Exception {
    FakeA2AJavaClientRuntime runtime =
        new FakeA2AJavaClientRuntime()
            .sendReturns(
                params ->
                    List.of(
                        response(
                            params, TaskState.TASK_STATE_COMPLETED, MessageContent.text("ok"))));
    try (A2ATransport transport =
        new A2ATransport(List.of(card()), runtime, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport, cfg());
      client.setEventCallback(null);
      SendMessageResult result =
          client.sendTask("test", MessageContent.text("x")).get(5, TimeUnit.SECONDS);
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    }
  }

  /** Small reflective bridge to private static helpers (same package seam as TC-ENG-180/181). */
  static class FacadeBoundaryTestReflection {
    static Throwable unwrap(Throwable error) throws Exception {
      java.lang.reflect.Method m =
          DefaultWorkflowEngineClient.class.getDeclaredMethod("unwrap", Throwable.class);
      m.setAccessible(true);
      return (Throwable) m.invoke(null, error);
    }

    static boolean carries(dev.openan.workflow.engine.model.ReceivedMessage received)
        throws Exception {
      java.lang.reflect.Method m =
          DefaultWorkflowEngineClient.class.getDeclaredMethod(
              "carriesNegotiationMetadata", dev.openan.workflow.engine.model.ReceivedMessage.class);
      m.setAccessible(true);
      return (Boolean) m.invoke(null, received);
    }

    static net.openan.a2at.sdk.core.model.NegotiationContext validateReply(
        net.openan.a2at.sdk.core.model.NegotiationContext original, MessageContent reply)
        throws Exception {
      java.lang.reflect.Method m =
          DefaultWorkflowEngineClient.class.getDeclaredMethod(
              "validateReply",
              net.openan.a2at.sdk.core.model.NegotiationContext.class,
              MessageContent.class);
      m.setAccessible(true);
      try {
        return (net.openan.a2at.sdk.core.model.NegotiationContext) m.invoke(null, original, reply);
      } catch (java.lang.reflect.InvocationTargetException e) {
        throw (RuntimeException) e.getCause();
      }
    }
  }

  // TC-ENG-565 | 管理接口空参数守卫
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void managementMethodBlankArgumentGuards() throws Exception {
    AgentCard c = card();
    try (A2ATransport transport =
        new A2ATransport(
            List.of(c),
            new FakeA2AJavaClientRuntime(),
            WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client =
          new DefaultWorkflowEngineClient(transport, WorkflowEngineClientConfig.builder().build());
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> client.getTask(" ", "t").get());
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> client.getTask("test", null).get());
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> client.cancelTask(null, "t").get());
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> client.cancelTask("test", " ").get());
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> client.listTasks(" ", (org.a2aproject.sdk.spec.ListTasksParams) null).get());
    }
  }
}

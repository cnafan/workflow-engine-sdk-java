/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Tests for ExecutePsop: end-to-end event flow, lifecycle, on_finish, on_event transformer, START
 * not duplicated, Builder usability.
 */
class ExecutePsopTest {

  // TC-ENG | cancellationStopsLateTaskPreparationAndClosesLifecycleOnce
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void reporterUsesRunnerIdentityAndRetriesPersistentEvents(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path spool) throws Exception {
    var received =
        new java.util.concurrent.LinkedBlockingQueue<com.fasterxml.jackson.databind.JsonNode>();
    var first = new AtomicInteger();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var http =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/events",
        exchange -> {
          try (exchange) {
            var batch = json.readTree(exchange.getRequestBody().readAllBytes());
            if (first.getAndIncrement() == 0) {
              exchange.sendResponseHeaders(503, -1);
              return;
            }
            batch.forEach(received::add);
            byte[] ack =
                json.writeValueAsBytes(
                    Map.of(
                        "execution_id",
                        batch.get(0).get("subject").asText(),
                        "accepted",
                        batch.size(),
                        "duplicates",
                        0,
                        "contiguous_through",
                        batch.get(batch.size() - 1).path("seq").asText()));
            exchange.sendResponseHeaders(200, ack.length);
            exchange.getResponseBody().write(ack);
          }
        });
    http.start();
    try (var reporter =
        new ExecutionReporter(
            java.net.URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/events"),
            "test-key",
            "urn:openan:host:java-test",
            spool)) {
      var workflow = linearWorkflow();
      workflow.setRevision("a".repeat(64));
      var run = new AtomicReference<String>();
      assertTrue(
          ExecutePsop.builder()
              .psop(workflow)
              .engineClient(new StubWorkflowEngineClient("A", "B"))
              .controlPoint(autoCp())
              .reporter(reporter)
              .eventCallback(
                  new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                      if (EventType.TASK_STATUS_CHANGED.equals(type)) {
                        run.set(data.get("executionId").toString());
                        assertNotNull(data.get("taskId"));
                      }
                    }
                  })
              .execute()
              .get(5, java.util.concurrent.TimeUnit.SECONDS)
              .isSuccess());
      var collected = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        var event = received.poll(1, java.util.concurrent.TimeUnit.SECONDS);
        if (event == null) continue;
        collected.add(event);
        if (event.get("type").asText().endsWith("execution.finished.v1")) break;
      }
      assertTrue(first.get() >= 2);
      assertTrue(reporter.awaitDelivery(java.time.Duration.ofSeconds(5)));
      assertTrue(collected.get(0).get("type").asText().endsWith("execution.started.v1"));
      assertTrue(
          collected
              .get(collected.size() - 1)
              .get("type")
              .asText()
              .endsWith("execution.finished.v1"));
      assertTrue(collected.stream().allMatch(e -> run.get().equals(e.get("subject").asText())));
      assertEquals(
          2,
          collected.stream()
              .filter(
                  e ->
                      e.get("type").asText().endsWith("task.finished.v1")
                          && e.get("data").get("task_id").isTextual())
              .count());
    } finally {
      http.stop(0);
    }
  }

  @Test
  void cancellationStopsLateTaskPreparationAndClosesLifecycleOnce() throws Exception {
    var prepared = new CompletableFuture<MessageContent>();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var finished = new java.util.concurrent.CountDownLatch(1);
    var closes = new AtomicInteger();
    List<String> observed = Collections.synchronizedList(new ArrayList<>());
    ObservationSink observation =
        (workflow, executionId, businessRef, traceparent, tracestate) ->
            new ObservationSink.Observation() {
              @Override
              public void observe(String type, Map<String, Object> data) {
                if (EventType.TASK_RESPONSE.equals(type)) {
                  observed.add("task:" + data.get("status"));
                }
              }

              @Override
              public void finish(String outcome) {
                observed.add("execution:" + outcome);
              }
            };
    var stub = new StubWorkflowEngineClient("A", "B");
    var execution =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .engineClient(stub)
            .observationSink(observation)
            .controlPoint(
                ControlPoint.builder()
                    .onTask(
                        request -> {
                          entered.countDown();
                          return prepared;
                        })
                    .build())
            .eventCallback(
                new EventCallback() {
                  @Override
                  public void onEvent(String type, Map<String, Object> data) {
                    if (EventType.CLOSE.equals(type)) closes.incrementAndGet();
                  }
                })
            .onFinish(
                (result, events) -> {
                  assertFalse(result.isSuccess());
                  assertEquals("Workflow execution cancelled", result.getError());
                  finished.countDown();
                  return CompletableFuture.completedFuture(null);
                })
            .execute();
    assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(execution.cancel(true));
    prepared.complete(MessageContent.text("must not send"));
    assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(execution.isCancelled());
    assertEquals(0, stub.getSentMessages().size());
    assertEquals(1, closes.get());
    assertEquals(List.of("task:cancelled", "execution:cancelled"), observed);
  }

  @Test
  void cancellationWaitsForTheTaskAlreadyPublishingItsResult() throws Exception {
    var content = new CompletableFuture<MessageContent>();
    var publishing = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var finished = new CompletableFuture<String>();
    List<String> taskOutcomes = Collections.synchronizedList(new ArrayList<>());
    var execution =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(ControlPoint.builder().onTask(request -> content).build())
            .observationSink(
                (workflow, id, ref, parent, state) ->
                    new ObservationSink.Observation() {
                      @Override
                      public void observe(String type, Map<String, Object> data) {
                        if (EventType.TASK_STATUS_CHANGED.equals(type)) {
                          publishing.countDown();
                          try {
                            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          }
                        }
                        if (EventType.TASK_RESPONSE.equals(type)) {
                          taskOutcomes.add(String.valueOf(data.get("status")));
                        }
                      }

                      @Override
                      public void finish(String outcome) {
                        finished.complete(outcome);
                      }
                    })
            .execute();
    var worker = CompletableFuture.runAsync(() -> content.complete(MessageContent.text("done")));
    try {
      assertTrue(publishing.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(execution.cancel(true));
      assertFalse(finished.isDone());
    } finally {
      release.countDown();
    }
    worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals("cancelled", finished.get(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(List.of("success"), taskOutcomes);
  }

  // TC-ENG | cancellationWhileOnFinishIsPendingStillClosesExactlyOnce
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancellationWhileOnFinishIsPendingStillClosesExactlyOnce() {
    var finish = new CompletableFuture<Void>();
    var closes = new AtomicInteger();
    var execution =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(autoCp())
            .eventCallback(
                new EventCallback() {
                  @Override
                  public void onEvent(String type, Map<String, Object> data) {
                    if (EventType.CLOSE.equals(type)) closes.incrementAndGet();
                  }
                })
            .onFinish((result, events) -> finish)
            .execute();
    assertTrue(execution.cancel(true));
    assertEquals(1, closes.get());
    finish.complete(null);
    assertEquals(1, closes.get());
  }

  // TC-ENG | cancelledRunnerClosesItsOwnedRuntime
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancelledRunnerClosesItsOwnedRuntime() {
    var closes = new AtomicInteger();
    var runtime =
        (dev.openan.workflow.engine.client.A2AJavaClientRuntime)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.openan.workflow.engine.client.A2AJavaClientRuntime.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) closes.incrementAndGet();
                  return null;
                });
    var execution =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .a2aClientRuntime(runtime)
            .controlPoint(
                ControlPoint.builder().onTask(request -> new CompletableFuture<>()).build())
            .execute();
    assertTrue(execution.cancel(true));
    assertEquals(1, closes.get());
  }

  private Task task(String agent, String desc) {
    return Task.builder().agent(agent).description(desc).build();
  }

  private ControlPoint autoCp() {
    return new ControlPoint() {
      @Override
      public CompletableFuture<MessageContent> onTask(TaskRequest request) {
        return CompletableFuture.completedFuture(MessageContent.text(request.getInstruction()));
      }

      @Override
      public CompletableFuture<RouteDecision> onRoute(RouteRequest routeRequest) {
        return CompletableFuture.completedFuture(RouteDecision.allow("test"));
      }
    };
  }

  private Workflow linearWorkflow() {
    WorkflowStep s1 =
        WorkflowStep.builder()
            .name("s1")
            .layer(0)
            .subtasks(List.of(task("A", "do A")))
            .next(List.of(JumpCondition.builder().step("s2").condition("").build()))
            .build();
    WorkflowStep s2 =
        WorkflowStep.builder().name("s2").layer(1).subtasks(List.of(task("B", "do B"))).build();
    return Workflow.builder().name("e2e").steps(List.of(s1, s2)).build();
  }

  // TC-ENG | fullLifecycleEventSequence
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fullLifecycleEventSequence() {
    StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    EventCallback cb =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(type);
          }
        };
    ExecutionResult result =
        ExecutePsop.execute(
                linearWorkflow(),
                List.of(),
                autoCp(),
                stub,
                "intent",
                "zh",
                null,
                false,
                null,
                null,
                cb,
                null,
                null)
            .join();
    assertTrue(result.isSuccess());
    // Verify lifecycle bracket: start ... complete ... close
    assertEquals(EventType.START, events.get(0));
    assertEquals(EventType.CLOSE, events.get(events.size() - 1));
    int startCount = Collections.frequency(events, EventType.START);
    assertEquals(1, startCount, "START must be emitted exactly once (no duplicates)");
    assertTrue(events.contains(EventType.COMPLETE));
    assertTrue(events.contains(EventType.WORKFLOW_COMPLETE));
    int closeCount = Collections.frequency(events, EventType.CLOSE);
    assertEquals(1, closeCount);
    int completeIdx = events.indexOf(EventType.COMPLETE);
    int closeIdx = events.indexOf(EventType.CLOSE);
    assertTrue(completeIdx < closeIdx);
    int startIdx = events.indexOf(EventType.START);
    assertTrue(startIdx < completeIdx);
  }

  // TC-ENG | onFinishCalledWithResultAndEvents
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onFinishCalledWithResultAndEvents() {
    StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
    AtomicInteger finishCalls = new AtomicInteger(0);
    AtomicReference<ExecutionResult> capturedResult = new AtomicReference<>();
    BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> onFinish =
        (r, e) -> {
          finishCalls.incrementAndGet();
          capturedResult.set(r);
          assertTrue(e.size() > 0, "events should be collected");
          return CompletableFuture.completedFuture(null);
        };
    ExecutionResult result =
        ExecutePsop.execute(
                linearWorkflow(),
                List.of(),
                autoCp(),
                stub,
                "intent",
                "zh",
                null,
                false,
                null,
                null,
                new EventCallback(),
                onFinish,
                null)
            .join();
    assertEquals(1, finishCalls.get());
    assertTrue(capturedResult.get().isSuccess());
  }

  // TC-ENG | onEventTransformerCanInjectEvents
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onEventTransformerCanInjectEvents() {
    StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    EventCallback cb =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(type);
          }
        };
    // Inject a "psop_update" before each step_start
    Function<Map<String, Object>, Object> onEvent =
        event -> {
          if (EventType.STEP_START.equals(event.get("type"))) {
            List<Map<String, Object>> injected = new ArrayList<>();
            injected.add(Map.of("type", "psop_update", "data", Map.of("custom", true)));
            injected.add(event);
            return injected;
          }
          return event;
        };
    ExecutePsop.execute(
            linearWorkflow(),
            List.of(),
            autoCp(),
            stub,
            "intent",
            "zh",
            null,
            false,
            null,
            null,
            cb,
            null,
            onEvent)
        .join();
    assertTrue(events.contains("psop_update"));
    int psopIdx = events.indexOf("psop_update");
    int stepStartIdx = events.indexOf(EventType.STEP_START);
    assertTrue(psopIdx >= 0);
    // psop_update should come right before the first step_start
    assertEquals(stepStartIdx - 1, psopIdx);
  }

  // TC-ENG | onEventTransformerCanFilterEvents
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onEventTransformerCanFilterEvents() {
    StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    EventCallback cb =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(type);
          }
        };
    // Filter out all task_request events
    Function<Map<String, Object>, Object> onEvent =
        event -> {
          if (EventType.TASK_REQUEST.equals(event.get("type"))) {
            return null;
          }
          return event;
        };
    ExecutePsop.execute(
            linearWorkflow(),
            List.of(),
            autoCp(),
            stub,
            "intent",
            "zh",
            null,
            false,
            null,
            null,
            cb,
            null,
            onEvent)
        .join();
    assertFalse(events.contains(EventType.TASK_REQUEST));
  }

  // TC-ENG | builderProducesSameResultAsStaticExecute
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderProducesSameResultAsStaticExecute() {
    StubWorkflowEngineClient stub1 = new StubWorkflowEngineClient("A", "B");
    StubWorkflowEngineClient stub2 = new StubWorkflowEngineClient("A", "B");
    ExecutionResult r1 =
        ExecutePsop.execute(
                linearWorkflow(),
                List.of(),
                autoCp(),
                stub1,
                "intent",
                "zh",
                null,
                false,
                null,
                null,
                new EventCallback(),
                null,
                null)
            .join();
    ExecutionResult r2 =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .controlPoint(autoCp())
            .engineClient(stub2)
            .runtimeIntent("intent")
            .lang("zh")
            .execute()
            .join();
    assertEquals(r1.isSuccess(), r2.isSuccess());
    assertEquals(stub1.getSentCount(), stub2.getSentCount());
  }

  // TC-ENG | builderRequiresPsop
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderRequiresPsop() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ExecutePsop.builder().controlPoint(autoCp()).execute());
  }

  // TC-ENG | builderRequiresControlPoint
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderRequiresControlPoint() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ExecutePsop.builder().psop(linearWorkflow()).execute());
  }

  // TC-ENG | injectedClientRemainsCallerOwnedAfterExecution
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void injectedClientRemainsCallerOwnedAfterExecution() {
    AtomicInteger closeCalls = new AtomicInteger();
    StubWorkflowEngineClient client =
        new StubWorkflowEngineClient("A", "B") {
          @Override
          public void close() {
            closeCalls.incrementAndGet();
          }
        };

    ExecutionResult result =
        ExecutePsop.builder()
            .psop(linearWorkflow())
            .controlPoint(autoCp())
            .engineClient(client)
            .execute()
            .join();

    assertTrue(result.isSuccess());
    assertEquals(0, closeCalls.get());
  }

  // TC-ENG | builderRejectsInjectedClientCombinedWithConstructionSettings
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void builderRejectsInjectedClientCombinedWithConstructionSettings() {
    StubWorkflowEngineClient client = new StubWorkflowEngineClient("A", "B");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ExecutePsop.builder()
                    .psop(linearWorkflow())
                    .controlPoint(autoCp())
                    .engineClient(client)
                    .sslVerify(false)
                    .execute());
    assertTrue(error.getMessage().contains("engineClient cannot be combined"));
  }

  // TC-ENG | errorLifecycleOnTaskFailure
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void errorLifecycleOnTaskFailure() {
    StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
    ControlPoint failCp =
        new ControlPoint() {
          @Override
          public CompletableFuture<MessageContent> onTask(TaskRequest request) {
            if (request.getAgentName().equals("A")) {
              return CompletableFuture.failedFuture(
                  new IllegalStateException("business preparation failed"));
            }
            return CompletableFuture.completedFuture(MessageContent.text(request.getInstruction()));
          }

          @Override
          public CompletableFuture<RouteDecision> onRoute(RouteRequest routeRequest) {
            return CompletableFuture.completedFuture(RouteDecision.allow());
          }
        };
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    EventCallback cb =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(type);
          }
        };
    ExecutionResult result =
        ExecutePsop.execute(
                linearWorkflow(),
                List.of(),
                failCp,
                stub,
                "intent",
                "zh",
                null,
                false,
                null,
                null,
                cb,
                null,
                null)
            .join();
    assertFalse(result.isSuccess());
    assertEquals(EventType.START, events.get(0));
    assertEquals(EventType.CLOSE, events.get(events.size() - 1));
    assertTrue(events.contains(EventType.ERROR));
    assertFalse(events.contains(EventType.COMPLETE));
  }
}

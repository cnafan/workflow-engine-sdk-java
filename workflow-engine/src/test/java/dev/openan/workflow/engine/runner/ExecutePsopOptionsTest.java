/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** TC-ENG-610…621：ExecutePsop 默认值、BiConsumer、builder 分支与反射式纯函数分支。 */
class ExecutePsopOptionsTest {

  private ControlPoint autoCp() {
    return new ControlPoint() {
      @Override
      public CompletableFuture<MessageContent> onTask(TaskRequest request) {
        return CompletableFuture.completedFuture(MessageContent.text(request.getInstruction()));
      }

      @Override
      public CompletableFuture<RouteDecision> onRoute(RouteRequest r) {
        return CompletableFuture.completedFuture(RouteDecision.allow("test"));
      }
    };
  }

  private Workflow workflow() {
    WorkflowStep s1 =
        WorkflowStep.builder()
            .name("s1")
            .subtasks(List.of(Task.builder().agent("A").description("do A").build()))
            .next(List.of(JumpCondition.builder().step("s2").condition("").build()))
            .build();
    WorkflowStep s2 =
        WorkflowStep.builder()
            .name("s2")
            .subtasks(List.of(Task.builder().agent("B").description("do B").build()))
            .build();
    return Workflow.builder().name("gap").steps(List.of(s1, s2)).build();
  }

  // TC-ENG-610 | 13 参 execute：runtimeIntent=null/lang/null 默认 ""/"zh"
  //
  // 输入: 两参传 null
  // 预期输出: 成功；TaskRequest.language=="zh"
  // 断言点: 语言；防回归: 默认值被删
  @Test
  void nullIntentAndLangUseDefaults() {
    AtomicReference<TaskRequest> captured = new AtomicReference<>();
    ControlPoint cp =
        new ControlPoint() {
          @Override
          public CompletableFuture<MessageContent> onTask(TaskRequest request) {
            captured.set(request);
            return CompletableFuture.completedFuture(MessageContent.text(request.getInstruction()));
          }

          @Override
          public CompletableFuture<RouteDecision> onRoute(RouteRequest r) {
            return CompletableFuture.completedFuture(RouteDecision.allow("test"));
          }
        };
    ExecutionResult result =
        ExecutePsop.execute(
                workflow(),
                List.of(),
                cp,
                new StubWorkflowEngineClient("A", "B"),
                null,
                null,
                null,
                false,
                null,
                null,
                new EventCallback() {},
                null,
                null)
            .join();
    assertTrue(result.isSuccess());
    assertEquals("zh", captured.get().getLanguage());
    assertEquals("", captured.get().getWorkflowInput().runtimeIntent());
  }

  // TC-ENG-611 | 10 参重载：BiConsumer onFinish 被调用
  //
  // 输入: BiConsumer 非空
  // 预期输出: 恰好 1 次，收到成功结果
  // 断言点: 次数+success；防回归: 包装 lambda 被删
  @Test
  void biConsumerOnFinishIsWrappedAndInvoked() {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<ExecutionResult> seen = new AtomicReference<>();
    ExecutionResult result =
        ExecutePsop.execute(
                workflow(),
                List.of(),
                autoCp(),
                new StubWorkflowEngineClient("A", "B"),
                "intent",
                "zh",
                null,
                new EventCallback() {},
                (r, events) -> {
                  calls.incrementAndGet();
                  seen.set(r);
                },
                null)
            .join();
    assertTrue(result.isSuccess());
    assertEquals(1, calls.get());
    assertTrue(seen.get().isSuccess());
  }

  // TC-ENG-612 | 10 参重载：null BiConsumer 不抛
  //
  // 输入: onFinish=null
  // 预期输出: 工作流成功
  // 断言点: success；防回归: null 三元分支被删
  @Test
  void nullBiConsumerOnFinishAccepted() {
    ExecutionResult result =
        ExecutePsop.execute(
                workflow(),
                List.of(),
                autoCp(),
                new StubWorkflowEngineClient("A", "B"),
                "intent",
                "zh",
                null,
                new EventCallback() {},
                null,
                null)
            .join();
    assertTrue(result.isSuccess());
  }

  // TC-ENG-613 | emitEventMap：非 Map data 回退空 Map；缺 type 用默认类型
  //
  // 输入: transformer 返回 Map("type","custom","data","scalar") 与 Map("data",Map.of())
  // 预期输出: 自定义类型事件收到空 data；默认类型事件被派发
  // 断言点: 类型与 data；防回归: 回退分支被删
  @Test
  void nonMapDataFallsBackToEmptyMap() {
    List<Map<String, Object>> delivered = new ArrayList<>();
    EventCallback recorder =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            delivered.add(Map.of("type", type, "data", data));
          }
        };
    ExecutionResult result =
        ExecutePsop.execute(
                workflow(),
                List.of(),
                autoCp(),
                new StubWorkflowEngineClient("A", "B"),
                "intent",
                "zh",
                null,
                false,
                null,
                null,
                recorder,
                null,
                event ->
                    List.of(
                        Map.of("type", "custom", "data", "scalar"), Map.of("data", Map.of("k", 1))))
            .join();
    assertTrue(result.isSuccess());
    boolean custom =
        delivered.stream()
            .anyMatch(
                item ->
                    "custom".equals(item.get("type")) && ((Map<?, ?>) item.get("data")).isEmpty());
    assertTrue(custom, "custom event with empty fallback data must be delivered");
  }

  // TC-ENG-614 | Builder.onFinish(BiConsumer) 与 onEvent 过滤
  //
  // 输入: builder 传 BiConsumer；onEvent 过滤掉 START
  // 预期输出: onFinish 1 次；START 未透传给 emitter
  // 断言点: 次数+过滤；防回归: builder 包装/过滤被删
  @Test
  void builderBiConsumerAndEventFilter() {
    AtomicInteger finishes = new AtomicInteger();
    List<String> types = new ArrayList<>();
    ExecutionResult result =
        ExecutePsop.builder()
            .psop(workflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(autoCp())
            .eventCallback(
                new EventCallback() {
                  @Override
                  public void onEvent(String type, Map<String, Object> data) {
                    types.add(type);
                  }
                })
            .onFinish(
                (java.util.function.BiConsumer<ExecutionResult, List<Map<String, Object>>>)
                    (r, e) -> finishes.incrementAndGet())
            .onEvent(event -> EventType.START.equals(event.get("type")) ? null : event)
            .execute()
            .join();
    assertTrue(result.isSuccess());
    assertEquals(1, finishes.get());
    assertTrue(!types.contains(EventType.START), "START must be filtered out");
  }

  // TC-ENG-615 | Builder.credentialsConfigPath + engineClient 互斥 → IAE
  //
  // 输入: 同时配置凭据路径与注入 client
  // 预期输出: IAE 含 "engineClient cannot be combined"
  // 断言点: 消息；防回归: 互斥守卫被删
  @Test
  void credentialsConfigPathMarksClientConstruction() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ExecutePsop.builder()
                    .psop(workflow())
                    .engineClient(new StubWorkflowEngineClient("A"))
                    .controlPoint(autoCp())
                    .credentialsConfigPath("missing.json")
                    .execute());
    assertTrue(error.getMessage().contains("engineClient cannot be combined"));
  }

  // TC-ENG-616 | Builder.onFinish(null BiConsumer) 接受
  //
  // 输入: null
  // 预期输出: 成功
  // 断言点: success；防回归: null 三元分支
  @Test
  void builderNullBiConsumerAccepted() {
    ExecutionResult result =
        ExecutePsop.builder()
            .psop(workflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(autoCp())
            .onFinish(
                (java.util.function.BiConsumer<ExecutionResult, List<Map<String, Object>>>) null)
            .execute()
            .join();
    assertTrue(result.isSuccess());
  }

  // TC-ENG-617 | handleExecutionError：null 消息非取消异常 → 类简单名
  //
  // 输入: 反射调用 handleExecutionError(new RuntimeException())
  // 预期输出: success=false, error="RuntimeException"
  // 断言点: 归一化消息；防回归: 回退被删
  @Test
  void handleExecutionErrorNullMessageUsesClassName() throws Exception {
    ExecutionResult result =
        (ExecutionResult)
            invokeStatic(
                "handleExecutionError", new Class<?>[] {Throwable.class}, new RuntimeException());
    assertTrue(!result.isSuccess());
    assertEquals("RuntimeException", result.getError());
  }

  // TC-ENG-618 | emitResultEvent：失败结果 null error → "Execution failed"
  //
  // 输入: 反射调用，failed result error=null
  // 预期输出: ERROR 事件 data.get("error") == "Execution failed"
  // 断言点: 字面量；防回归: null 回退被删
  @Test
  void emitResultEventUsesFallbackError() throws Exception {
    AtomicReference<String> type = new AtomicReference<>();
    AtomicReference<Map<String, Object>> data = new AtomicReference<>();
    EventCallback callback =
        new EventCallback() {
          @Override
          public void onEvent(String t, Map<String, Object> d) {
            type.set(t);
            data.set(d);
          }
        };
    ExecutionResult failed =
        ExecutionResult.builder()
            .success(false)
            .error(null)
            .history(List.of())
            .stepOutputs(Map.of())
            .build();
    invokeStatic(
        "emitResultEvent",
        new Class<?>[] {ExecutionResult.class, EventCallback.class},
        failed,
        callback);
    assertEquals(EventType.ERROR, type.get());
    assertEquals("Execution failed", data.get().get("error"));
  }

  // TC-ENG-619 | serialize：null / 嵌套 List / 自定义对象 toString
  //
  // 输入: 反射调用 serialize(null)、serialize(List)、serialize(new Object)
  // 预期输出: null / 结构保持 / toString
  // 断言点: 三类返回；防回归: 分支被删
  @Test
  void serializeBranches() throws Exception {
    assertEquals(
        null, invokeStatic("serialize", new Class<?>[] {Object.class}, new Object[] {null}));
    Object nested =
        invokeStatic("serialize", new Class<?>[] {Object.class}, List.of(1, "x", List.of(2)));
    assertEquals(List.of(1, "x", List.of(2)), nested);
    Object pojo =
        invokeStatic(
            "serialize",
            new Class<?>[] {Object.class},
            new Object() {
              @Override
              public String toString() {
                return "po";
              }
            });
    assertEquals("po", pojo);
  }

  // TC-ENG-620 | Builder.onEvent 经 builder 装到收集回调（非 null 路径）
  //
  // 输入: builder.onEvent 返回原事件
  // 预期输出: START 事件透传
  // 断言点: START 存在；防回归: builder onEvent 字段被忽略
  @Test
  void builderOnEventPassthrough() {
    List<String> types = new ArrayList<>();
    ExecutePsop.builder()
        .psop(workflow())
        .engineClient(new StubWorkflowEngineClient("A", "B"))
        .controlPoint(autoCp())
        .eventCallback(
            new EventCallback() {
              @Override
              public void onEvent(String type, Map<String, Object> data) {
                types.add(type);
              }
            })
        .onEvent(event -> event)
        .execute()
        .join();
    assertTrue(types.contains(EventType.START));
  }

  // TC-ENG-621 | 13 参 execute 传空 agentCards 且注入 client → 不触发自动建 client
  //
  // 输入: engineClient 非 null，agentCards 空
  // 预期输出: 成功且 CLOSE 恰好一次（onFinish 空路径）
  // 断言点: success；防回归: closeClientOnFinish 判定被改
  @Test
  void injectedClientIsNotClosedByRunner() {
    AtomicInteger closes = new AtomicInteger();
    StubWorkflowEngineClient stub =
        new StubWorkflowEngineClient("A", "B") {
          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };
    ExecutionResult result =
        ExecutePsop.execute(
                workflow(),
                List.of(),
                autoCp(),
                stub,
                "intent",
                "zh",
                null,
                true,
                null,
                null,
                new EventCallback() {},
                null,
                null)
            .join();
    assertTrue(result.isSuccess());
    assertEquals(0, closes.get());
  }

  private static Object invokeStatic(String name, Class<?>[] types, Object... args)
      throws Exception {
    java.lang.reflect.Method m = ExecutePsop.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }
}

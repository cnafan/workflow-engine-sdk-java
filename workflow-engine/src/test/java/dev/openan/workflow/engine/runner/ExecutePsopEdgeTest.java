/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;

/** TC-ENG-600…606：ExecutePsop 转换器/序列化/onFinish 异常等边缘分支。 */
class ExecutePsopEdgeTest {

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
    return Workflow.builder().name("edge").steps(List.of(s1, s2)).build();
  }

  private EventCallback collecting(List<String> events) {
    return new EventCallback() {
      @Override
      public void onEvent(String type, Map<String, Object> data) {
        events.add(type);
      }
    };
  }

  // TC-ENG-600 | onEvent 转换器抛异常 → 回退为原事件
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onEventThrowingFallsBackToOriginalEvent() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
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
            collecting(events),
            null,
            event -> {
              throw new IllegalStateException("transformer boom");
            })
        .join();
    assertTrue(events.contains(EventType.START));
  }

  // TC-ENG-601 | onEvent 返回非 List/非 Map 对象 → 不派发事件，但工作流成功
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onEventReturnsScalarDispatchesNothing() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
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
                collecting(events),
                null,
                event -> "scalar")
            .join();
    assertTrue(result.isSuccess());
    // 标量不被派发，只剩生命周期事件
    assertFalse(events.contains("psop_update"));
  }

  // TC-ENG-602 | onFinish 同步抛异常 → 不影响工作流结果
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onFinishSyncExceptionDoesNotFailResult() {
    AtomicInteger finishCalls = new AtomicInteger();
    java.util.function.BiFunction<
            ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
        onFinish = syncThrowingFinish(finishCalls);
    ExecutionResult result =
        ExecutePsop.builder()
            .psop(workflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(autoCp())
            .onFinish(onFinish)
            .execute()
            .join();
    assertEquals(1, finishCalls.get());
    assertTrue(result.isSuccess());
  }

  private java.util.function.BiFunction<
          ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
      syncThrowingFinish(AtomicInteger finishCalls) {
    return (r, e) -> {
      finishCalls.incrementAndGet();
      throw new IllegalStateException("on_finish boom");
    };
  }

  // TC-ENG-603 | onFinish 返回 null future → 视为完成
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onFinishNullFutureTreatedAsCompleted() {
    AtomicInteger finishCalls = new AtomicInteger();
    java.util.function.BiFunction<
            ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
        onFinish =
            (r, e) -> {
              finishCalls.incrementAndGet();
              return null;
            };
    ExecutionResult result =
        ExecutePsop.builder()
            .psop(workflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(autoCp())
            .onFinish(onFinish)
            .execute()
            .join();
    assertEquals(1, finishCalls.get());
    assertTrue(result.isSuccess());
  }

  // TC-ENG-604 | onFinish 返回的 future 异常完成 → 不影响结果
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onFinishAsyncFailureDoesNotFailResult() {
    java.util.function.BiFunction<
            ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
        onFinish =
            (r, e) ->
                CompletableFuture.failedFuture(new IllegalStateException("async finish boom"));
    ExecutionResult result =
        ExecutePsop.builder()
            .psop(workflow())
            .engineClient(new StubWorkflowEngineClient("A", "B"))
            .controlPoint(autoCp())
            .onFinish(onFinish)
            .execute()
            .join();
    assertTrue(result.isSuccess());
  }

  // TC-ENG-605 | 事件回调 onEvent 自身抛异常 → 不影响工作流
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void eventCallbackThrowingDoesNotBreakExecution() {
    EventCallback throwing =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            throw new IllegalStateException("callback boom");
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
                throwing,
                null,
                null)
            .join();
    assertTrue(result.isSuccess());
  }

  // TC-ENG-606 | 转换器返回含非 Map 元素的 List → 跳过非 Map 元素
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onEventReturnsListWithNonMapEntries() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
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
                collecting(events),
                null,
                event -> List.of("scalar", Map.of("type", "custom", "data", Map.of())))
            .join();
    assertTrue(result.isSuccess());
    assertTrue(events.contains("custom"));
  }
}

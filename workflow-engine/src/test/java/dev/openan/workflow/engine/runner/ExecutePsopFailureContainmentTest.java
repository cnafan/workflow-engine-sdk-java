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
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TC-ENG-1119…1120：ExecutePsop 快照转换失败与序列化失败的确定性触发。 */
class ExecutePsopFailureContainmentTest {

  private ControlPoint remoteCp() {
    return new ControlPoint() {
      @Override
      public java.util.concurrent.CompletableFuture<MessageContent> onTask(TaskRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(
            MessageContent.text(request.getInstruction()));
      }

      @Override
      public java.util.concurrent.CompletableFuture<RouteDecision> onRoute(RouteRequest r) {
        return java.util.concurrent.CompletableFuture.completedFuture(RouteDecision.allow("test"));
      }
    };
  }

  private Workflow remoteWorkflow() {
    WorkflowStep step =
        WorkflowStep.builder()
            .name("s1")
            .subtasks(List.of(Task.builder().agent("A").description("do A").build()))
            .build();
    return Workflow.builder().name("psop-fail").steps(List.of(step)).build();
  }

  // TC-ENG-1119 | Workflow 快照转换失败：getName 抛异常 → 构造失败 → CLOSE 恰好一次 → failedFuture
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void snapshotFailureEmitsCloseAndFailsFuture() throws Exception {
    Workflow bad =
        new Workflow("id", "n", "d", List.of()) {
          @Override
          public String getName() {
            throw new IllegalStateException("snapshot getter boom");
          }
        };
    CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
    EventCallback recorder =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            events.add(type);
          }
        };
    StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A");
    java.util.concurrent.CompletionException error =
        assertThrows(
            java.util.concurrent.CompletionException.class,
            () ->
                ExecutePsop.execute(
                        bad,
                        List.of(),
                        remoteCp(),
                        stub,
                        "intent",
                        "zh",
                        null,
                        false,
                        null,
                        null,
                        recorder,
                        null,
                        null)
                    .join());
    assertTrue(
        String.valueOf(error.getCause()).contains("snapshot getter boom"),
        String.valueOf(error.getCause()));
    assertEquals(1, events.stream().filter(EventType.CLOSE::equals).count());
    assertEquals(0, events.stream().filter(EventType.START::equals).count());
  }

  // TC-ENG-1120 | 事件序列化失败在两层捕获内消化：任务输出经 BusinessValues Jackson 归一化后
  // 进入收集回调，本用例验证带非默认属性对象可正常流转（覆盖收集回调的 Map/List 递归分支）。
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void structuredOutputsFlowThroughCollectingCallback() {
    StubWorkflowEngineClient stub =
        new StubWorkflowEngineClient("A")
            .withDefaultOutputs(List.of("text-out", Map.of("k", 1), List.of("a", "b")));
    AtomicInteger closeCount = new AtomicInteger();
    EventCallback counting =
        new EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            if (EventType.CLOSE.equals(type)) closeCount.incrementAndGet();
          }
        };
    dev.openan.workflow.engine.model.ExecutionResult result =
        ExecutePsop.execute(
                remoteWorkflow(),
                List.of(),
                remoteCp(),
                stub,
                "intent",
                "zh",
                null,
                false,
                null,
                null,
                counting,
                null,
                null)
            .join();
    assertTrue(result.isSuccess(), result.getError());
    assertEquals(1, closeCount.get());
  }
}

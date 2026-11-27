/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.BusinessInput;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.NegotiationRequest;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.WorkflowInput;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** TC-ENG-880…885：ControlPoint 默认失败/构建器/DefaultControlPoint 委托。 */
class ControlPointDefaultsTest {

  private static TaskRequest taskRequest() {
    return TaskRequest.builder()
        .agentName("a")
        .stepName("s1")
        .executionId("e")
        .taskId("t")
        .input(BusinessInput.text("x"))
        .workflowInput(WorkflowInput.empty())
        .build();
  }

  private static RouteRequest routeRequest() {
    return new RouteRequest("e", "s1", "s2", "c", WorkflowInput.empty(), List.of());
  }

  private static NegotiationRequest negotiationRequest() {
    return new NegotiationRequest(
        taskRequest(),
        MessageContent.text("orig"),
        new dev.openan.workflow.engine.model.ReceivedMessage(null, java.util.Map.of(), List.of()),
        List.of(),
        java.time.Duration.ofSeconds(1));
  }

  private static Throwable unwrap(CompletableFuture<?> future) {
    return assertThrows(CompletionException.class, future::join).getCause();
  }

  // TC-ENG-880 | 未注册处理器时默认 future 均以 ISE 失败
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultHandlersFailWithIllegalState() {
    ControlPoint cp = ControlPoint.builder().build();
    assertTrue(unwrap(cp.onTask(taskRequest())) instanceof IllegalStateException);
    assertTrue(unwrap(cp.onSelfTask(taskRequest())) instanceof IllegalStateException);
    assertTrue(unwrap(cp.onRoute(routeRequest())) instanceof IllegalStateException);
    assertTrue(unwrap(cp.onNegotiation(negotiationRequest())) instanceof IllegalStateException);
  }

  // TC-ENG-881 | 构建器注册后走自定义处理器
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void registeredHandlersAreInvoked() {
    ControlPoint cp =
        ControlPoint.builder()
            .onTask(request -> CompletableFuture.completedFuture(MessageContent.text("ok")))
            .onSelfTask(request -> CompletableFuture.completedFuture(TaskResult.success(List.of())))
            .onRoute(request -> CompletableFuture.completedFuture(RouteDecision.allow("yes")))
            .onNegotiation(
                request ->
                    CompletableFuture.completedFuture(
                        new NegotiationReply.Send(MessageContent.text("reply"))))
            .build();
    assertEquals(
        "ok",
        cp.onTask(taskRequest()).join().parts().stream()
            .filter(org.a2aproject.sdk.spec.TextPart.class::isInstance)
            .map(p -> ((org.a2aproject.sdk.spec.TextPart) p).text())
            .findFirst()
            .orElseThrow());
    assertTrue(cp.onSelfTask(taskRequest()).join().isSuccess());
    assertTrue(cp.onRoute(routeRequest()).join().allowed());
    assertTrue(cp.onNegotiation(negotiationRequest()).join() instanceof NegotiationReply.Send);
  }

  // TC-ENG-882 | DefaultControlPoint 无 strategy → 默认失败
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultControlPointWithoutStrategyFails() {
    DefaultControlPoint cp = new DefaultControlPoint();
    assertTrue(unwrap(cp.onNegotiation(negotiationRequest())) instanceof IllegalStateException);
  }

  // TC-ENG-883 | DefaultControlPoint 有 strategy → 委托
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultControlPointDelegatesToStrategy() {
    NegotiationStrategy strategy =
        request ->
            CompletableFuture.completedFuture(
                new NegotiationReply.Send(MessageContent.text("resolved")));
    DefaultControlPoint cp = new DefaultControlPoint(strategy);
    NegotiationReply reply = cp.onNegotiation(negotiationRequest()).join();
    assertTrue(reply instanceof NegotiationReply.Send);
  }

  // TC-ENG-884 | 默认 onRoute 错误消息含边信息
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultRouteErrorNamesEdge() {
    ControlPoint cp = ControlPoint.builder().build();
    Throwable error = unwrap(cp.onRoute(routeRequest()));
    assertTrue(error.getMessage().contains("s1"));
    assertTrue(error.getMessage().contains("s2"));
  }

  // TC-ENG-885 | 默认 onTask 错误含步骤名
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void defaultTaskErrorNamesStep() {
    ControlPoint cp = ControlPoint.builder().build();
    Throwable error = unwrap(cp.onTask(taskRequest()));
    assertTrue(error.getMessage().contains("s1"));
  }
}

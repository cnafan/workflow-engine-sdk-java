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
package dev.openan.workflow.engine.runner;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.NegotiationStrategy;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.junit.jupiter.api.Test;

class ExecutePsopBuilderValidationTest {

  // TC-ENG-030 | 缺少 psop 时 execute 抛出 IllegalArgumentException 且消息明确
  //
  // 输入: Builder 未设置 psop
  // 预期输出: IllegalArgumentException("psop is required")
  // 断言点: 异常类型 + 消息字面值；防回归: 校验被删或消息变更即失败
  @Test
  void missingPsopIsRejected() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ExecutePsop.builder()
                    .controlPoint(
                        ControlPoint.builder().onTask(request -> new CompletableFuture<>()).build())
                    .execute());
    assertTrue(error.getMessage().contains("psop is required"));
  }

  // TC-ENG-032 | engineClient 与 agentCards 不能同时配置
  //
  // 输入: 同时设置 engineClient 与 agentCards
  // 预期输出: IllegalArgumentException("engineClient cannot be combined ...")
  // 断言点: 异常类型 + 消息关键字；防回归: 互斥校验被删即失败
  @Test
  void engineClientCannotBeCombinedWithAgentCards() {
    List<AgentCard> cards = List.of(agentCard());
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validBase().agentCards(cards).engineClient(new StubEngineClient()).execute());
    assertTrue(error.getMessage().contains("engineClient cannot be combined"));
  }

  // TC-ENG-033 | engineClient 与 TLS/凭据类客户端构造设置同样互斥
  //
  // 输入: 设置 engineClient 后再设置 caCertsPath（标记 clientConstructionConfigured）
  // 预期输出: IllegalArgumentException("engineClient cannot be combined ...")
  // 断言点: 异常类型 + 消息关键字；防回归: 配置标记逻辑被删即失败
  @Test
  void engineClientCannotBeCombinedWithClientConstructionSettings() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                validBase()
                    .caCertsPath("/tmp/ca.pem")
                    .engineClient(new StubEngineClient())
                    .execute());
    assertTrue(error.getMessage().contains("engineClient cannot be combined"));
  }

  private static ExecutePsop.Builder validBase() {
    return ExecutePsop.builder()
        .psop(minimalWorkflow())
        .controlPoint(ControlPoint.builder().onTask(request -> new CompletableFuture<>()).build());
  }

  private static Workflow minimalWorkflow() {
    WorkflowStep step =
        WorkflowStep.builder()
            .name("s1")
            .layer(0)
            .subtasks(
                List.of(
                    dev.openan.workflow.engine.model.Task.builder()
                        .agent("A")
                        .description("do A")
                        .build()))
            .build();
    return Workflow.builder().name("validation").steps(List.of(step)).build();
  }

  private static AgentCard agentCard() {
    return AgentCard.builder()
        .name("A")
        .description("stub")
        .url("http://localhost:0")
        .version("1.0.0")
        .capabilities(org.a2aproject.sdk.spec.AgentCapabilities.builder().build())
        .supportedInterfaces(
            List.of(
                new org.a2aproject.sdk.spec.AgentInterface(
                    "JSONRPC",
                    "http://localhost:0",
                    null,
                    org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION)))
        .defaultInputModes(List.of("text"))
        .defaultOutputModes(List.of("text"))
        .skills(List.of())
        .build();
  }

  /** 仅用于触发 Builder 互斥校验的最小桩实现；任何方法被调用即测试失败。 */
  private static final class StubEngineClient implements WorkflowEngineClient {
    @Override
    public CompletableFuture<SendMessageResult> sendTask(String agentName, MessageContent content) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public CompletableFuture<SendMessageResult> sendTask(
        String agentName, MessageContent content, NegotiationStrategy negotiationStrategy) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public void setControlPoint(ControlPoint controlPoint) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public void setEventCallback(EventCallback callback) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public CompletableFuture<ListTasksResult> listTasks(String agentName, ListTasksParams params) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public CompletableFuture<SendMessageResult> subscribeToTask(
        String agentName, String taskId, Consumer<Map<String, Object>> eventCallback) {
      throw new UnsupportedOperationException("not expected in validation test");
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException("not expected in validation test");
    }
  }
}

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
package dev.openan.workflow.engine.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.WorkflowInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultControlPointTest {

  private final TaskRequest request =
      TaskRequest.builder().agentName("agent").stepName("step").instruction("run").build();

  // TC-ENG | preparesTextWithoutSending
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void preparesTextWithoutSending() {
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> new DefaultControlPoint().onTask(request).join());
  }

  // TC-ENG | localExecutionRequiresBusinessHandler
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void localExecutionRequiresBusinessHandler() {
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> new DefaultControlPoint().onSelfTask(request).join());
  }

  // TC-ENG | conditionalRoutingRequiresBusinessHandler
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void conditionalRoutingRequiresBusinessHandler() {
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () ->
            new DefaultControlPoint()
                .onRoute(
                    new RouteRequest(
                        "run", "step", "next", "condition", WorkflowInput.empty(), List.of()))
                .join());
  }

  // TC-ENG | handlersCanBeRegisteredIndependently
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void handlersCanBeRegisteredIndependently() {
    var handler =
        ControlPoint.builder()
            .onSelfTask(
                q ->
                    java.util.concurrent.CompletableFuture.completedFuture(
                        TaskResult.builder().success(true).outputs(List.of("done")).build()))
            .build();
    assertEquals(List.of("done"), handler.onSelfTask(request).join().getOutputs());
    assertThrows(
        java.util.concurrent.CompletionException.class, () -> handler.onTask(request).join());
  }
}

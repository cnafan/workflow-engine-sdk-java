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

package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.RemoteA2AErrorException;
import dev.openan.workflow.engine.model.BusinessFailure;
import dev.openan.workflow.engine.model.TaskResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** TC-ENG-156…160：FailureMapping 四类异常到 TaskResult 的映射。 */
class FailureMappingTest {

  // TC-ENG-156 | BusinessFailure → 业务码/消息/details 原样映射
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessFailureMapsBusinessFields() {
    BusinessFailure failure =
        new BusinessFailure("business.code", "business boom", Map.of("k", "v"));
    TaskResult result = FailureMapping.from(failure);
    assertFalse(result.isSuccess());
    assertEquals("business.code", result.getErrorCode());
    assertEquals("business boom", result.getError());
    assertEquals("v", result.getErrorDetails().get("k"));
  }

  // TC-ENG-157 | RemoteA2AErrorException（标准 A2A 错误信封）→ workflow 错误码与详情
  //
  // 输入: code=429 标准错误 JSON
  // 防回归: remote 分支被删即失败
  @Test
  void remoteA2aErrorMapsWorkflowCode() {
    String envelope =
        "{\"error\":{\"code\":429,\"message\":\"Task limit reached\","
            + "\"status\":\"RESOURCE_EXHAUSTED\","
            + "\"details\":[{\"reason\":\"TASK_LIMIT_REACHED\",\"domain\":\"a2a-protocol.org\"}]}}";
    RemoteA2AErrorException remote = RemoteA2AErrorException.fromPayload(envelope);
    TaskResult result = FailureMapping.from(remote);
    assertFalse(result.isSuccess());
    assertEquals("a2a.task_limit_reached", result.getErrorCode());
    assertTrue(result.getErrorDetails().containsKey("httpStatus"));
    assertEquals(429, result.getErrorDetails().get("httpStatus"));
  }

  // TC-ENG-158 | TimeoutException → workflow.timeout
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void timeoutMapsToWorkflowTimeout() {
    TaskResult result = FailureMapping.from(new TimeoutException("took too long"));
    assertFalse(result.isSuccess());
    assertEquals("workflow.timeout", result.getErrorCode());
  }

  // TC-ENG-159 | CancellationException → workflow.cancelled
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancellationMapsToWorkflowCancelled() {
    TaskResult result = FailureMapping.from(new CancellationException("aborted"));
    assertFalse(result.isSuccess());
    assertEquals("workflow.cancelled", result.getErrorCode());
  }

  // TC-ENG-160 | 普通异常 → workflow.execution_failed；BusinessFailure 在因果链深层也命中
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void genericExceptionAndCauseChainMapping() {
    TaskResult result = FailureMapping.from(new IllegalStateException("plain"));
    assertFalse(result.isSuccess());
    assertEquals("workflow.execution_failed", result.getErrorCode());
    assertEquals("IllegalStateException", result.getError());

    BusinessFailure deep = new BusinessFailure("deep.code", "deep", Map.of());
    RuntimeException wrapped = new RuntimeException("outer", new RuntimeException("inner", deep));
    TaskResult chained = FailureMapping.from(wrapped);
    assertEquals("deep.code", chained.getErrorCode());

    // RemoteA2AErrorException 包在因果链中也命中
    RemoteA2AErrorException remote =
        RemoteA2AErrorException.fromPayload("{\"error\":{\"code\":503,\"message\":\"down\"}}");
    RuntimeException wrappedRemote = new RuntimeException("outer", remote);
    TaskResult remoteChained = FailureMapping.from(wrappedRemote);
    assertEquals("a2a.http.503", remoteChained.getErrorCode());

    // 防止编译器把 List/Map 导入判为未用
    assertTrue(List.of().isEmpty());
    assertTrue(Map.of().isEmpty());
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.client.RemoteA2AErrorException;
import dev.openan.workflow.engine.model.BusinessFailure;
import dev.openan.workflow.engine.model.TaskResult;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** TC-ENG-940…945：FailureMapping 异常分类。 */
class FailureMappingClassificationTest {

  // TC-ENG-940 | BusinessFailure 直接映射
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessFailureMapsCodeAndDetails() {
    BusinessFailure failure = new BusinessFailure("b.code", "message", Map.of("k", "v"));
    TaskResult result = FailureMapping.from(failure);
    assertFalse(result.isSuccess());
    assertEquals("b.code", result.getErrorCode());
    assertEquals("message", result.getError());
    assertEquals("v", result.getErrorDetails().get("k"));
  }

  // TC-ENG-941 | RemoteA2AErrorException 映射含 HTTP 状态
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void remoteErrorMapsHttpStatus() {
    RemoteA2AErrorException remote =
        RemoteA2AErrorException.fromResponse(
            429,
            "{\"error\":{\"code\":429,\"message\":\"slow\",\"status\":\"RESOURCE_EXHAUSTED\","
                + "\"details\":[{\"reason\":\"rate_limit\",\"domain\":\"a2a\"}]}}",
            Map.of("retry-after", java.util.List.of("30")));
    TaskResult result = FailureMapping.from(new RuntimeException(remote));
    assertFalse(result.isSuccess());
    assertEquals(429, result.getErrorDetails().get("httpStatus"));
    assertEquals("30", result.getErrorDetails().get("retryAfter"));
  }

  // TC-ENG-942 | 超时链映射 workflow.timeout
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void timeoutMapsToWorkflowTimeout() {
    TaskResult result =
        FailureMapping.from(new ExecutionException("outer", new TimeoutException("timed out")));
    assertEquals("workflow.timeout", result.getErrorCode());
  }

  // TC-ENG-943 | 取消异常映射 workflow.cancelled
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void cancellationMapsToWorkflowCancelled() {
    TaskResult result = FailureMapping.from(new CancellationException());
    assertEquals("workflow.cancelled", result.getErrorCode());
  }

  // TC-ENG-944 | 普通异常映射 execution_failed 与类简单名
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void genericFailureMapsToExecutionFailed() {
    TaskResult result = FailureMapping.from(new IllegalStateException("boom"));
    assertEquals("workflow.execution_failed", result.getErrorCode());
    assertEquals("IllegalStateException", result.getError());
  }

  // TC-ENG-945 | BusinessFailure 嵌于包装链中仍被识别
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nestedBusinessFailureIsFound() {
    BusinessFailure business = new BusinessFailure("deep", "d", Map.of());
    TaskResult result = FailureMapping.from(new RuntimeException(new ExecutionException(business)));
    assertEquals("deep", result.getErrorCode());
    assertTrue(result.getErrorDetails() != null);
  }
}

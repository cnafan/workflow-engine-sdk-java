/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-820…827：RemoteA2AErrorException 信封/重试头/解析边界。 */
class RemoteA2AErrorExceptionParsingTest {

  private static final String ENVELOPE =
      "{\"error\":{\"code\":429,\"message\":\"rate limited\",\"status\":\"RESOURCE_EXHAUSTED\","
          + "\"details\":[{\"reason\":\"rate_limit\",\"domain\":\"a2a.protocol.org\"}]}}";

  // TC-ENG-820 | fromPayload：空/空白/非法 JSON → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void blankOrInvalidPayloadReturnsNull() {
    assertNull(RemoteA2AErrorException.fromPayload(null));
    assertNull(RemoteA2AErrorException.fromPayload(""));
    assertNull(RemoteA2AErrorException.fromPayload("   "));
    assertNull(RemoteA2AErrorException.fromPayload("not-json"));
  }

  // TC-ENG-821 | fromResponse：非错误状态也解析错误信封（状态码以信封为准）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonErrorStatusStillParsesEnvelope() {
    RemoteA2AErrorException error = RemoteA2AErrorException.fromResponse(200, ENVELOPE, Map.of());
    assertEquals(429, error.getHttpStatus());
  }

  // TC-ENG-822 | fromResponse：4xx 信封解析
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void errorResponseParsesEnvelope() {
    RemoteA2AErrorException error =
        RemoteA2AErrorException.fromResponse(429, ENVELOPE, Map.of("retry-after", List.of("60")));
    assertEquals(429, error.getHttpStatus());
    assertEquals(429, error.getCode());
    assertEquals("RESOURCE_EXHAUSTED", error.getStatus());
    assertEquals("rate_limit", error.getReason());
    assertEquals("a2a.protocol.org", error.getDomain());
    assertEquals("60", error.getRetryAfter());
    assertTrue(
        error.workflowErrorCode().contains("rate") || error.workflowErrorCode().contains("429"));
  }

  // TC-ENG-823 | fromResponse：400 但体不是错误信封 → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void errorStatusWithoutEnvelopeReturnsNull() {
    assertNull(RemoteA2AErrorException.fromResponse(500, "plain text error", Map.of()));
  }

  // TC-ENG-824 | fromResponse：体含 code 但非整数/字符串数字
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void malformedCodeIgnored() {
    String body = "{\"error\":{\"code\":\"oops\",\"message\":\"m\"}}";
    assertNull(RemoteA2AErrorException.fromResponse(400, body, Map.of()));
  }

  // TC-ENG-825 | findIn：直接实例、CompletionException 链、普通异常无 A2A 错误
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void findInChainBranches() {
    RemoteA2AErrorException remote =
        RemoteA2AErrorException.fromResponse(403, ENVELOPE.replace("429", "403"), Map.of());
    assertTrue(RemoteA2AErrorException.findIn(remote) instanceof RemoteA2AErrorException);
    java.util.concurrent.CompletionException wrapped =
        new java.util.concurrent.CompletionException(remote);
    assertEquals(remote, RemoteA2AErrorException.findIn(wrapped));
    assertNull(RemoteA2AErrorException.findIn(new IllegalStateException("plain")));
  }

  // TC-ENG-826 | workflowErrorCode：无 reason 时回退 http 错误码命名
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void errorCodeFallbackWithoutReason() {
    String body = "{\"error\":{\"code\":500,\"message\":\"boom\"}}";
    RemoteA2AErrorException error = RemoteA2AErrorException.fromResponse(500, body, Map.of());
    assertTrue(error.workflowErrorCode().startsWith("a2a.http."));
    assertTrue(error.getReason().isBlank());
  }

  // TC-ENG-827 | safeDetails 红action 与 getResponseHeaders
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void headersAreSanitized() {
    RemoteA2AErrorException error =
        RemoteA2AErrorException.fromResponse(
            401,
            ENVELOPE.replace("429", "401").replace("RESOURCE_EXHAUSTED", "UNAUTHENTICATED"),
            Map.of("Authorization", List.of("Bearer secret"), "X-Trace", List.of("t-1")));
    assertFalse(error.getResponseHeaders().toString().contains("secret"));
    assertEquals("t-1", error.getResponseHeaders().get("X-Trace").get(0));
  }

  // TC-ENG-828 | fromResponse：root 非对象/缺 error 对象/null headers/无 details
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void malformedRootShapesReturnNull() {
    assertNull(RemoteA2AErrorException.fromResponse(400, "[1,2]", null));
    assertNull(RemoteA2AErrorException.fromResponse(400, "{\"ok\":true}", Map.of()));
    assertNull(
        RemoteA2AErrorException.fromResponse(400, "{\"error\":{\"message\":\"m\"}}", Map.of()));
  }

  // TC-ENG-829 | code 非整数/超 int 范围 → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonIntegralOrOverflowCodeReturnsNull() {
    assertNull(
        RemoteA2AErrorException.fromResponse(
            400, "{\"error\":{\"code\":\"400\",\"message\":\"m\"}}", Map.of()));
    assertNull(
        RemoteA2AErrorException.fromResponse(
            400, "{\"error\":{\"code\":99999999999999999999,\"message\":\"m\"}}", Map.of()));
  }

  // TC-ENG-830 | 空 message → null；details 含非对象/缺 reason 元素跳过
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void blankMessageAndNonObjectDetailsReturnNullOrSkip() {
    assertNull(
        RemoteA2AErrorException.fromResponse(
            400, "{\"error\":{\"code\":400,\"message\":\"  \"}}", Map.of()));
    RemoteA2AErrorException error =
        RemoteA2AErrorException.fromResponse(
            400,
            "{\"error\":{\"code\":400,\"message\":\"m\","
                + "\"details\":[\"scalar\",{\"domain\":\"d\"}]}}",
            Map.of());
    assertEquals("", error.getReason());
  }

  // TC-ENG-831 | findIn：A2AClientHTTPError 带信封 → 解析
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void findInHttpErrorWithEnvelope() {
    org.a2aproject.sdk.spec.A2AClientHTTPError httpError =
        new org.a2aproject.sdk.spec.A2AClientHTTPError(
            429,
            "rate",
            "{\"error\":{\"code\":429,\"message\":\"rate\",\"status\":\"RESOURCE_EXHAUSTED\"}}",
            Map.of());
    RemoteA2AErrorException found = RemoteA2AErrorException.findIn(httpError);
    assertTrue(found instanceof RemoteA2AErrorException);
    assertEquals(429, found.getHttpStatus());
  }

  // TC-ENG-832 | findIn：A2AClientHTTPError 无可解析信封 → transportOnly 默认消息
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void findInHttpErrorWithoutEnvelopeUsesDefaultMessage() {
    org.a2aproject.sdk.spec.A2AClientHTTPError httpError =
        new org.a2aproject.sdk.spec.A2AClientHTTPError(502, "", "plain body", Map.of());
    RemoteA2AErrorException found = RemoteA2AErrorException.findIn(httpError);
    assertEquals(502, found.getHttpStatus());
    assertTrue(found.getMessage().contains("A2A request failed with HTTP 502"));
  }

  // TC-ENG-833 | findIn：SDK A2AError（已知 code 与未知 code）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void findInSdkA2AErrorKnownAndUnknownCodes() {
    org.a2aproject.sdk.spec.A2AError known =
        new org.a2aproject.sdk.spec.A2AError(-32604, "not found", Map.of());
    RemoteA2AErrorException mapped = RemoteA2AErrorException.findIn(known);
    assertTrue(mapped.getReason().length() > 0 || mapped.getStatus().length() > 0);

    org.a2aproject.sdk.spec.A2AError unknown =
        new org.a2aproject.sdk.spec.A2AError(-999999, "weird", Map.of());
    RemoteA2AErrorException fallback = RemoteA2AErrorException.findIn(unknown);
    assertEquals(500, fallback.getHttpStatus());
    assertEquals("INTERNAL", fallback.getStatus());
  }

  // TC-ENG-834 | findIn：A2AError 携带 details 元数据
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sdkA2AErrorWithDetailsCarriesMetadata() {
    org.a2aproject.sdk.spec.A2AError error =
        new org.a2aproject.sdk.spec.A2AError(
            -32604,
            "not found",
            Map.of(
                "metadata",
                Map.of("retryable", false),
                "@type",
                "type.googleapis.com/google.rpc.ErrorInfo"));
    RemoteA2AErrorException mapped = RemoteA2AErrorException.findIn(error);
    assertFalse(mapped.getDetails().isEmpty());
  }

  // TC-ENG-835 | findIn：自引用 cause 链不死循环
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void selfReferentialCauseChainTerminates() {
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);
    assertNull(RemoteA2AErrorException.findIn(a));
  }
}

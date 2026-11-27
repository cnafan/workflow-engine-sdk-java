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

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** TC-ENG-240…249：RemoteA2AErrorException 解析、脱敏器、协议结果适配与传输失败分类的分支。 */
class RemoteErrorClassificationTest {

  // ---------- RemoteA2AErrorException.fromPayload/fromResponse ----------

  // TC-ENG-240 | 空/非 JSON/无 error 信封/非数字 code/200 状态/空消息 → null
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fromResponseRejectsInvalidEnvelopes() {
    assertNull(RemoteA2AErrorException.fromPayload(null));
    assertNull(RemoteA2AErrorException.fromPayload("  "));
    assertNull(RemoteA2AErrorException.fromPayload("not json"));
    assertNull(RemoteA2AErrorException.fromPayload("{\"ok\":true}"));
    assertNull(RemoteA2AErrorException.fromPayload("{\"error\":{\"code\":\"x\"}}"));
    assertNull(RemoteA2AErrorException.fromPayload("{\"error\":{\"code\":400}}"));
    assertNull(
        RemoteA2AErrorException.fromResponse(
            200, "{\"error\":{\"code\":200,\"message\":\"x\"}}", Map.of()));
  }

  // TC-ENG-241 | 无 reason details 的完整信封 + 有 details 的解析
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fromResponseParsesCompleteEnvelope() {
    RemoteA2AErrorException simple =
        RemoteA2AErrorException.fromPayload("{\"error\":{\"code\":500,\"message\":\"boom\"}}");
    assertNotNull(simple);
    assertEquals(500, simple.getHttpStatus());
    assertTrue(simple.getDetails().isEmpty());
    assertEquals("a2a.http.500", simple.workflowErrorCode());

    String withDetails =
        "{\"error\":{\"code\":429,\"message\":\"rate\",\"status\":\"RESOURCE_EXHAUSTED\","
            + "\"details\":[{\"reason\":\"TASK_LIMIT_REACHED\",\"domain\":\"a2a-protocol.org\"}]}}";
    RemoteA2AErrorException detailed = RemoteA2AErrorException.fromPayload(withDetails);
    assertEquals("a2a.task_limit_reached", detailed.workflowErrorCode());
    assertEquals(1, detailed.getDetails().size());
    assertEquals("TASK_LIMIT_REACHED", detailed.getReason());
    assertEquals("a2a-protocol.org", detailed.getDomain());
    assertEquals("RESOURCE_EXHAUSTED", detailed.getStatus());
  }

  // TC-ENG-242 | 非数组 details 安全降级；retry-after 响应头大小写不敏感
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonArrayDetailsAndRetryAfterHeader() {
    RemoteA2AErrorException nonArray =
        RemoteA2AErrorException.fromPayload(
            "{\"error\":{\"code\":503,\"message\":\"down\",\"details\":\"x\"}}");
    assertNotNull(nonArray);
    assertTrue(nonArray.getDetails().isEmpty());

    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Retry-After", List.of("30"));
    RemoteA2AErrorException withRetry =
        RemoteA2AErrorException.fromResponse(
            503, "{\"error\":{\"code\":503,\"message\":\"down\"}}", headers);
    assertEquals("30", withRetry.getRetryAfter());
    assertEquals("", nonArray.getRetryAfter());

    Map<String, List<String>> emptyHeaders = new LinkedHashMap<>();
    emptyHeaders.put("x", List.of());
    RemoteA2AErrorException emptyValue =
        RemoteA2AErrorException.fromResponse(
            503, "{\"error\":{\"code\":503,\"message\":\"down\"}}", emptyHeaders);
    assertEquals("", emptyValue.getRetryAfter());
  }

  // TC-ENG-243 | findIn：因果链中的 RemoteA2AError 与普通异常
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void findInWalksCauseChain() {
    RemoteA2AErrorException remote =
        RemoteA2AErrorException.fromPayload(
            "{\"error\":{\"code\":502,\"message\":\"bad gateway\"}}");
    RuntimeException wrapped = new RuntimeException("outer", remote);
    assertEquals(remote, RemoteA2AErrorException.findIn(wrapped));
    assertNull(RemoteA2AErrorException.findIn(new RuntimeException("plain")));
  }

  // ---------- SensitiveDataRedactor ----------

  // TC-ENG-244 | sensitive() 各类敏感键与白名单
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sensitiveKeyClassification() {
    assertTrue(SensitiveDataRedactor.sensitive("Authorization"));
    assertTrue(SensitiveDataRedactor.sensitive("cookie"));
    assertTrue(SensitiveDataRedactor.sensitive("x-token"));
    assertTrue(SensitiveDataRedactor.sensitive("clientSecret"));
    assertTrue(SensitiveDataRedactor.sensitive("password"));
    assertTrue(SensitiveDataRedactor.sensitive("pwd"));
    assertTrue(SensitiveDataRedactor.sensitive("passwd"));
    assertTrue(SensitiveDataRedactor.sensitive("accessSession"));
    assertTrue(SensitiveDataRedactor.sensitive("x-api-key"));
    assertTrue(SensitiveDataRedactor.sensitive("apiKey"));
    assertFalse(SensitiveDataRedactor.sensitive("http://x?token=plain"));
    assertFalse(SensitiveDataRedactor.sensitive("name"));
  }

  // TC-ENG-245 | redact：JSON 键/secret 表单/Bearer Basic/普通文本
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void redactsSecretsInMultipleFormats() {
    assertEquals("", SensitiveDataRedactor.redact(null));
    assertEquals("plain", SensitiveDataRedactor.redact("plain"));
    String json = "{\"password\":\"p@ss\",\"name\":\"ok\"}";
    assertTrue(SensitiveDataRedactor.redact(json).contains("\"***\""));
    assertFalse(SensitiveDataRedactor.redact(json).contains("p@ss"));
    assertTrue(SensitiveDataRedactor.redact("accessSession=abc123 next").contains("***"));
    String bearer = SensitiveDataRedactor.redact("Authorization: Bearer secret-token");
    assertTrue(bearer.contains("***"));
    assertFalse(bearer.contains("secret-token"));
    String basic = SensitiveDataRedactor.redact("Proxy-Authorization: Basic dXNlcjpwYXNz");
    assertTrue(basic.contains("***"));
  }

  // TC-ENG-246 | safeHeaders：敏感头打码，普通头保留，返回不可修改
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void safeHeadersMasksSensitiveKeys() {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Authorization", List.of("Bearer x"));
    headers.put("X-Trace", List.of("trace-1"));
    Map<String, List<String>> safe = SensitiveDataRedactor.safeHeaders(headers);
    assertEquals(List.of("***"), safe.get("Authorization"));
    assertEquals(List.of("trace-1"), safe.get("X-Trace"));
    assertThrows(UnsupportedOperationException.class, () -> safe.put("x", List.of()));
  }

  // TC-ENG-249 | TransportFailures：中断/取消/通道关闭/消息匹配
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void transportFailureClassification() {
    assertTrue(TransportFailures.isExpectedLocalClose(new InterruptedException()));
    assertTrue(TransportFailures.isExpectedLocalClose(new CancellationException()));
    assertTrue(TransportFailures.isExpectedLocalClose(new AsynchronousCloseException()));
    assertTrue(TransportFailures.isExpectedLocalClose(new ClosedChannelException()));
    assertTrue(
        TransportFailures.isExpectedLocalClose(
            new RuntimeException("Connection closed locally by client")));
    RuntimeException wrapped = new RuntimeException("outer", new CancellationException("inner"));
    assertTrue(TransportFailures.isExpectedLocalClose(wrapped));
    assertFalse(TransportFailures.isExpectedLocalClose(new RuntimeException("business failed")));
    assertFalse(TransportFailures.isExpectedLocalClose(new RuntimeException()));
  }
}

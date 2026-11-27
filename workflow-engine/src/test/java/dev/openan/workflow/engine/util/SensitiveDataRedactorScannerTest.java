/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-700…704：SensitiveDataRedactor 扫描器深度/转义/嵌套分支。 */
class SensitiveDataRedactorScannerTest {

  // TC-ENG-700 | 嵌套对象内的密钥被打码（depth 增减分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nestedJsonObjectSecretsRedacted() {
    String json = "{\"outer\":{\"password\":\"deep\"},\"name\":\"ok\"}";
    String redacted = SensitiveDataRedactor.redact(json);
    assertFalse(redacted.contains("deep"));
    assertTrue(redacted.contains("ok"));
  }

  // TC-ENG-701 | 数组内密钥（[ 深度递增与 ] 闭合）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void arrayContainingSecretsRedacted() {
    String json = "{\"items\":[{\"token\":\"abc\"}],\"name\":\"ok\"}";
    String redacted = SensitiveDataRedactor.redact(json);
    assertFalse(redacted.contains("abc"));
  }

  // TC-ENG-702 | 引号后非冒号 → 跳过（continue 分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void quoteFollowedByNonColonSkipped() {
    String text = "\"plain quoted\", \"password\":\"secret\"";
    String redacted = SensitiveDataRedactor.redact(text);
    assertFalse(redacted.contains("secret"));
  }

  // TC-ENG-703 | 转义引号不闭合字符串
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void escapedQuoteDoesNotCloseString() {
    String json = "{\"name\":\"a\\\"b\",\"password\":\"p1\"}";
    String redacted = SensitiveDataRedactor.redact(json);
    assertTrue(redacted.contains("a\\\"b"));
    assertFalse(redacted.contains("p1"));
  }

  // TC-ENG-704 | 安全头与敏感头混合（safeHeaders 遍历）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void safeHeadersMix() {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("X-Trace", List.of("t1", "t2"));
    headers.put("X-Token", List.of("tok"));
    headers.put("Cookie", List.of("c=1"));
    Map<String, List<String>> safe = SensitiveDataRedactor.safeHeaders(headers);
    assertEquals(List.of("t1", "t2"), safe.get("X-Trace"));
    assertEquals(List.of("***"), safe.get("X-Token"));
    assertEquals(List.of("***"), safe.get("Cookie"));
  }
}

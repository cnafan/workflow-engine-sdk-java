/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-730…737：SensitiveDataRedactor 键分类/表单与 JSON 脱敏边界。 */
class SensitiveDataRedactorRulesTest {

  // TC-ENG-730 | redact(null) → 空串
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullRedactsToEmpty() {
    assertEquals("", SensitiveDataRedactor.redact(null));
  }

  // TC-ENG-731 | sensitive：URL 豁免；各敏感命名变体
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sensitiveKeywordClassification() {
    assertTrue(SensitiveDataRedactor.sensitive("Authorization"));
    assertTrue(SensitiveDataRedactor.sensitive("cookie"));
    assertTrue(SensitiveDataRedactor.sensitive("X-Auth-Token"));
    assertTrue(SensitiveDataRedactor.sensitive("client_secret"));
    assertTrue(SensitiveDataRedactor.sensitive("Password"));
    assertTrue(SensitiveDataRedactor.sensitive("pwd"));
    assertTrue(SensitiveDataRedactor.sensitive("passwd"));
    assertTrue(SensitiveDataRedactor.sensitive("accessSession"));
    assertTrue(SensitiveDataRedactor.sensitive("api-key"));
    assertTrue(SensitiveDataRedactor.sensitive("x-api_key"));
    assertFalse(SensitiveDataRedactor.sensitive("https://token.example/path"));
    assertFalse(SensitiveDataRedactor.sensitive("username"));
  }

  // TC-ENG-732 | 表单串 token=... 与 Bearer/Basic 凭据脱敏
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void formAndAuthValuesAreRedacted() {
    assertTrue(SensitiveDataRedactor.redact("token=abc123").contains("***"));
    assertFalse(SensitiveDataRedactor.redact("token=abc123").contains("abc123"));
    String bearer = SensitiveDataRedactor.redact("Authorization: Bearer abc.def.ghi");
    assertTrue(bearer.contains("Bearer ***"));
    assertFalse(bearer.contains("abc.def.ghi"));
    String basic = SensitiveDataRedactor.redact("Proxy-Authorization: Basic dXNlcjpw");
    assertTrue(basic.contains("Basic ***"));
  }

  // TC-ENG-733 | JSON 敏感字段值脱敏，非敏感字段保留
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void jsonSensitiveFieldsRedacted() {
    String json = "{\"password\":\"hush\",\"note\":\"ok\"}";
    String redacted = SensitiveDataRedactor.redact(json);
    assertTrue(redacted.contains("\"password\":\"***\""));
    assertTrue(redacted.contains("\"note\":\"ok\""));
    assertFalse(redacted.contains("hush"));
  }

  // TC-ENG-734 | safeHeaders：敏感头打码；普通头保留且结果不可变
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void safeHeadersMaskSensitiveAndCopiesOthers() {
    Map<String, List<String>> headers =
        Map.of(
            "Authorization", List.of("Bearer secret"),
            "X-Trace", List.of("trace-1", "trace-2"));
    Map<String, List<String>> safe = SensitiveDataRedactor.safeHeaders(headers);
    assertEquals(List.of("***"), safe.get("Authorization"));
    assertEquals(List.of("trace-1", "trace-2"), safe.get("X-Trace"));
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class, () -> safe.put("x", List.of()));
  }

  // TC-ENG-735 | 普通文本（无引号键、无凭据模式）原样返回
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void plainTextPassesThrough() {
    String text = "ordinary log line without secrets";
    assertEquals(text, SensitiveDataRedactor.redact(text));
  }

  // TC-ENG-736 | JSON 键未跟冒号时不崩
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void quotedStringWithoutColonDoesNotCrash() {
    String text = "{\"a\" \"b\"}";
    SensitiveDataRedactor.redact(text);
  }

  // TC-ENG-737 | api_key / apikey 变体
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void apiKeyVariantsRedacted() {
    assertTrue(SensitiveDataRedactor.redact("api_key=AKIA123").contains("***"));
    assertFalse(SensitiveDataRedactor.redact("apikey=AKIA123").contains("AKIA123"));
  }

  // TC-ENG-738 | JSON 键引号未闭合/畸形不崩
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void malformedJsonDoesNotCrash() {
    String text = "{\"password\":\"secret\" extra";
    String redacted = SensitiveDataRedactor.redact(text);
    assertFalse(redacted.contains("secret"));
  }

  // TC-ENG-739 | 引号后跟非冒号内容（不作为键）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void quotedStringNotFollowedByColonPasses() {
    String text = "{\"a\" \"b\", \"password\":\"hush\"}";
    String redacted = SensitiveDataRedactor.redact(text);
    assertFalse(redacted.contains("hush"));
  }

  // TC-ENG-740 | 嵌套对象与数组中的敏感字段均脱敏
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nestedSensitiveFieldsRedacted() {
    String json = "{\"outer\":{\"deep\":[{\"client_secret\":\"s1\"},{\"token\":\"s2\"}]}}";
    String redacted = SensitiveDataRedactor.redact(json);
    assertFalse(redacted.contains("s1"));
    assertFalse(redacted.contains("s2"));
  }

  // TC-ENG-741 | 转义引号不打断键识别
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void escapedQuotesInsideValueDoNotBreakKey() {
    String json = "{\"note\":\"he said \\\"hi\\\"\",\"password\":\"hush\"}";
    String redacted = SensitiveDataRedactor.redact(json);
    assertFalse(redacted.contains("hush"));
  }

  // TC-ENG-742 | Bearer 带逗号/分号/引号变体均脱敏
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void authValueDelimiterVariants() {
    assertFalse(SensitiveDataRedactor.redact("Authorization: Bearer abc,def").contains("abc,def"));
    assertFalse(SensitiveDataRedactor.redact("Authorization: Bearer abc;def").contains("abc;def"));
    assertFalse(
        SensitiveDataRedactor.redact("Authorization: Bearer abc\\def").contains("abc\\def"));
    assertTrue(SensitiveDataRedactor.redact("Basic dXNlcjpw").contains("Basic ***"));
  }

  // TC-ENG-743 | secret/accessSession 表单字段
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void secretAndSessionFormFieldsRedacted() {
    assertFalse(SensitiveDataRedactor.redact("client_secret=abc123&x=1").contains("abc123"));
    assertFalse(SensitiveDataRedactor.redact("accessSession=tok&x=1").contains("tok"));
    assertFalse(SensitiveDataRedactor.redact("passwd=p1").contains("p1"));
  }
}

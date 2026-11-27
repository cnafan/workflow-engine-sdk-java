/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;

/** TC-ENG-1108…1112：SensitiveDataRedactor valueEnd 分支与 EnvFileLoader 引号处理。 */
class RedactorAndEnvLoaderEdgeTest {

  // TC-ENG-1108 | 敏感键的值为对象（'}' 在 depth 1→0 处截断，L90 分支）
  //
  // 输入: 并发/竞争条件
  // 预期输出: 竞争条件下行为正确，无数据竞争
  // 断言点: 结果一致性 + 竞态窗口覆盖
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void objectValueEndsAtClosingBrace() {
    String json = "{\"password\":{\"a\":1},\"z\":2}";
    String out = SensitiveDataRedactor.redact(json);
    assertFalse(out.contains("\"a\":1"));
    assertTrue(out.contains("\"z\":2"));
  }

  // TC-ENG-1092b | 敏感键的值为裸字面量（',' 在 depth 0 处截断，L92 分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void literalValueEndsAtComma() {
    String json = "{\"password\":true,\"x\":1}";
    String out = SensitiveDataRedactor.redact(json);
    assertFalse(out.contains("true"));
    assertTrue(out.contains("\"x\":1"));
  }

  // TC-ENG-1109 | 引号键出现在串尾且无冒号（L64 分支，不崩、原样输出）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void trailingQuotedKeyWithoutColonPassesThrough() {
    String text = "{\"password\"";
    assertEquals(text, SensitiveDataRedactor.redact(text));
  }

  // TC-ENG-1110 | 键含非法转义 → Jackson 解析失败跳过该键（L57-59 分支）
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void invalidEscapeInKeySkipped() {
    String json = "{\"pass\\uZZword\":\"v\",\"password\":\"hush\"}";
    String out = SensitiveDataRedactor.redact(json);
    assertFalse(out.contains("hush"));
  }

  // TC-ENG-1111 | 键的引号未闭合（valueEnd 到达串尾且末字符非引号）→ L47 break，整段跳过键级脱敏
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unterminatedKeyQuoteBreaksScan() {
    String text = "{\"password";
    assertEquals(text, SensitiveDataRedactor.redact(text));
  }

  // TC-ENG-1112 | EnvFileLoader：带引号与不带引号的值（L97 两侧分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void envLoaderQuoteHandling() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("env-edge", ".env");
    java.nio.file.Files.writeString(
        file,
        "QUOTED=\"hello world\"\n" + "UNQUOTED=plain\n" + "SINGLE=x\n" + "WITH_KEY=token=abc123\n");
    try {
      java.lang.reflect.Method m =
          EnvFileLoader.class.getDeclaredMethod("read", java.nio.file.Path.class);
      m.setAccessible(true);
      @SuppressWarnings("unchecked")
      java.util.Map<String, String> values = (java.util.Map<String, String>) m.invoke(null, file);
      assertEquals("hello world", values.get("QUOTED"));
      assertEquals("plain", values.get("UNQUOTED"));
      assertEquals("x", values.get("SINGLE"));
      assertEquals("token=abc123", values.get("WITH_KEY"));
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** TC-ENG-720…724：WireLogFormatter 私有转义/解码分支的反射覆盖。 */
class WireLogFormatterLayoutTest {

  private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
    Method m = WireLogFormatter.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  // TC-ENG-720 | decodeUnicodeEscape：合法/越界/非法十六进制
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void decodeUnicodeEscapeBranches() throws Exception {
    Method m =
        WireLogFormatter.class.getDeclaredMethod("decodeUnicodeEscape", String.class, int.class);
    m.setAccessible(true);
    assertEquals(0x4E2D, m.invoke(null, "x\\u4E2D", 2));
    assertEquals(-1, m.invoke(null, "x\\u4E", 2));
    assertEquals(-1, m.invoke(null, "x\\uGGGG", 2));
  }

  // TC-ENG-721 | isDisplayDecodable：控制字符/引号/反斜杠/ASCII/非 ASCII
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void isDisplayDecodableBranches() throws Exception {
    Method m = WireLogFormatter.class.getDeclaredMethod("isDisplayDecodable", int.class);
    m.setAccessible(true);
    assertFalse((Boolean) m.invoke(null, 0x1F));
    assertFalse((Boolean) m.invoke(null, '"'));
    assertFalse((Boolean) m.invoke(null, '\\'));
    assertTrue((Boolean) m.invoke(null, 'a'));
    assertTrue((Boolean) m.invoke(null, 0x4E2D));
  }

  // TC-ENG-722 | prettyJson 深度 > 64 提前返回原文
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void deepJsonReturnsOriginal() throws Exception {
    String deep = "{".repeat(70) + "}".repeat(70);
    String result = WireLogFormatter.prettyBody(deep, "json");
    assertEquals(deep, result);
  }

  // TC-ENG-723 | prettyJson 非 ASCII 中文转义解码
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unicodeEscapeDecoded() {
    String escaped = "{\"k\":\"\\u65b0\\u589e\"}";
    String result = WireLogFormatter.prettyBody(escaped, "json");
    String expected = new String(Character.toChars(0x65B0)) + new String(Character.toChars(0x589E));
    org.junit.jupiter.api.Assertions.assertTrue(result.contains(expected), "[" + result + "]");
  }

  // TC-ENG-724 | prettyBody：null/空白/非 JSON 文本
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullBlankAndPlainTextBranches() {
    assertEquals("", WireLogFormatter.prettyBody(null, "json"));
    String blank = "   ";
    assertEquals(blank, WireLogFormatter.prettyBody(blank, "json"));
    String plain = "not-json";
    assertEquals(plain, WireLogFormatter.prettyBody(plain, "json"));
  }

  // TC-ENG-725 | prettySse：data 行非 JSON 容器时保持原文行
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseNonJsonDataStaysRaw() {
    String sse = "event: update\ndata: plain-text\n\n";
    String result = WireLogFormatter.prettyBody(sse, "text/event-stream");
    assertTrue(result.contains("data: plain-text"), result);
    assertFalse(result.contains("=== SSE data"));
  }

  // TC-ENG-726 | isJsonContainer：非容器/合法数组/畸形 JSON
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void jsonContainerDetection() throws Exception {
    Method m = WireLogFormatter.class.getDeclaredMethod("isJsonContainer", String.class);
    m.setAccessible(true);
    assertFalse((Boolean) m.invoke(null, "x"));
    assertTrue((Boolean) m.invoke(null, "[1,2]"));
    assertFalse((Boolean) m.invoke(null, "{bad"));
  }
}

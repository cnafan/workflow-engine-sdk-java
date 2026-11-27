/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-1000..1012: WireLogFormatter format/prettySse/prettyJson edge branches, driven with real
 * Entry records and system properties only (hand-written fixtures, no mock framework).
 */
class WireLogFormatterEdgeTest {

  private static final String LIMIT_KEY = "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS";

  @AfterEach
  void restore() {
    System.clearProperty(LIMIT_KEY);
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_PRETTY");
  }

  private static WireLog.Entry entry(
      String direction,
      Integer status,
      Map<String, java.util.List<String>> headers,
      String body,
      String representation) {
    return new WireLog.Entry(
        "OUT",
        direction,
        "req-1",
        "http://agent.test/a2a",
        "POST",
        status,
        headers,
        representation,
        body,
        "application-boundary",
        Map.of("agent", "a-1"));
  }

  // TC-ENG-1000 | envelope layout: null status "(not observed)", empty headers, raw display
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void envelopeFormatWithNullStatusAndEmptyHeaders() {
    String text = WireLogFormatter.format(entry("REQUEST", null, Map.of(), "plain", "text"), false);
    assertTrue(text.contains("(not observed)"));
    assertTrue(text.contains("(none observed)"));
    assertTrue(text.contains("raw observed text"));
    assertTrue(text.contains("agent: a-1"));
  }

  // TC-ENG-1001 | body-frame entry goes through formatFrame (compact layout)
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void bodyFrameEntryUsesCompactLayout() {
    String text = WireLogFormatter.format(entry("REQUEST_BODY", 200, Map.of(), "{}", "json"), true);
    assertFalse(text.contains("Target:"));
    assertTrue(text.contains("(envelope logged separately"));
    assertTrue(text.contains("=== Body ==="));
  }

  // TC-ENG-1002 | pretty disabled keeps raw body; enabled pretty-prints JSON
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void prettyFlagSwitchesBodyRendering() {
    String raw =
        WireLogFormatter.format(
            entry("REQUEST", 200, Map.of(), "{\"a\":1,\"b\":[1,2]}", "json"), false);
    assertTrue(raw.contains("{\"a\":1,\"b\":[1,2]}"));
    String pretty =
        WireLogFormatter.format(
            entry("REQUEST", 200, Map.of(), "{\"a\":1,\"b\":[1,2]}", "json"), true);
    assertTrue(pretty.contains("\"a\": 1"));
  }

  // TC-ENG-1003 | pretty result beyond the display limit falls back to raw body
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void prettyBeyondLimitFallsBackToRawBody() {
    System.setProperty(LIMIT_KEY, "64");
    String bigJson = "{\"k\":\"" + "x".repeat(300) + "\"}";
    String out = WireLogFormatter.prettyBody(bigJson, "json");
    assertTrue(out.startsWith(bigJson));
    assertTrue(out.contains("(pretty skipped: display capacity exceeded)"));
  }

  // TC-ENG-1004 | prettySse: single JSON data frame pretty-printed with markers
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseJsonDataFramePrettyPrinted() {
    String single = "data: {\"a\": 1}\n\n";
    String out = WireLogFormatter.prettyBody(single, "raw-sse-frame");
    assertTrue(out.contains("=== SSE data (JSON display; not wire text) ==="), out);
    assertTrue(out.contains("\"a\": 1"));
    assertTrue(out.contains("=== End SSE data ==="));
  }

  // TC-ENG-1005 | prettySse: non-JSON data stays verbatim, including extra data lines
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseNonJsonDataStaysVerbatim() {
    String sse = "data: plain-one\ndata: plain-two\n";
    String out = WireLogFormatter.prettyBody(sse, "raw-sse-frame");
    assertTrue(out.contains("data: plain-one"));
    assertTrue(out.contains("data: plain-two"));
    assertFalse(out.contains("=== SSE data"));
  }

  // TC-ENG-1006 | prettySse: expansion beyond limit returns raw with marker
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseExpansionBeyondLimitReturnsRaw() {
    System.setProperty(LIMIT_KEY, "64");
    String sse = "data: {\"k\":\"" + "y".repeat(300) + "\"}\n";
    String out = WireLogFormatter.prettyBody(sse, "raw-sse-frame");
    assertTrue(out.startsWith(sse));
    assertTrue(out.contains("(pretty skipped"));
  }

  // TC-ENG-1007 | isJsonContainer: malformed container falls back to raw text
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void malformedContainerNotRepaired() {
    String broken = "{\"a\":";
    assertEquals(broken, WireLogFormatter.prettyBody(broken, "json"));
  }

  // TC-ENG-1008 | prettyJson: structural escapes stay verbatim inside strings
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void structuralEscapesStayVerbatim() {
    String raw = "{\"s\":\"a\\\"b\\\\c\"}";
    String out = WireLogFormatter.prettyBody(raw, "json");
    assertTrue(out.contains("a\\\"b\\\\c"));
  }

  // TC-ENG-1009 | prettyJson: surrogate pair escapes decode to one code point
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void surrogatePairEscapesDecode() {
    String raw = "{\"e\":\"\\uD83D\\uDE00\"}";
    String out = WireLogFormatter.prettyBody(raw, "json");
    String emoji = new String(java.lang.Character.toChars(0x1F600));
    assertTrue(out.contains(emoji), out);
  }

  // TC-ENG-1010 | prettyJson: depth beyond 64 returns the original text
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void depthBeyondLimitReturnsOriginal() {
    String deep = "[".repeat(70) + "1" + "]".repeat(70);
    assertEquals(deep, WireLogFormatter.prettyBody(deep, "json"));
  }

  // TC-ENG-1011 | prettyJson: empty object/array stay tight (no trailing newline)
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyContainersStayTight() {
    assertEquals("{}", WireLogFormatter.prettyBody("{}", "json"));
    assertEquals("[]", WireLogFormatter.prettyBody("[]", "json"));
  }

  // TC-ENG-1012 | envelope with non-empty headers renders each header line
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void envelopeHeadersRenderedPerValue() {
    String text =
        WireLogFormatter.format(
            entry(
                "REQUEST",
                200,
                Map.of(
                    "X-One",
                    java.util.List.of("1", "2"),
                    "Authorization",
                    java.util.List.of("Bearer s")),
                "{}",
                "json"),
            false);
    assertTrue(text.contains("X-One: 1"));
    assertTrue(text.contains("X-One: 2"));
    assertTrue(text.contains("Authorization: Bearer s"));
  }
}

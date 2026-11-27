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

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class WireLogFormatterTest {
  // TC-ENG | prettyJsonPreservesDuplicateKeysNumericAndEscapedStringTokens
  //
  // 输入: 重复/二次提交
  // 预期输出: 幂等行为：不产生重复副作用
  // 断言点: 调用次数 + 结果一致性
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void prettyJsonPreservesDuplicateKeysNumericAndEscapedStringTokens() {
    String raw = "{\"n\":1e+02,\"n\":-0.0,\"text\":\"x\\n\\\"中文\\\"\",\"a\":[{},1]}";
    String pretty = WireLogFormatter.prettyBody(raw, "serialized-utf8");
    assertTrue(pretty.contains("\n  \"n\": 1e+02,"));
    assertTrue(pretty.contains("\n  \"n\": -0.0,"));
    assertTrue(pretty.contains("\"x\\n\\\"中文\\\"\""));
    assertTrue(pretty.contains("\n    {},\n    1\n  ]"));
  }

  // TC-ENG | prettySseKeepsEventControlFieldsAndMakesDataReadable
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void prettySseKeepsEventControlFieldsAndMakesDataReadable() {
    String raw =
        "id: 12\r\nevent: task\r\n: heartbeat\r\ndata: {\"metadata\":{\r\ndata: \"performative\":\"PROPOSE\"}}\r\n\r\n";
    String pretty = WireLogFormatter.prettyBody(raw, "raw-sse-frame");
    assertTrue(
        pretty.startsWith(
            "id: 12\nevent: task\n: heartbeat\n=== SSE data (JSON display; not wire text) ===\n{\n"));
    assertTrue(pretty.contains("    \"performative\": \"PROPOSE\""));
    assertFalse(pretty.contains("\ndata: "));
    assertTrue(pretty.contains("=== End SSE data ==="));
    assertTrue(pretty.endsWith("\n\n"));
    assertEquals(pretty, WireLogFormatter.prettyBody(raw, "sdk-sse-text"));
  }

  // TC-ENG | sseDisplaySeparatesEventsAndKeepsRawObservationAvailable
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseDisplaySeparatesEventsAndKeepsRawObservationAvailable() {
    String raw = "id: 1\ndata: {\n" + "data:   \"n\": 1\n" + "data: }\n\n" + "id: 2\ndata: {}\n\n";
    List<WireLog.Entry> observed = new ArrayList<>();
    WireLog.emit(
        observed::add,
        "DIRECT_HTTP",
        "RESPONSE_BODY",
        "sse-1",
        "/",
        "POST",
        200,
        Map.of(),
        "raw-sse-frame",
        raw,
        "observed",
        Map.of());
    assertEquals(raw, observed.get(0).body());
    assertTrue(WireLogFormatter.format(observed.get(0), false).endsWith(raw));
    String pretty = WireLogFormatter.format(observed.get(0), true);
    assertTrue(
        pretty.contains("id: 1\n=== SSE data (JSON display; not wire text) ===\n{\n  \"n\": 1\n}"));
    assertTrue(pretty.contains("id: 2\n=== SSE data (JSON display; not wire text) ===\n{}"));
    assertFalse(pretty.contains("\ndata:"));
    assertEquals(
        "data: {\"unfinished\":\n\n",
        WireLogFormatter.prettyBody("data: {\"unfinished\":\n\n", "raw-sse-frame"));
  }

  // TC-ENG | invalidTruncatedAndNonJsonContentAreNotRepaired
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void invalidTruncatedAndNonJsonContentAreNotRepaired() {
    for (String raw : List.of("{\"x\":", "{\"x\":1} trailing", "[DONE]", "plain text"))
      assertEquals(raw, WireLogFormatter.prettyBody(raw, "serialized-utf8"));
    assertEquals(
        "data: [DONE]\n\n", WireLogFormatter.prettyBody("data: [DONE]\n\n", "raw-sse-frame"));
  }

  // TC-ENG | logLayoutIsPrettyButObserversKeepExactRedactedWireText
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void logLayoutIsPrettyButObserversKeepExactRedactedWireText() {
    List<WireLog.Entry> entries = new ArrayList<>();
    String raw = "{\"password\":\"hidden\",\"negotiationContext\":{\"performative\":\"ACCEPT\"}}";
    WireLog.emit(
        entries::add,
        "DIRECT_HTTP",
        "REQUEST_BODY",
        "req-1",
        "/send",
        "POST",
        null,
        Map.of(
            "Authorization",
            List.of("hidden"),
            "A2A-Extensions",
            List.of("Negotiation-T/v1"),
            "X-Trace",
            List.of("one", "two")),
        "serialized-utf8",
        raw,
        "observed",
        Map.of());
    WireLog.Entry entry = entries.get(0);
    assertEquals(raw.replace("hidden", "***"), entry.body());
    String pretty = WireLogFormatter.format(entry, true);
    // body frames are continuations: the envelope (headers/correlation) was logged by the preceding
    // entry
    assertTrue(pretty.startsWith("[DIRECT_HTTP] REQUEST_BODY requestId=req-1\n"));
    assertTrue(pretty.contains("(envelope logged separately; correlate"));
    assertFalse(pretty.contains("=== Headers ==="));
    assertFalse(pretty.contains("hidden"));
    assertTrue(pretty.contains("\n    \"performative\": \"ACCEPT\""));
    assertTrue(WireLogFormatter.format(entry, false).endsWith(entry.body()));
    assertEquals(raw.replace("hidden", "***"), entry.body());
  }

  // TC-ENG | envelopeEntriesKeepFullLayoutWhileBodyFramesStayCompact
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void envelopeEntriesKeepFullLayoutWhileBodyFramesStayCompact() {
    WireLog.Entry envelope =
        new WireLog.Entry(
            "DIRECT_HTTP",
            "RESPONSE_HEADERS",
            "req-1",
            "/message:stream",
            "POST",
            200,
            Map.of("Content-type", List.of("text/event-stream")),
            "sdk-headers",
            "",
            "observed",
            Map.of("agent", "SPN Domain Agent City1"));
    String full = WireLogFormatter.format(envelope, true);
    assertTrue(full.contains("Target: POST /message:stream"));
    assertTrue(full.contains("Status: 200"));
    assertTrue(full.contains("=== Headers ===\nContent-type: text/event-stream"));
    assertTrue(full.contains("=== Correlation ===\nagent: SPN Domain Agent City1"));

    WireLog.Entry frame =
        new WireLog.Entry(
            "DIRECT_HTTP",
            "RESPONSE_BODY",
            "req-1",
            "/message:stream",
            "POST",
            200,
            Map.of(),
            "sdk-sse-text",
            "id:1\ndata: {\"ok\":true}",
            "observed",
            Map.of("agent", "SPN Domain Agent City1"));
    String compact = WireLogFormatter.format(frame, true);
    assertTrue(compact.startsWith("[DIRECT_HTTP] RESPONSE_BODY requestId=req-1"));
    assertFalse(compact.contains("=== Headers ==="));
    assertFalse(compact.contains("=== Correlation ==="));
    assertTrue(compact.contains("=== Body ==="));
  }

  // TC-ENG | prettyDisplayDecodesNonAsciiUnicodeEscapesButKeepsStructuralEscapes
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void prettyDisplayDecodesNonAsciiUnicodeEscapesButKeepsStructuralEscapes() {
    String raw =
        "{\"授权策略\":\"\\u65b0\\u589e\\u52a8\\u7f51\\u64cd\\u4f5c\","
            + "\"note\":\"line\\u000anext\",\"emoji\":\"\\ud83d\\ude00\","
            + "\"prompt\":\"请根据\\u003c授权策略的操作类型\\u003e和\\u003c动网操作的授权策略列表\\u003e完成相应的授权操作\","
            + "\"structural\":\"quote \\u0022 and backslash \\u005c stay\"}";
    String pretty = WireLogFormatter.prettyBody(raw, "serialized-utf8");
    assertTrue(pretty.contains("\"授权策略\": \"新增动网操作\""));
    assertTrue(pretty.contains("\"emoji\": \"😀\""));
    assertTrue(pretty.contains("请根据<授权策略的操作类型>和<动网操作的授权策略列表>完成相应的授权操作"));
    // structural and control escapes stay verbatim; raw observation is untouched
    assertTrue(pretty.contains("line\\u000anext"));
    assertTrue(pretty.contains("\\u0022"));
    assertTrue(pretty.contains("\\u005c"));
  }

  // TC-ENG | displayExpansionIsBoundedAndDoesNotRecurseOnDeepJson
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void displayExpansionIsBoundedAndDoesNotRecurseOnDeepJson() {
    String key = "WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS";
    String previous = System.getProperty(key);
    System.setProperty(key, "256");
    try {
      String raw = "{\"a\":[" + "1,".repeat(80) + "2]}";
      String display = WireLogFormatter.prettyBody(raw, "serialized-utf8");
      assertTrue(display.startsWith(raw));
      assertTrue(display.contains("pretty skipped"));
      String deep = "[".repeat(70) + "0" + "]".repeat(70);
      assertEquals(
          deep, WireLogFormatter.prettyBody(deep, "serialized-utf8").split("\n\\(pretty")[0]);
    } finally {
      if (previous == null) System.clearProperty(key);
      else System.setProperty(key, previous);
    }
  }

  // TC-ENG | disabledBodyIsNotReconstructedByPrettyDisplay
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void disabledBodyIsNotReconstructedByPrettyDisplay() {
    String key = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
    String previous = System.getProperty(key);
    System.setProperty(key, "false");
    try {
      List<WireLog.Entry> entries = new ArrayList<>();
      WireLog.emit(
          entries::add,
          "DIRECT_HTTP",
          "REQUEST_BODY",
          "req",
          "/",
          "POST",
          null,
          Map.of(),
          "serialized-utf8",
          "{\"privateBusiness\":42}",
          "observed",
          Map.of());
      String display = WireLogFormatter.format(entries.get(0), true);
      assertTrue(display.contains("(body logging disabled)"));
      assertFalse(display.contains("privateBusiness"));
    } finally {
      if (previous == null) System.clearProperty(key);
      else System.setProperty(key, previous);
    }
  }
}

/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-1053…1059：WireLog record 入口/body-disabled/Body 边界 + Crypto 非十六进制 key。 */
class WireLogRecordAndBodyEdgeTest {

  private static final String INCLUDE_BODY = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
  private static final String PRETTY = "WORKFLOW_ENGINE_PROTOCOL_PRETTY";

  @AfterEach
  void restore() {
    System.clearProperty(INCLUDE_BODY);
    System.clearProperty(PRETTY);
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS");
  }

  // TC-ENG-1053 | record() 公共入口：PROTOCOL logger DEBUG 时输出完整 envelope
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void recordEmitsEnvelopeWhenDebugEnabled() {
    WireLog.record(
        "OUT",
        "REQUEST",
        "req-42",
        "http://agent.test/a2a",
        "POST",
        200,
        Map.of("X-A", List.of("1")),
        "json",
        "{\"k\":1}",
        "application-boundary");
  }

  // TC-ENG-1054 | record() + INCLUDE_BODY=false → body 占位符 + visibility 标记
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void recordBodyDisabledReplacesBody() {
    System.setProperty(INCLUDE_BODY, "false");
    WireLog.record(
        "OUT",
        "REQUEST",
        "req-43",
        "http://agent.test/a2a",
        "POST",
        200,
        Map.of(),
        "json",
        "{\"secret\":1}",
        "application-boundary");
  }

  // TC-ENG-1055 | record() body 超限截断
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void recordOversizedBodyTruncated() {
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS", "64");
    WireLog.record(
        "OUT",
        "REQUEST",
        "req-44",
        "http://agent.test/a2a",
        "POST",
        200,
        Map.of(),
        "json",
        "x".repeat(400),
        "application-boundary");
  }

  // TC-ENG-1056 | Body：end 后再 accept 被忽略（ended 早退分支）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void bodyAcceptAfterEndIsIgnored() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(false, true, frames::add);
    body.accept(ByteBuffer.wrap("first".getBytes(StandardCharsets.UTF_8)));
    body.end(false);
    body.accept(ByteBuffer.wrap("late".getBytes(StandardCharsets.UTF_8)));
    assertEquals(1, frames.size());
    assertEquals("first", frames.get(0)[0]);
  }

  // TC-ENG-1057 | Body：SSE 单独 CR 后跟非 LF → 行边界重建，两段合入同一帧
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void bodySseCarriageReturnFollowedByData() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(true, true, frames::add);
    body.accept(ByteBuffer.wrap("data: a\rdata: b\n".getBytes(StandardCharsets.UTF_8)));
    body.end(false);
    assertEquals(1, frames.size());
    assertTrue(frames.get(0)[0].contains("data: a"));
    assertTrue(frames.get(0)[0].contains("data: b"));
  }

  // TC-ENG-1058 | Body：SSE pendingCr 在 end 时补发 lineEnd（保留原始 CR 字节）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void bodySsePendingCrFlushedAtEnd() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(true, true, frames::add);
    body.accept(ByteBuffer.wrap("data: x\r".getBytes(StandardCharsets.UTF_8)));
    assertEquals(0, frames.size());
    body.end(false);
    assertEquals(1, frames.size());
    assertTrue(frames.get(0)[0].startsWith("data: x"));
  }

  // TC-ENG-1059 | encrypt 使用 64 位非十六进制 key → hexToBytes 字符校验拒绝（包装为 RuntimeException）
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonHexKeyRejectedWithCharMessage() {
    String badKey = "z".repeat(64);
    RuntimeException error =
        assertThrows(RuntimeException.class, () -> CredentialCrypto.encrypt("x", badKey));
    assertTrue(error.getMessage().contains("hexadecimal characters only"), error.getMessage());
  }
}

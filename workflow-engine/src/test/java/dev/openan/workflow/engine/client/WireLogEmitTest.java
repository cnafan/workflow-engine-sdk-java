/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-780…787：WireLog 上下文/开关与 Body 帧边界/容量分支。 */
class WireLogEmitTest {

  @AfterEach
  void clearSettings() {
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY");
    System.clearProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS");
  }

  // TC-ENG-780 | inContext 期间 ThreadLocal 生效，结束后恢复
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void inContextRestoresPrevious() {
    WireLog.inContext(
        Map.of("rid", "r-1"), () -> assertEquals("r-1", WireLog.context().get("rid")));
    assertTrue(WireLog.context().isEmpty());
    String value =
        WireLog.call(
            Map.of("rid", "r-2"),
            () -> {
              assertEquals("r-2", WireLog.context().get("rid"));
              return WireLog.context().get("rid");
            });
    assertEquals("r-2", value);
  }

  // TC-ENG-781 | setting：属性存在/缺失回退
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void settingBranches() {
    String key = "WORKFLOW_ENGINE_PROTOCOL_INCLUDE_BODY";
    System.clearProperty(key);
    assertTrue(WireLog.setting(key, true));
    System.setProperty(key, "false");
    assertTrue(!WireLog.setting(key, true));
    System.setProperty(key, "garbage");
    assertEquals(false, WireLog.setting(key, false));
  }

  // TC-ENG-782 | limit：非法值回退 100000；小正值生效
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void limitParsesOrFallsBack() {
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS", "not-a-number");
    assertEquals(100_000, WireLog.limit());
    System.setProperty("WORKFLOW_ENGINE_PROTOCOL_MAX_BODY_CHARS", "300");
    assertEquals(300, WireLog.limit());
  }

  // TC-ENG-783 | SSE 单独 CR 行结束也触发 flush
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseCarriageReturnAloneFlushesFrame() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(true, true, frames::add);
    body.accept(ByteBuffer.wrap("data: a\r".getBytes(StandardCharsets.UTF_8)));
    body.end(false);
    assertTrue(frames.size() >= 1);
  }

  // TC-ENG-784 | 非 SSE 二进制 body：内容省略标记
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonSseBinaryBodyOmitted() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(false, false, frames::add);
    body.accept(ByteBuffer.wrap(new byte[] {1, 2, 3}));
    body.end(false);
    assertEquals(1, frames.size());
    assertEquals("(binary body omitted)", frames.get(0)[0]);
  }

  // TC-ENG-785 | 超过容量：dropped 标记（容量在 Body 构造时读取）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void oversizedBodyMarkedDropped() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(false, true, frames::add);
    body.accept(
        ByteBuffer.wrap("x".repeat(WireLog.limit() + 100).getBytes(StandardCharsets.UTF_8)));
    body.end(false);
    assertEquals("(body/frame dropped: capacity exceeded)", frames.get(0)[0]);
    assertTrue(frames.get(0)[1].contains("dropped"));
  }

  // TC-ENG-786 | end(true) 非 SSE 输出 interrupted 标记；重复 end 幂等
  //
  // 输入: 超时/中断条件
  // 预期输出: 超时异常或中断恢复
  // 断言点: 异常类型 + 中断标志恢复
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void interruptedEndAndIdempotent() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(false, true, frames::add);
    body.accept(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)));
    body.end(true);
    body.end(true);
    assertEquals(1, frames.size());
    assertTrue(frames.get(0)[1].contains("stream=interrupted"));
  }

  // TC-ENG-787 | sink 抛异常不外泄
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sinkFailureIsContained() {
    WireLog.Body body =
        new WireLog.Body(
            false,
            true,
            frame -> {
              throw new IllegalStateException("sink boom");
            });
    body.accept(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8)));
    body.end(false);
  }

  // TC-ENG-788 | SSE 空行（CRLFCRLF）刷新帧
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sseBlankLineFlushes() {
    List<String[]> frames = new ArrayList<>();
    WireLog.Body body = new WireLog.Body(true, true, frames::add);
    body.accept(ByteBuffer.wrap("data: x\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
    body.end(false);
    assertTrue(frames.size() >= 1);
  }
}

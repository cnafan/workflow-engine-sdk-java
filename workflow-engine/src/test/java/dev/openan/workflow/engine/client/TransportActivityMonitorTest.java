/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TC-ENG-895…897：TransportActivityMonitor ThreadLocal/异常隔离。 */
class TransportActivityMonitorTest {

  // TC-ENG-895 | call 期间可 capture，结束后恢复（null 与非 null previous）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void capturesAndRestoresListener() {
    assertNull(TransportActivityMonitor.capture());
    Runnable listener = () -> {};
    TransportActivityMonitor.call(
        listener,
        () -> {
          assertSame(listener, TransportActivityMonitor.capture());
          return null;
        });
    assertNull(TransportActivityMonitor.capture());
  }

  // TC-ENG-896 | null listener 清除 ThreadLocal
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullListenerClearsContext() {
    Runnable listener = () -> {};
    TransportActivityMonitor.call(
        listener,
        () -> {
          TransportActivityMonitor.call(
              null,
              () -> {
                assertNull(TransportActivityMonitor.capture());
                return null;
              });
          assertSame(listener, TransportActivityMonitor.capture());
          return null;
        });
  }

  // TC-ENG-897 | notifyActivity：null 无操作；监听器抛异常不外泄
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void notifyActivityNullAndThrowingAreSafe() {
    TransportActivityMonitor.notifyActivity(null);
    AtomicInteger calls = new AtomicInteger();
    Runnable throwing =
        () -> {
          calls.incrementAndGet();
          throw new IllegalStateException("boom");
        };
    TransportActivityMonitor.notifyActivity(throwing);
    assertEquals(1, calls.get());
  }
}

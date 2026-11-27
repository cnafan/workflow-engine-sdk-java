/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.SendMessageResult;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TC-ENG-310…313：NotificationSubscription 生命周期与活性快照（直接构造）。 */
class NotificationSubscriptionLifecycleTest {

  private NotificationSubscription subscription() {
    AtomicInteger closes = new AtomicInteger();
    NotificationSubscription subscription =
        new NotificationSubscription("agent-a", "ctx-1", closes::incrementAndGet);
    return subscription;
  }

  // TC-ENG-310 | 访问器与初始活性
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void accessorsAndInitialActivity() {
    NotificationSubscription s = subscription();
    assertEquals("agent-a", s.agentName());
    assertEquals("ctx-1", s.contextId());
    assertTrue(s.isActive());
    assertNotNull(s.heartbeat());
    assertEquals(0L, s.heartbeat().eventCount());
    // 无活动 → 不健康
    assertFalse(s.isHealthy(Duration.ofSeconds(1)));
    assertNull(s.lastBusinessEventAt());
    assertThrows(NullPointerException.class, () -> s.isHealthy(null));
    // 负 duration 拒绝
    assertThrows(IllegalArgumentException.class, () -> s.isHealthy(Duration.ofSeconds(-1)));
  }

  // TC-ENG-311 | recordEvent 后健康；recordActivity 不增加业务事件计数
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void eventAndActivityLiveness() throws InterruptedException {
    NotificationSubscription s = subscription();
    s.recordEvent();
    assertEquals(1L, s.heartbeat().eventCount());
    assertNotNull(s.lastBusinessEventAt());
    assertTrue(s.isHealthy(Duration.ofSeconds(60)));

    Thread.sleep(5);
    s.recordActivity();
    // recordActivity 只刷新传输活动，不增加业务事件
    assertEquals(1L, s.heartbeat().eventCount());
    assertTrue(s.isHealthy(Duration.ofSeconds(60)));
  }

  // TC-ENG-312 | acknowledge 完成确认；failAcknowledgement 异常完成
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void acknowledgeAndFailAcknowledgement() throws Exception {
    NotificationSubscription s = subscription();
    SendMessageResult result =
        SendMessageResult.builder().taskState("TASK_STATE_COMPLETED").build();
    s.acknowledge(result);
    assertEquals(result, s.acknowledgement().get());

    NotificationSubscription failed = subscription();
    IllegalStateException boom = new IllegalStateException("boom");
    failed.failAcknowledgement(boom);
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> failed.acknowledgement().get());
    assertEquals(boom, error.getCause());
  }

  // TC-ENG-313 | close：closeAction 执行 + 确认以 CancellationException 失败；幂等
  //
  // 输入: 关闭/销毁操作
  // 预期输出: 资源释放，后续调用被拒绝或安全忽略
  // 断言点: 关闭标志 + 后续行为
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void closeRunsActionAndCancelsAcknowledgement() {
    AtomicInteger closes = new AtomicInteger();
    NotificationSubscription s =
        new NotificationSubscription("agent-a", "ctx-1", closes::incrementAndGet);
    s.close();
    assertEquals(1, closes.get());
    CancellationException cancellation =
        assertThrows(CancellationException.class, () -> s.acknowledgement().get());
    assertTrue(cancellation.getMessage().contains("closed before acknowledgement"));
    assertFalse(s.isActive());

    // 第二次 close 幂等，不重复执行 closeAction
    s.close();
    assertEquals(1, closes.get());
  }

  // TC-ENG-314 | markStreamTerminated：无 streamFailure → completion 正常完成
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void streamTerminationCompletesNormally() throws Exception {
    NotificationSubscription s = subscription();
    s.completeStream();
    s.markStreamTerminated();
    s.completion().get();
    assertFalse(s.isActive());
  }
}

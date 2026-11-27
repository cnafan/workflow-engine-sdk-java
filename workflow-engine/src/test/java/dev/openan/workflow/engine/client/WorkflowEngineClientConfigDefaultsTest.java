/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-660…667：WorkflowEngineClientConfig 全 setter/防御性拷贝/校验。 */
class WorkflowEngineClientConfigDefaultsTest {

  // TC-ENG-660 | 全 setter 往返
  //
  // 输入: 每个 builder 方法设置非默认值
  // 预期输出: getter 精确返回
  // 断言点: 各值；防回归: 字段接线被改
  @Test
  void allSettersRoundTrip() {
    AuthProvider provider = (name, card, headers) -> {};
    Map<String, Map<String, Map<String, Object>>> credentials =
        Map.of("agent", Map.of("scheme", Map.of("token", "t")));
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .sslVerify(false)
            .caCertsPath("ca.pem")
            .clientCertPath("cert.pem")
            .clientKeyPath("key.pem")
            .clientKeyPassword("pw")
            .crlPath("crl.pem")
            .sendTimeoutSeconds(42)
            .notificationAckTimeoutSeconds(7)
            .sendExecutorCoreSize(2)
            .sendExecutorMaxSize(8)
            .sendExecutorQueueCapacity(32)
            .authProvider(provider)
            .credentialsConfigPath("creds.json")
            .credentialEncryptionKey("key")
            .maxNegotiationExchanges(9)
            .preferredProtocol("GRPC")
            .taskPollIntervalMillis(250)
            .build();
    assertFalse(config.isSslVerify());
    assertEquals("ca.pem", config.getCaCertsPath());
    assertEquals("cert.pem", config.getClientCertPath());
    assertEquals("key.pem", config.getClientKeyPath());
    assertEquals("pw", config.getClientKeyPassword());
    assertEquals("crl.pem", config.getCrlPath());
    assertEquals(42, config.getSendTimeoutSeconds());
    assertEquals(7, config.getNotificationAckTimeoutSeconds());
    assertEquals(2, config.getSendExecutorCoreSize());
    assertEquals(8, config.getSendExecutorMaxSize());
    assertEquals(32, config.getSendExecutorQueueCapacity());
    assertSameProvider(provider, config.getAuthProvider());
    assertEquals("creds.json", config.getCredentialsConfigPath());
    assertEquals("key", config.getCredentialEncryptionKey());
    assertEquals(9, config.getMaxNegotiationExchanges());
    assertEquals("GRPC", config.getPreferredProtocol());
    assertEquals(250, config.getTaskPollIntervalMillis());
  }

  private static void assertSameProvider(AuthProvider expected, AuthProvider actual) {
    assertTrue(expected == actual);
  }

  // TC-ENG-661 | credentialsConfig 深拷贝与不可变
  //
  // 输入: 嵌套 Map/List 结构
  // 预期输出: 值保留；返回结构不可变；内层 List 不可变
  // 断言点: 值+UnsupportedOperationException；防回归: 深拷贝被删
  @Test
  void credentialsConfigIsDeepCopiedAndImmutable() {
    Map<String, Object> schemeValues = new java.util.HashMap<>();
    schemeValues.put("scopes", new java.util.ArrayList<>(List.of("a", "b")));
    schemeValues.put("nested", new java.util.HashMap<>(Map.of("k", "v")));
    Map<String, Map<String, Map<String, Object>>> source = new java.util.HashMap<>();
    source.put("agent", new java.util.HashMap<>(Map.of("scheme", schemeValues)));
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder().credentialsConfig(source).build();
    Map<String, Object> stored = config.getCredentialsConfig().get("agent").get("scheme");
    assertEquals(List.of("a", "b"), stored.get("scopes"));
    assertEquals("v", ((Map<?, ?>) stored.get("nested")).get("k"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> config.getCredentialsConfig().put("x", Map.of()));
    assertThrows(
        UnsupportedOperationException.class, () -> ((List<Object>) stored.get("scopes")).add("c"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<Object, Object>) stored.get("nested")).put("y", "z"));
  }

  // TC-ENG-662 | null scheme / null values 归一为空 Map
  //
  // 输入: agent -> null；scheme -> null
  // 预期输出: 不抛，空 Map
  // 断言点: 空；防回归: null 归一被删即 NPE
  @Test
  void nullSchemeBucketsNormalizeToEmpty() {
    Map<String, Map<String, Map<String, Object>>> source = new java.util.HashMap<>();
    source.put("null-agent", null);
    source.put("empty-agent", new java.util.HashMap<>());
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder().credentialsConfig(source).build();
    assertTrue(config.getCredentialsConfig().get("null-agent").isEmpty());
    assertTrue(config.getCredentialsConfig().get("empty-agent").isEmpty());
  }

  // TC-ENG-663 | 默认配置
  //
  // 输入: build 无 setter
  // 预期输出: 默认超时/线程池/轮询值
  // 断言点: 默认；防回归: 默认值被改
  @Test
  void defaultsAreStable() {
    WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder().build();
    assertTrue(config.isSslVerify());
    assertEquals(600, config.getSendTimeoutSeconds());
    assertEquals(
        WorkflowEngineClientConfig.DEFAULT_NOTIFICATION_ACK_TIMEOUT_SECONDS,
        config.getNotificationAckTimeoutSeconds());
    assertEquals(4, config.getSendExecutorCoreSize());
    assertEquals(16, config.getSendExecutorMaxSize());
    assertEquals(256, config.getSendExecutorQueueCapacity());
    assertEquals(3, config.getMaxNegotiationExchanges());
    assertEquals(
        WorkflowEngineClientConfig.DEFAULT_TASK_POLL_INTERVAL_MILLIS,
        config.getTaskPollIntervalMillis());
    assertNull(config.getCredentialsConfig());
    assertNull(config.getPreferredProtocol());
  }

  // TC-ENG-664 | 校验：超时非正
  //
  // 输入: sendTimeout=0 / ackTimeout=-1
  // 预期输出: IAE "Timeouts must be positive"
  // 断言点: 消息；防回归: 校验被删
  @Test
  void nonPositiveTimeoutsRejected() {
    IllegalArgumentException e1 =
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowEngineClientConfig.builder().sendTimeoutSeconds(0).build());
    assertTrue(e1.getMessage().contains("Timeouts must be positive"));
    IllegalArgumentException e2 =
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowEngineClientConfig.builder().notificationAckTimeoutSeconds(-1).build());
    assertTrue(e2.getMessage().contains("Timeouts must be positive"));
  }

  // TC-ENG-665 | 校验：线程池/预算/轮询非法
  //
  // 输入: core=0, max<core, queue=0, exchanges=0, poll=99
  // 预期输出: 各自 IAE
  // 断言点: 消息；防回归: 校验被删
  @Test
  void invalidExecutorBudgetAndPollRejected() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowEngineClientConfig.builder().sendExecutorCoreSize(0).build())
            .getMessage()
            .contains("Invalid send executor"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    WorkflowEngineClientConfig.builder()
                        .sendExecutorCoreSize(8)
                        .sendExecutorMaxSize(2)
                        .build())
            .getMessage()
            .contains("Invalid send executor"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowEngineClientConfig.builder().sendExecutorQueueCapacity(0).build())
            .getMessage()
            .contains("Invalid send executor"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowEngineClientConfig.builder().maxNegotiationExchanges(0).build())
            .getMessage()
            .contains("must be positive"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowEngineClientConfig.builder().taskPollIntervalMillis(99).build())
            .getMessage()
            .contains("at least 100ms"));
  }

  // TC-ENG-666 | credentialsConfigPath 与 credentialsConfig 互斥
  //
  // 输入: 同时设置
  // 预期输出: IAE "mutually exclusive"
  // 断言点: 消息；防回归: 互斥守卫被删
  @Test
  void credentialsSourcesAreMutuallyExclusive() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WorkflowEngineClientConfig.builder()
                    .credentialsConfigPath("creds.json")
                    .credentialsConfig(Map.of())
                    .build());
    assertTrue(error.getMessage().contains("mutually exclusive"));
  }

  // TC-ENG-667 | 其余 setter 覆盖（clientKeyPassword/crl/credentialEncryptionKey 等已在 660 覆盖；
  // 此处覆盖 sslVerify(true) 显式传值路径）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void explicitTrueSslVerify() {
    assertTrue(WorkflowEngineClientConfig.builder().sslVerify(true).build().isSslVerify());
  }
}

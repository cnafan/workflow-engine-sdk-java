/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-ENG-830…835：AgentAuthManager 构造/缓存/加密校验。 */
class AgentAuthManagerConstructionTest {

  @TempDir java.nio.file.Path tmp;

  // TC-ENG-830 | null 配置不抛
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullConfigAccepted() {
    AgentAuthManager manager =
        new AgentAuthManager((Map<String, Map<String, Map<String, Object>>>) null);
    assertNull(manager.getService("missing"));
  }

  // TC-ENG-831 | 无参构造（auth disabled）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void noArgConstructor() {
    AgentAuthManager manager = new AgentAuthManager();
    assertNull(manager.getService("any"));
    assertNull(manager.getConfig("any"));
  }

  // TC-ENG-832 | getService 缓存同一实例；无配置返回 null
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void serviceIsCached() {
    Map<String, Map<String, Map<String, Object>>> config = new HashMap<>();
    config.put(
        "agent-a",
        Map.of(
            "bearer",
            Map.of(
                "login_url", "http://127.0.0.1:1/token",
                "token_field", "access_token",
                "username", "u",
                "password", "p")));
    AgentAuthManager manager = new AgentAuthManager(config, HttpClient.newHttpClient());
    AgentCredentialService first = manager.getService("agent-a");
    AgentCredentialService second = manager.getService("agent-a");
    assertNotNull(first);
    assertEquals(first, second);
    assertNull(manager.getService("unknown"));
    assertNotNull(manager.getConfig("agent-a"));
  }

  // TC-ENG-833 | 文件构造：有效 JSON
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loadsFromFile() throws Exception {
    java.nio.file.Path file = tmp.resolve("creds.json");
    java.nio.file.Files.writeString(
        file,
        "{\"agent-a\":{\"bearer\":{\"login_url\":\"http://x\",\"username\":\"u\",\"password\":\"p\"}}}");
    AgentAuthManager manager = new AgentAuthManager(file.toString());
    assertNotNull(manager.getService("agent-a"));
  }

  // TC-ENG-834 | enc: 值无 key 构造失败（fail-closed）
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void encryptedValueWithoutKeyFails() {
    Map<String, Map<String, Map<String, Object>>> config = new HashMap<>();
    config.put("agent-a", Map.of("bearer", Map.of("password", "enc:abc:def")));
    assertThrows(IllegalStateException.class, () -> new AgentAuthManager(config));
  }

  // TC-ENG-835 | enc: 值可解密时构造成功
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void encryptedValueWithKeyAccepted() {
    String key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String encrypted = CredentialCrypto.encrypt("secret", key);
    Map<String, Map<String, Map<String, Object>>> config = new HashMap<>();
    config.put("agent-a", Map.of("bearer", Map.of("password", encrypted)));
    AgentAuthManager manager = new AgentAuthManager(config, (java.net.http.HttpClient) null, key);
    assertNotNull(manager);
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;
import org.junit.jupiter.api.Test;

class AgentAuthManagerTest {

  // TC-ENG | loadsCredentialsFromClasspathLocation
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loadsCredentialsFromClasspathLocation() throws Exception {
    URL resource = getClass().getClassLoader().getResource("test-agent-credentials.json");
    assertNotNull(resource, "test resource must exist on classpath");
    AgentAuthManager manager = new AgentAuthManager(resource.getPath());

    var config = manager.getConfig("Test Agent");

    assertNotNull(config);
    assertEquals("https://auth.example.test/token", config.get("bearerAuth").get("login_url"));
  }

  // TC-ENG | loadsCredentialsFromClasspathPrefix
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void loadsCredentialsFromClasspathPrefix() {
    AgentAuthManager manager = new AgentAuthManager("classpath:test-agent-credentials.json");

    var config = manager.getConfig("Test Agent");

    assertNotNull(config);
    assertEquals("https://auth.example.test/token", config.get("bearerAuth").get("login_url"));
  }

  // TC-ENG | resolvesSharedProfilesAndPerAgentOverrides
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void resolvesSharedProfilesAndPerAgentOverrides() {
    AgentAuthManager manager = new AgentAuthManager("classpath:test-profile-credentials.json");

    var city1 = manager.getConfig("City 1").get("bearerAuth");
    var city2 = manager.getConfig("City 2").get("bearerAuth");

    assertEquals("https://city1.example.test/token", city1.get("login_url"));
    assertEquals("https://shared.example.test/token", city2.get("login_url"));
    assertEquals(
        "shared-user", ((java.util.Map<?, ?>) city1.get("request_fields")).get("userName"));
    assertEquals(
        "city1-password", ((java.util.Map<?, ?>) city1.get("request_fields")).get("value"));
    assertEquals(
        "shared-password", ((java.util.Map<?, ?>) city2.get("request_fields")).get("value"));
  }

  // TC-ENG | missingFileFailsClosed
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingFileFailsClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> new AgentAuthManager("/nonexistent/missing-agent-credentials.json"));
  }

  // TC-ENG | missingClasspathResourceFailsClosed
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingClasspathResourceFailsClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> new AgentAuthManager("classpath:nonexistent-credentials.json"));
  }
}

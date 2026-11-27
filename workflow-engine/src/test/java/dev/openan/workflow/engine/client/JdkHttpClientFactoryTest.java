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

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdkHttpClientFactoryTest {
  // TC-ENG | doesNotAutomaticallyRedirectAuthenticatedProtocolRequests
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void doesNotAutomaticallyRedirectAuthenticatedProtocolRequests() {
    HttpClient client =
        JdkHttpClientFactory.create(
            true, null, null, null, null, null, Duration.ofSeconds(1), null);

    assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
  }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.junit.jupiter.api.Test;

class RuntimeChannelSelectionTest {
  // TC-ENG | channelSelectionUsesExplicitLocalStateRatherThanBusinessMetadata
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void channelSelectionUsesExplicitLocalStateRatherThanBusinessMetadata() {
    ClientCallContext taskContext = new ClientCallContext(new HashMap<>(), new HashMap<>());
    assertFalse(DefaultA2AJavaClientRuntime.isNotificationStream(taskContext));

    ClientCallContext notificationContext =
        new ClientCallContext(
            new HashMap<>(
                Map.of(
                    A2AJavaClientRuntime.CHANNEL_STATE_KEY,
                    A2AJavaClientRuntime.NOTIFICATION_CHANNEL)),
            new HashMap<>());
    assertTrue(DefaultA2AJavaClientRuntime.isNotificationStream(notificationContext));
  }
}

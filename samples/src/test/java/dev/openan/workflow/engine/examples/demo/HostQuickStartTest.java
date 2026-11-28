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

package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.NegotiationStrategy;
import dev.openan.workflow.engine.model.*;
import dev.openan.workflow.engine.model.TaskRequest;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostQuickStartTest {
  @Test
  void publishedHostExampleReachesLocalAggregation() throws Exception {
    var sent = new AtomicInteger();
    var client =
        (WorkflowEngineClient)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {WorkflowEngineClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("callbackTimeoutSeconds")) return 10L;
                  // A Proxy forwards interface default methods to this handler instead of running
                  // sendWorkflowTask's default, which would delegate to sendTask on a real client.
                  if (method.getName().equals("sendWorkflowTask")
                      && args != null
                      && args.length == 4) {
                    return ((WorkflowEngineClient) proxy)
                        .sendTask(
                            ((TaskRequest) args[0]).getAgentName(),
                            (MessageContent) args[1],
                            (NegotiationStrategy) args[2],
                            (EventCallback) args[3]);
                  }
                  if (method.getName().equals("sendTask")
                      && (method.getParameterCount() == 3 || method.getParameterCount() == 4)) {
                    sent.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        SendMessageResult.builder()
                            .taskState("TASK_STATE_COMPLETED")
                            .receivedMessages(
                                List.of(
                                    new ReceivedMessage(
                                        MessageContent.text("diagnosis"), Map.of(), List.of())))
                            .build());
                  }
                  return null;
                });
    var result = HostQuickStart.execute(HostQuickStart.workflow("test-agent"), List.of(), client);
    assertTrue(result.isSuccess(), result.getError());
    assertEquals(1, sent.get());
    assertTrue(result.getStepOutputs().get("aggregate").toString().contains("diagnosis"));
  }
}

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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContentDependencyBoundaryTest {
  // TC-ENG | engineClasspathHasNoContentClientOrLlmModules
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void engineClasspathHasNoContentClientOrLlmModules() {
    for (String name :
        List.of(
            "net.openan.a2at.sdk.client.A2ATClient",
            "net.openan.a2at.sdk.server.A2ATServer",
            "net.openan.a2at.sdk.llm.LlmClient")) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
    }
    String classpath = System.getProperty("java.class.path");
    for (String module :
        List.of(
            "a2a-t-client-",
            "a2a-t-llm-",
            "a2a-t-prompt-",
            "a2a-t-resources-",
            "a2a-t-negotiation-")) assertFalse(classpath.contains(module), module);
  }

  // TC-ENG | sourcePackagesHaveOnlyAllowedDependencyDirection
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sourcePackagesHaveOnlyAllowedDependencyDirection() throws Exception {
    Path root = Path.of("src/main/java/dev/openan/workflow/engine");
    try (var paths = Files.walk(root)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (relative.startsWith("core/")
            || relative.startsWith("model/")
            || relative.startsWith("control/")) {
          assertFalse(source.contains("net.openan.a2at"), relative);
        }
        for (String forbidden :
            List.of(
                "net.openan.a2at.sdk.client",
                "net.openan.a2at.sdk.server",
                "net.openan.a2at.sdk.llm",
                "net.openan.a2at.sdk.negotiation",
                "net.openan.a2at.sdk.prompt")) {
          assertFalse(source.contains(forbidden), relative + " imports " + forbidden);
        }
      }
    }
  }
}

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvFileLoaderTest {
  private static final String ISOLATED_KEY = "A2AT_TEST_INSTANCE_SCOPED_KEY";

  @TempDir Path tempDirectory;

  @AfterEach
  void clearIsolatedProperty() {
    System.clearProperty(ISOLATED_KEY);
  }

  // TC-ENG-020（既有用例保留编号） | read 解析不触碰 JVM 全局属性
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void readParsesValuesWithoutMutatingJvmProperties() throws Exception {
    Path envFile = tempDirectory.resolve("city-one.env");
    Files.writeString(
        envFile,
        "# city-specific configuration\n"
            + ISOLATED_KEY
            + "=city-one\n"
            + "QUOTED_VALUE=\"with spaces\"\n");
    System.clearProperty(ISOLATED_KEY);

    Map<String, String> values = EnvFileLoader.read(envFile);

    assertEquals("city-one", values.get(ISOLATED_KEY));
    assertEquals("with spaces", values.get("QUOTED_VALUE"));
    assertNull(System.getProperty(ISOLATED_KEY));
  }

  // TC-ENG-021 | loadToSystemProperties 将缺失键写入系统属性
  //
  // 输入: .env 含 A2AT_TEST_INSTANCE_SCOPED_KEY=from-file，进程属性与环境变量中均无该键
  // 预期输出: System.getProperty 返回 "from-file"
  // 断言点: 属性值；防回归: 写入逻辑被删即失败
  @Test
  void loadSetsMissingKeysAsSystemProperties() throws Exception {
    Path envFile = tempDirectory.resolve("app.env");
    Files.writeString(envFile, ISOLATED_KEY + "=from-file\n");
    System.clearProperty(ISOLATED_KEY);

    EnvFileLoader.loadToSystemProperties(envFile);

    assertEquals("from-file", System.getProperty(ISOLATED_KEY));
  }

  // TC-ENG-022 | 已存在的系统属性优先，不被文件覆盖
  //
  // 输入: 进程已有 ISOLATED_KEY=in-jvm，.env 中同名键为 from-file
  // 预期输出: 属性保持 "in-jvm"
  // 断言点: 属性值不变；防回归: 优先级判断写反即失败
  @Test
  void existingSystemPropertyWinsOverFile() throws Exception {
    Path envFile = tempDirectory.resolve("app.env");
    Files.writeString(envFile, ISOLATED_KEY + "=from-file\n");
    System.setProperty(ISOLATED_KEY, "in-jvm");

    EnvFileLoader.loadToSystemProperties(envFile);

    assertEquals("in-jvm", System.getProperty(ISOLATED_KEY));
  }

  // TC-ENG-023 | 已存在的环境变量优先，不写入系统属性
  //
  // 输入: .env 含 PATH=__ignored__（PATH 恒存在于 OS 环境变量）
  // 预期输出: 加载后 System.getProperty("PATH") 仍为 null（env 分支命中，未落属性）
  // 断言点: 属性为 null；防回归: 环境变量检查被删即失败
  @Test
  void existingEnvironmentVariableWinsOverFile() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("PATH") != null);
    Path envFile = tempDirectory.resolve("app.env");
    Files.writeString(envFile, "PATH=__ignored__\n");
    System.clearProperty("PATH");

    EnvFileLoader.loadToSystemProperties(envFile);

    assertNull(System.getProperty("PATH"));
  }

  // TC-ENG-024 | null 路径与不存在的文件均返回空 map
  //
  // 输入: read(null) / read(不存在的路径)
  // 预期输出: 两者均返回空 map
  // 断言点: isEmpty；防回归: 空守卫被删即失败
  @Test
  void nullAndMissingPathsReturnEmptyMap() {
    assertTrue(EnvFileLoader.read(null).isEmpty());
    assertTrue(EnvFileLoader.read(tempDirectory.resolve("no-such.env")).isEmpty());
  }

  // TC-ENG-025 | 读取失败（目录路径触发 IOException）返回空 map 而非抛出
  //
  // 输入: read(指向目录的 Path)
  // 预期输出: 空 map
  // 断言点: isEmpty；防回归: IOException 被改为上抛即失败
  @Test
  void unreadablePathReturnsEmptyMapInsteadOfThrowing() {
    assertTrue(EnvFileLoader.read(tempDirectory).isEmpty());
  }

  // TC-ENG-026 | 解析器边界：空行/注释/无等号/行首等号/重复键取首个/引号剥离
  //
  // 输入: 混合 6 类边界行的 .env
  // 预期输出: 仅合法键入 map，重复键取首值，引号值剥离引号
  // 断言点: map 精确内容；防回归: 解析分支任一改动即失败
  @Test
  void parserEdgesAreHandled() throws Exception {
    Path envFile = tempDirectory.resolve("edges.env");
    Files.writeString(
        envFile,
        "\n"
            + "# comment line\n"
            + "NO_EQUALS_SIGN\n"
            + "=starts-with-equals\n"
            + ISOLATED_KEY
            + "=first\n"
            + ISOLATED_KEY
            + "=second\n"
            + "QUOTED=\"strip me\"\n");

    Map<String, String> values = EnvFileLoader.read(envFile);

    assertEquals("first", values.get(ISOLATED_KEY));
    assertEquals("strip me", values.get("QUOTED"));
    assertEquals(2, values.size());
  }
}

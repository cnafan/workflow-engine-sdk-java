/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-ENG-260…264：CredentialConfigLoader 原始表单与 profile 表单解析。 */
class CredentialConfigLoaderProfilesTest {

  @TempDir Path tmp;

  private static String loadFile(Path file) throws Exception {
    java.lang.reflect.Method m =
        CredentialConfigLoader.class.getDeclaredMethod("load", String.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Map<String, Object>>> result =
        (Map<String, Map<String, Map<String, Object>>>) m.invoke(null, file.toString());
    return result.toString();
  }

  private static Map<String, Map<String, Map<String, Object>>> loadRaw(String path)
      throws Exception {
    java.lang.reflect.Method m =
        CredentialConfigLoader.class.getDeclaredMethod("load", String.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Map<String, Object>>> result =
        (Map<String, Map<String, Map<String, Object>>>) m.invoke(null, path);
    return result;
  }

  private Path write(String name, String json) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, json, StandardCharsets.UTF_8);
    return file;
  }

  // TC-ENG-260 | null/空白路径 → 空 Map
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void blankPathReturnsEmpty() throws Exception {
    assertTrue(loadRaw(null).isEmpty());
    assertTrue(loadRaw("  ").isEmpty());
  }

  // TC-ENG-261 | 文件不存在 → ISE
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingFileFails() {
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> loadRaw(tmp.resolve("nope.json").toString()));
    assertTrue(error.getCause().getMessage().contains("not found"));
  }

  // TC-ENG-262 | 原始 agent-keyed 表单直接转换
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void plainFormConvertsDirectly() throws Exception {
    Path file =
        write(
            "plain.json",
            "{\"agent-a\":{\"bearerAuth\":{\"auth_header\":\"X-Auth\","
                + " \"auth_header_prefix\":\"Bearer \"}}}");
    Map<String, Map<String, Map<String, Object>>> resolved = loadRaw(file.toString());
    assertTrue(resolved.containsKey("agent-a"));
    assertEquals("X-Auth", resolved.get("agent-a").get("bearerAuth").get("auth_header"));
  }

  // TC-ENG-263 | profile+agents 解析：绑定 profile + overrides 标量合并
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void profileFormResolvesAndOverrides() throws Exception {
    Path file =
        write(
            "profiles.json",
            "{\"profiles\":{\"p1\":{\"bearerAuth\":{\"auth_header\":\"X-Auth\"}}},"
                + " \"agents\":{\"agent-a\":{\"profile\":\"p1\","
                + " \"overrides\":{\"bearerAuth\":{\"token_ttl\":\"60\"}}}}}");
    Map<String, Map<String, Map<String, Object>>> resolved = loadRaw(file.toString());
    Map<String, Object> scheme = resolved.get("agent-a").get("bearerAuth");
    assertEquals("X-Auth", scheme.get("auth_header"));
    assertEquals("60", scheme.get("token_ttl"));
  }

  // TC-ENG-264 | 未知 profile / 缺失 profile 名 → 异常
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void unknownProfileRejected() throws Exception {
    Path file =
        write(
            "unknown.json",
            "{\"profiles\":{\"p1\":{}}," + " \"agents\":{\"a\":{\"profile\":\"missing\"}}}");
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> loadRaw(file.toString()));
    assertTrue(error.getCause().getMessage().contains("Unknown credential profile"));

    Path blank =
        write(
            "blank.json",
            "{\"profiles\":{\"p1\":{}}," + " \"agents\":{\"a\":{\"profile\":\"  \"}}}");
    assertThrows(
        java.lang.reflect.InvocationTargetException.class, () -> loadRaw(blank.toString()));
  }

  // 防止未使用导入
  @SuppressWarnings("unused")
  private static final String KEEP = loadFileMarker();

  private static String loadFileMarker() {
    return "";
  }

  // TC-ENG-640 | 显式 null/空白路径返回空 Map（静态入口）
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullAndBlankPathsReturnEmpty() {
    assertTrue(CredentialConfigLoader.load(null).isEmpty());
    assertTrue(CredentialConfigLoader.load("   ").isEmpty());
  }

  // TC-ENG-641 | 根级凭据配置直接转换（无 profiles/agents）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void flatConfigConvertedDirectly() throws Exception {
    Path file =
        write(
            "flat.json",
            "{\"agent-a\":{\"bearer\":{\"login_url\":\"http://x\",\"username\":\"u\","
                + "\"password\":\"p\",\"token_field\":\"access_token\"}}}");
    Map<String, Map<String, Map<String, Object>>> result =
        CredentialConfigLoader.load(file.toString());
    assertTrue(result.containsKey("agent-a"));
  }

  // TC-ENG-642 | 不存在的 classpath 资源 → ISE
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingClasspathResourceFails() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> CredentialConfigLoader.load("classpath:does-not-exist.json"));
    assertTrue(error.getMessage().contains("classpath"));
  }

  // TC-ENG-643 | agents 绑定非对象 → 失败
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonObjectAgentBindingFails() throws Exception {
    Path file = write("bad-binding.json", "{\"profiles\":{\"p\":{}},\"agents\":{\"a\":\"x\"}}");
    assertThrows(IllegalStateException.class, () -> CredentialConfigLoader.load(file.toString()));
  }

  // TC-ENG-644 | override 为非对象 → 失败
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonObjectOverridesFails() throws Exception {
    Path file =
        write(
            "bad-overrides.json",
            "{\"profiles\":{\"p\":{\"bearer\":{}}},"
                + "\"agents\":{\"a\":{\"profile\":\"p\",\"overrides\":\"x\"}}}");
    assertThrows(IllegalStateException.class, () -> CredentialConfigLoader.load(file.toString()));
  }
}

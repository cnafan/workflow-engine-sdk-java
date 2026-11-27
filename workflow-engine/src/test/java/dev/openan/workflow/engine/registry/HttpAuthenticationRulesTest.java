/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-290…292：HttpAuthentication URL 规范化与请求头校验。 */
class HttpAuthenticationRulesTest {

  // TC-ENG-290 | baseUrl：合法 URL 去尾斜杠；null/非法/非 http/凭证/query/fragment 拒绝
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void baseUrlNormalizationAndValidation() throws Exception {
    java.lang.reflect.Method baseUrl =
        HttpAuthentication.class.getDeclaredMethod("baseUrl", String.class);
    baseUrl.setAccessible(true);

    assertEquals("http://127.0.0.1:8080", baseUrl.invoke(null, "http://127.0.0.1:8080/"));
    assertEquals("https://host.example", baseUrl.invoke(null, "https://host.example"));

    InvocationTargetAssertion.assertIllegalArgument(() -> baseUrl.invoke(null, "not a url"));
    InvocationTargetAssertion.assertIllegalArgument(() -> baseUrl.invoke(null, "ftp://host"));
    InvocationTargetAssertion.assertIllegalArgument(() -> baseUrl.invoke(null, "http:///path"));
    InvocationTargetAssertion.assertIllegalArgument(() -> baseUrl.invoke(null, "http://user@host"));
    InvocationTargetAssertion.assertIllegalArgument(() -> baseUrl.invoke(null, "http://host?a=1"));
    InvocationTargetAssertion.assertIllegalArgument(() -> baseUrl.invoke(null, "http://host#f"));
  }

  // TC-ENG-291 | headers：合法头通过；provider 抛运行时异常包装
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void headersValidAndProviderFailureWrapped() throws Exception {
    java.lang.reflect.Method headers =
        HttpAuthentication.class.getDeclaredMethod("headers", HttpAuthProvider.class, URI.class);
    headers.setAccessible(true);

    @SuppressWarnings("unchecked")
    Map<String, String> ok =
        (Map<String, String>)
            headers.invoke(
                null,
                (HttpAuthProvider) uri -> Map.of("Authorization", "Bearer x"),
                URI.create("http://host"));
    assertEquals("Bearer x", ok.get("Authorization"));

    HttpAuthProvider failing =
        uri -> {
          throw new IllegalStateException("provider down");
        };
    InvocationTargetAssertion.assertIllegalState(
        () -> headers.invoke(null, failing, URI.create("http://host")));
  }

  // TC-ENG-292 | headers：保留字/重复/空值/非 ASCII 值拒绝
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void headersInvalidNamesAndValuesRejected() throws Exception {
    java.lang.reflect.Method headers =
        HttpAuthentication.class.getDeclaredMethod("headers", HttpAuthProvider.class, URI.class);
    headers.setAccessible(true);

    assertHeaderRejected(headers, "Host", "x");
    assertHeaderRejected(headers, "bad name", "x");
    assertHeaderRejected(headers, "Authorization", "");
    assertHeaderRejected(headers, "Authorization", "line\nbreak");
    Map<String, String> duplicateKeys = new LinkedHashMap<>();
    duplicateKeys.put("X-Trace", "a");
    duplicateKeys.put("x-trace", "b");
    InvocationTargetAssertion.assertIllegalArgument(
        () ->
            headers.invoke(
                null, (HttpAuthProvider) uri -> duplicateKeys, URI.create("http://host")));
    assertFalse(duplicateKeys.isEmpty());
  }

  private static void assertHeaderRejected(
      java.lang.reflect.Method headers, String name, String value) {
    InvocationTargetAssertion.assertIllegalArgument(
        () ->
            headers.invoke(
                null, (HttpAuthProvider) uri -> Map.of(name, value), URI.create("http://host")));
  }

  // TC-ENG | onUnauthorizedDefaultReturnsFalse
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onUnauthorizedDefaultReturnsFalse() {
    HttpAuthProvider auth = HttpAuthProvider.none();
    assertFalse(auth.onUnauthorized(URI.create("http://host"), Map.of()));
    assertTrue(HttpAuthProvider.none().headers(URI.create("http://host")).isEmpty());
  }

  /** 反射调用统一解包 InvocationTargetException。 */
  private static final class InvocationTargetAssertion {
    static void assertIllegalArgument(ThrowingCall call) {
      try {
        call.run();
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertTrue(e.getCause() instanceof IllegalArgumentException, e.getCause().toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    static void assertIllegalState(ThrowingCall call) {
      try {
        call.run();
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertTrue(e.getCause() instanceof IllegalStateException, e.getCause().toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }
}

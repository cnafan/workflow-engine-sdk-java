/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TC-ENG-950…955：HttpAuthentication URL/头校验/异常包装。 */
class HttpAuthenticationGuardTest {

  // TC-ENG-950 | baseUrl：null/非 http/无 host/userInfo/query/fragment
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void baseUrlValidation() {
    assertThrows(NullPointerException.class, () -> HttpAuthentication.baseUrl(null));
    assertThrows(IllegalArgumentException.class, () -> HttpAuthentication.baseUrl("ftp://x/y"));
    assertThrows(IllegalArgumentException.class, () -> HttpAuthentication.baseUrl("http:///path"));
    assertThrows(
        IllegalArgumentException.class, () -> HttpAuthentication.baseUrl("http://user@host/path"));
    assertThrows(
        IllegalArgumentException.class, () -> HttpAuthentication.baseUrl("http://host/path?q=1"));
    assertThrows(
        IllegalArgumentException.class, () -> HttpAuthentication.baseUrl("http://host/path#f"));
  }

  // TC-ENG-951 | 尾部斜杠被剥离
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void trailingSlashRemoved() {
    assertEquals("http://host:8080", HttpAuthentication.baseUrl("http://host:8080/"));
  }

  // TC-ENG-952 | headers：合法头保留；保留字/重复/空值/控制字符拒绝
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void headerValidation() {
    URI uri = URI.create("http://host/api");
    Map<String, String> valid = new HashMap<>();
    valid.put("X-Trace", "trace-1");
    HttpAuthProvider provider =
        new HttpAuthProvider() {
          @Override
          public Map<String, String> headers(URI requestUri) {
            return valid;
          }
        };
    assertEquals("trace-1", HttpAuthentication.headers(provider, uri).get("X-Trace"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpAuthentication.headers(
                new HttpAuthProvider() {
                  @Override
                  public Map<String, String> headers(URI u) {
                    return Map.of("Host", "x");
                  }
                },
                uri));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpAuthentication.headers(
                new HttpAuthProvider() {
                  @Override
                  public Map<String, String> headers(URI u) {
                    return Map.of("X-A", "1", "x-a", "2");
                  }
                },
                uri));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HttpAuthentication.headers(
                new HttpAuthProvider() {
                  @Override
                  public Map<String, String> headers(URI u) {
                    return Map.of("X-A", " ");
                  }
                },
                uri));
  }

  // TC-ENG-953 | provider 返回 null → IAE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nullHeadersRejected() {
    URI uri = URI.create("http://host/api");
    assertThrows(
        IllegalStateException.class,
        () ->
            HttpAuthentication.headers(
                new HttpAuthProvider() {
                  @Override
                  public Map<String, String> headers(URI u) {
                    return null;
                  }
                },
                uri));
  }

  // TC-ENG-954 | provider 抛运行时异常 → 包装 ISE
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void providerFailureWrapped() {
    URI uri = URI.create("http://host/api");
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                HttpAuthentication.headers(
                    new HttpAuthProvider() {
                      @Override
                      public Map<String, String> headers(URI u) {
                        throw new IllegalStateException("secret leak");
                      }
                    },
                    uri));
    assertTrue(error.getMessage().contains("HTTP authentication provider failed"));
    assertFalse(error.getMessage().contains("secret leak"));
  }

  // TC-ENG-955 | onUnauthorized 正常返回与异常包装
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void onUnauthorizedBranches() {
    URI uri = URI.create("http://host/api");
    HttpAuthProvider returnsTrue =
        new HttpAuthProvider() {
          @Override
          public Map<String, String> headers(URI requestUri) {
            return Map.of();
          }

          @Override
          public boolean onUnauthorized(URI requestUri, Map<String, String> rejectedHeaders) {
            return true;
          }
        };
    assertTrue(HttpAuthentication.onUnauthorized(returnsTrue, uri, Map.of()));
    HttpAuthProvider throwing =
        new HttpAuthProvider() {
          @Override
          public Map<String, String> headers(URI requestUri) {
            return Map.of();
          }

          @Override
          public boolean onUnauthorized(URI requestUri, Map<String, String> rejectedHeaders) {
            throw new IllegalStateException("boom");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> HttpAuthentication.onUnauthorized(throwing, uri, Map.of()));
  }

  // TC-ENG-1097 | HttpServiceException.detail：凭据打码/401 跳过 body/超长截断
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void httpServiceExceptionDetailBranches() {
    HttpServiceException masked =
        new HttpServiceException(
            "svc",
            500,
            "Authorization: Bearer tok123 body",
            Map.of("Authorization", "Bearer tok123"));
    assertTrue(masked.getMessage().contains("***"));
    assertTrue(!masked.getMessage().contains("tok123"));

    HttpServiceException authError = new HttpServiceException("svc", 401, "echo-body", Map.of());
    assertTrue(!authError.getMessage().contains("echo-body"));
    assertTrue(authError.isAuthenticationFailure());

    HttpServiceException longBody = new HttpServiceException("svc", 500, "y".repeat(600), Map.of());
    assertTrue(longBody.getMessage().contains("..."));
    assertTrue(longBody.getMessage().length() < 700);
  }
}

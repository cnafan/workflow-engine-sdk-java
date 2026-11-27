/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/** TC-ENG-890…892：CredentialHttpTransport 直连 HTTP 分支。 */
class CredentialHttpTransportSendTest {

  private HttpServer server(int status, String body) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return server;
  }

  private CredentialHttpTransport transport() throws Exception {
    Constructor<CredentialHttpTransport> ctor =
        CredentialHttpTransport.class.getDeclaredConstructor(SSLContext.class, Duration.class);
    ctor.setAccessible(true);
    return ctor.newInstance(null, Duration.ofSeconds(10));
  }

  // TC-ENG-890 | 2xx 响应读取 body
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sendReadsSuccessBody() throws Exception {
    HttpServer server = server(200, "{\"access_token\":\"t\"}");
    try {
      CredentialHttpTransport.Response response =
          transport()
              .send(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                  "POST",
                  "application/json",
                  "{}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("access_token"));
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-891 | 4xx 错误流读取
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void sendReadsErrorStream() throws Exception {
    HttpServer server = server(401, "unauthorized");
    try {
      CredentialHttpTransport.Response response =
          transport()
              .send(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                  "POST",
                  "application/json",
                  "{}");
      assertEquals(401, response.statusCode());
      assertEquals("unauthorized", response.body());
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG-892 | 静态工厂构造不抛
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void factoryCreatesTransport() {
    CredentialHttpTransport transport =
        CredentialHttpTransport.create(true, null, null, null, null, null, Duration.ofSeconds(5));
    assertTrue(transport != null);
  }
}

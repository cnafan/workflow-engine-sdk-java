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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

class CredentialHeaderContributorTest {

  private static AgentCard card(String requirements) throws Exception {
    String json =
        """
        {
          "name":"agent","description":"test","version":"1",
          "capabilities":{"streaming":false},
          "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
          "securitySchemes":{
            "missing":{"httpAuthSecurityScheme":{"scheme":"Bearer"}},
            "first":{"httpAuthSecurityScheme":{"scheme":"Bearer"}},
            "second":{"httpAuthSecurityScheme":{"scheme":"Bearer"}}
          },
          "securityRequirements":%s,
          "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0","url":"https://agent.example/a2a","tenant":""}]
        }
        """
            .formatted(requirements);
    return new ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(json, AgentCard.class);
  }

  private static Map<String, Object> scheme(String url, String header) {
    return Map.of(
        "login_url",
        url,
        "token_field",
        "access_token",
        "request_fields",
        Map.of("username", "user", "password", "password"),
        "auth_header",
        header);
  }

  // TC-ENG | triesLaterOrRequirementWhenEarlierAlternativeIsNotConfigured
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void triesLaterOrRequirementWhenEarlierAlternativeIsNotConfigured() throws Exception {
    HttpServer server = tokenServer();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
      AgentAuthManager manager =
          new AgentAuthManager(
              Map.of("agent", Map.of("second", scheme(url, "X-Second"))),
              HttpClient.newHttpClient());
      CredentialHeaderContributor contributor = new CredentialHeaderContributor(manager, null);

      Map<String, String> headers =
          contributor.contribute(
              card("[{\"schemes\":{\"missing\":{}}},{\"schemes\":{\"second\":{}}}]"),
              "agent",
              Map.of(),
              Map.of());

      assertEquals(Map.of("X-Second", "token"), headers);
    } finally {
      server.stop(0);
    }
  }

  // TC-ENG | satisfiesEverySchemeInAnAndRequirement
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void satisfiesEverySchemeInAnAndRequirement() throws Exception {
    HttpServer server = tokenServer();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
      AgentAuthManager manager =
          new AgentAuthManager(
              Map.of(
                  "agent",
                  Map.of(
                      "first", scheme(url, "X-First"),
                      "second", scheme(url, "X-Second"))),
              HttpClient.newHttpClient());
      CredentialHeaderContributor contributor = new CredentialHeaderContributor(manager, null);

      Map<String, String> headers =
          contributor.contribute(
              card("[{\"schemes\":{\"first\":{},\"second\":{}}}]"), "agent", Map.of(), Map.of());

      assertEquals(Map.of("X-First", "token", "X-Second", "token"), headers);
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer tokenServer() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          byte[] response = "{\"access_token\":\"token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    return server;
  }
}

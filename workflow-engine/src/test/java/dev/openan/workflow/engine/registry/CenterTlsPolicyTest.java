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

package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CenterTlsPolicyTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bothClientsAuthenticateOverTlsAndOptOutIsLocal(boolean wrongSan) throws Exception {
    var context = SSLContext.getDefault();
    var socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
    var hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    String property = System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
    try (var endpoint = endpoint(wrongSan, false)) {
      var auth = HttpAuthProvider.bearer("test.token");
      assertEquals(
          "flow", LoadPsop.load(endpoint.url(), "flow", auth, TlsPolicy.insecure()).getName());
      assertEquals(
          "flow",
          LoadPsop.search(endpoint.url(), "intent", 3, auth, TlsPolicy.insecure())
              .get(0)
              .getWorkflowId());
      var registry = new RegistryClient(endpoint.url(), auth, TlsPolicy.insecure());
      assertEquals(1, registry.fetchAgentCards().size());
      assertEquals("Host", registry.fetchAgentCard("Host").get("name"));
      assertNotNull(registry.registerAgentCard(java.util.Map.of("name", "Host")));
      assertEquals(
          401,
          assertThrows(
                  HttpServiceException.class,
                  () ->
                      new RegistryClient(
                              endpoint.url(), HttpAuthProvider.none(), TlsPolicy.insecure())
                          .fetchAgentCards())
              .getStatusCode());
      assertEquals(
          401,
          assertThrows(
                  HttpServiceException.class,
                  () -> LoadPsop.load(endpoint.url(), "flow", "wrong.token", false))
              .getStatusCode());
      assertThrows(
          Exception.class, () -> new RegistryClient(endpoint.url(), auth).fetchAgentCards());
      assertThrows(
          Exception.class, () -> LoadPsop.load(endpoint.url(), "flow", auth, TlsPolicy.strict()));

      var trusted = TlsPolicy.trustedCa(endpoint.cert().toString());
      if (wrongSan) {
        assertThrows(
            Exception.class,
            () -> new RegistryClient(endpoint.url(), auth, trusted).fetchAgentCards());
        assertThrows(Exception.class, () -> LoadPsop.load(endpoint.url(), "flow", auth, trusted));
        // Old boolean false deliberately remains chain-only and still rejects a wrong hostname.
        assertThrows(
            Exception.class, () -> new RegistryClient(endpoint.url(), false).fetchAgentCards());
      } else {
        assertEquals(1, new RegistryClient(endpoint.url(), auth, trusted).fetchAgentCards().size());
        assertEquals("flow", LoadPsop.load(endpoint.url(), "flow", auth, trusted).getName());
        assertEquals(
            401,
            assertThrows(
                    HttpServiceException.class,
                    () -> new RegistryClient(endpoint.url(), false).fetchAgentCards())
                .getStatusCode());
      }
    }
    assertSame(context, SSLContext.getDefault());
    assertSame(socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory());
    assertSame(hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier());
    assertEquals(
        property, System.getProperty("jdk.internal.httpclient.disableHostnameVerification"));
  }

  // TC-ENG | insecureVerificationRetainsClientIdentityButCannotBypassRequiredMtls
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void insecureVerificationRetainsClientIdentityButCannotBypassRequiredMtls() throws Exception {
    try (var endpoint = endpoint(false, true)) {
      var auth = HttpAuthProvider.bearer("test.token");
      assertThrows(
          Exception.class, () -> LoadPsop.load(endpoint.url(), "flow", auth, TlsPolicy.insecure()));
      assertThrows(
          Exception.class,
          () -> new RegistryClient(endpoint.url(), auth, TlsPolicy.insecure()).fetchAgentCards());
      var insecureIdentity =
          TlsPolicy.insecure(endpoint.cert().toString(), endpoint.key().toString(), null);
      assertEquals("flow", LoadPsop.load(endpoint.url(), "flow", auth, insecureIdentity).getName());
      assertEquals(
          1, new RegistryClient(endpoint.url(), auth, insecureIdentity).fetchAgentCards().size());
      var strictIdentity =
          TlsPolicy.mutualTls(
              endpoint.cert().toString(),
              endpoint.cert().toString(),
              endpoint.key().toString(),
              null);
      assertEquals("flow", LoadPsop.load(endpoint.url(), "flow", auth, strictIdentity).getName());
      assertEquals(
          1, new RegistryClient(endpoint.url(), auth, strictIdentity).fetchAgentCards().size());
    }
    assertThrows(
        IllegalStateException.class,
        () -> TlsPolicy.insecure(directory.resolve("missing.pem").toString(), null, null));
    assertThrows(
        IllegalStateException.class,
        () -> TlsPolicy.trustedCa(directory.resolve("missing-ca.pem").toString()));
  }

  private Endpoint endpoint(boolean wrongSan, boolean clientAuth) throws Exception {
    Path storeFile = directory.resolve("server.p12");
    Path keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
    var process =
        new ProcessBuilder(
                List.of(
                    keytool.toString(),
                    "-genkeypair",
                    "-alias",
                    "test",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-dname",
                    "CN=test",
                    "-validity",
                    "2",
                    "-ext",
                    wrongSan ? "SAN=dns:wrong.example.test" : "SAN=ip:127.0.0.1",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    storeFile.toString(),
                    "-storepass",
                    "test-only-password",
                    "-keypass",
                    "test-only-password",
                    "-noprompt"))
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("keytool.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    var store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(storeFile)) {
      store.load(input, "test-only-password".toCharArray());
    }
    Path cert = directory.resolve("cert.pem");
    Path key = directory.resolve("key.pem");
    Files.writeString(cert, pem("CERTIFICATE", store.getCertificate("test").getEncoded()));
    Files.writeString(
        key,
        pem("PRIVATE KEY", store.getKey("test", "test-only-password".toCharArray()).getEncoded()));
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "test-only-password".toCharArray());
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(context) {
          @Override
          public void configure(HttpsParameters parameters) {
            var ssl = context.getDefaultSSLParameters();
            ssl.setNeedClientAuth(clientAuth);
            parameters.setSSLParameters(ssl);
          }
        });
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int status =
              "Bearer test.token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                  ? 200
                  : 401;
          String path = exchange.getRequestURI().getPath();
          String body =
              path.endsWith("/search")
                  ? "{\"data\":[{\"workflow_id\":\"flow\"}]}"
                  : path.contains("/psop/")
                      ? "{\"data\":{\"name\":\"flow\",\"steps\":[]}}"
                      : "{\"agentCards\":[{\"name\":\"Host\"}]}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new Endpoint(server, cert, key);
  }

  private static String pem(String type, byte[] bytes) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
        + "\n-----END "
        + type
        + "-----\n";
  }

  private record Endpoint(HttpsServer server, Path cert, Path key) implements AutoCloseable {
    String url() {
      return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}

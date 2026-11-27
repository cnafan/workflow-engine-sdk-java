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
import dev.openan.workflow.engine.client.SslContextFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real loopback TLS handshakes; certificates are ephemeral and never added to JVM trust. */
class LoadPsopHttpsTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitOptOutLoadsAndSearchesWithoutTrustFilesAndWithoutGlobalSideEffects(boolean wrongSan)
      throws Exception {
    var defaults = HttpsURLConnection.getDefaultSSLSocketFactory();
    var verifier = HttpsURLConnection.getDefaultHostnameVerifier();
    var defaultContext = SSLContext.getDefault();
    String hostnameProperty =
        System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
    try (var endpoint = endpoint(wrongSan, false)) {
      String baseUrl = endpoint.baseUrl();
      assertEquals("tls-psop", LoadPsop.load(baseUrl, "tls-psop", null, false).getName());
      assertEquals(
          "tls-psop", LoadPsop.search(baseUrl, "complaint", 3, null, false).get(0).getWorkflowId());
      assertThrows(SSLHandshakeException.class, () -> LoadPsop.load(baseUrl, "tls-psop"));
      assertThrows(SSLHandshakeException.class, () -> LoadPsop.search(baseUrl, "complaint"));

      // Even another trust-all connection must still enforce its own hostname policy.
      var independent = (HttpsURLConnection) URI.create(baseUrl + "/").toURL().openConnection();
      independent.setSSLSocketFactory(SslContextFactory.createTrustAll().getSocketFactory());
      independent.setConnectTimeout(3000);
      independent.setReadTimeout(3000);
      try {
        assertThrows(IOException.class, independent::getResponseCode);
      } finally {
        independent.disconnect();
      }
    }
    assertSame(defaults, HttpsURLConnection.getDefaultSSLSocketFactory());
    assertSame(verifier, HttpsURLConnection.getDefaultHostnameVerifier());
    assertSame(defaultContext, SSLContext.getDefault());
    assertEquals(
        hostnameProperty,
        System.getProperty("jdk.internal.httpclient.disableHostnameVerification"));
  }

  // TC-ENG | skippingServerVerificationDoesNotBypassMutualTls
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void skippingServerVerificationDoesNotBypassMutualTls() throws Exception {
    try (var endpoint = endpoint(false, true)) {
      assertThrows(
          IOException.class, () -> LoadPsop.load(endpoint.baseUrl(), "tls-psop", null, false));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void accountLoginAndBothPsopCallsShareExplicitConnectionLocalTls(boolean wrongSan)
      throws Exception {
    var defaults = HttpsURLConnection.getDefaultSSLSocketFactory();
    var verifier = HttpsURLConnection.getDefaultHostnameVerifier();
    try (var endpoint = endpoint(wrongSan, false, true)) {
      var tls = TlsPolicy.insecure();
      var auth =
          new OrchestrationAccountAuth(endpoint.baseUrl(), "admin", "synthetic-password", tls);
      assertEquals(
          "tls-psop",
          LoadPsop.search(endpoint.baseUrl(), "intent", 3, auth, tls).get(0).getWorkflowId());
      assertEquals("tls-psop", LoadPsop.load(endpoint.baseUrl(), "tls-psop", auth, tls).getName());
      var strict =
          new OrchestrationAccountAuth(
              endpoint.baseUrl(), "admin", "synthetic-password", TlsPolicy.strict());
      var error =
          assertThrows(
              IllegalStateException.class,
              () -> LoadPsop.load(endpoint.baseUrl(), "tls-psop", strict, TlsPolicy.strict()));
      assertNull(error.getCause());
      assertFalse(error.getMessage().contains("synthetic-password"));
      // verify=False is not anonymous: TLS succeeds but the API still requires account Bearer.
      assertEquals(
          401,
          assertThrows(
                  HttpServiceException.class,
                  () -> LoadPsop.load(endpoint.baseUrl(), "tls-psop", HttpAuthProvider.none(), tls))
              .getStatusCode());
    }
    assertSame(defaults, HttpsURLConnection.getDefaultSSLSocketFactory());
    assertSame(verifier, HttpsURLConnection.getDefaultHostnameVerifier());
  }

  private Endpoint endpoint(boolean wrongSan, boolean clientAuth) throws Exception {
    return endpoint(wrongSan, clientAuth, false);
  }

  private Endpoint endpoint(boolean wrongSan, boolean clientAuth, boolean accountAuth)
      throws Exception {
    Path keyStoreFile = directory.resolve("server.p12");
    Path keytool =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
    var command =
        new ArrayList<>(
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
                "CN=unrelated.example.test",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                keyStoreFile.toString(),
                "-storepass",
                "test-only-password",
                "-keypass",
                "test-only-password",
                "-noprompt"));
    if (wrongSan) command.addAll(List.of("-ext", "SAN=dns:unrelated.example.test"));
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("keytool.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "keytool timed out");
      assertEquals(
          0, process.exitValue(), () -> "keytool failed; see " + directory.resolve("keytool.log"));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    var store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(keyStoreFile)) {
      store.load(input, "test-only-password".toCharArray());
    }
    var certificate = (X509Certificate) store.getCertificate("test");
    if (wrongSan) assertNotNull(certificate.getSubjectAlternativeNames());
    else assertNull(certificate.getSubjectAlternativeNames());
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "test-only-password".toCharArray());
    var context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
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
          boolean login = exchange.getRequestURI().getPath().equals("/api/v1/auth/token");
          int status =
              accountAuth
                      && !login
                      && !"Bearer tls.session"
                          .equals(exchange.getRequestHeaders().getFirst("Authorization"))
                  ? 401
                  : 200;
          String body =
              login
                  ? "{\"access_token\":\"tls.session\",\"token_type\":\"Bearer\",\"expires_in\":60}"
                  : exchange.getRequestURI().getPath().endsWith("/search")
                      ? "{\"data\":[{\"workflow_id\":\"tls-psop\",\"name\":\"tls-psop\"}]}"
                      : "{\"data\":{\"name\":\"tls-psop\",\"steps\":[]}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return new Endpoint(server);
  }

  private record Endpoint(HttpsServer server) implements AutoCloseable {
    String baseUrl() {
      return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}

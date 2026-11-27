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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 针对回执/解析失败分支的强断言用例（TC-ENG-040…050）。 */
class SslContextFactoryMtlsTest {
  private static final String STORE_PASSWORD = "password123";

  @TempDir static Path tempDir;

  private static Path caPem;
  private static Path clientCertPem;
  private static Path clientKeyPem;

  @BeforeAll
  static void generateFixtures() throws Exception {
    Path caP12 = tempDir.resolve("ca.p12");
    Path clientP12 = tempDir.resolve("client.p12");

    keytool(
        "-genkeypair",
        "-alias",
        "ca",
        "-dname",
        "CN=Test CA",
        "-keyalg",
        "EC",
        "-keysize",
        "256",
        "-validity",
        "1",
        "-keystore",
        caP12.toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        STORE_PASSWORD,
        "-keypass",
        STORE_PASSWORD);
    caPem = tempDir.resolve("ca.pem");
    keytool(
        "-exportcert",
        "-alias",
        "ca",
        "-rfc",
        "-file",
        caPem.toString(),
        "-keystore",
        caP12.toString(),
        "-storepass",
        STORE_PASSWORD);

    keytool(
        "-genkeypair",
        "-alias",
        "client",
        "-dname",
        "CN=Test Client",
        "-keyalg",
        "EC",
        "-keysize",
        "256",
        "-validity",
        "1",
        "-keystore",
        clientP12.toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        STORE_PASSWORD,
        "-keypass",
        STORE_PASSWORD);
    clientCertPem = tempDir.resolve("client-cert.pem");
    keytool(
        "-exportcert",
        "-alias",
        "client",
        "-rfc",
        "-file",
        clientCertPem.toString(),
        "-keystore",
        clientP12.toString(),
        "-storepass",
        STORE_PASSWORD);
    clientKeyPem = tempDir.resolve("client-key.pem");
    writeClientKeyPem(clientP12);
  }

  private static void keytool(String... args) throws Exception {
    String keytool =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                    ? "keytool.exe"
                    : "keytool")
            .toString();
    ProcessBuilder builder = new ProcessBuilder(keytool);
    for (String arg : args) {
      builder.command().add(arg);
    }
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    assertTrue(
        process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0,
        "keytool failed: " + String.join(" ", args) + "\n" + output);
  }

  private static void writeClientKeyPem(Path clientP12) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = new java.io.FileInputStream(clientP12.toFile())) {
      store.load(input, STORE_PASSWORD.toCharArray());
    }
    PrivateKey key = (PrivateKey) store.getKey("client", STORE_PASSWORD.toCharArray());
    assertNotNull(key);
    String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
    Files.writeString(
        clientKeyPem, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
  }

  private static void assertCauseContains(String keyword, Runnable action) {
    try {
      action.run();
    } catch (IllegalStateException caught) {
      assertTrue(
          caught.getCause() != null && caught.getCause().getMessage().contains(keyword),
          "expected cause containing '"
              + keyword
              + "' but was: "
              + (caught.getCause() == null ? "null" : caught.getCause().getMessage()));
      return;
    }
    throw new AssertionError("expected IllegalStateException containing '" + keyword + "'");
  }

  // TC-ENG-040 | 二参重载：verifyServer=false 产出信任所有证书的可用上下文
  //
  // 输入: create(false, null)
  // 预期输出: Optional 非空
  // 断言点: Optional.isPresent；防回归: 信任所有分支被删即失败
  @Test
  void twoArgCreateBuildsTrustAllContext() {
    assertNotNull(SslContextFactory.create(false, null).orElseThrow());
  }

  // TC-ENG-041（不可达）| CA 文件无证书分支被 JDK `No certificate data found` 前置拦截，测试无法触发，见 TEST_DESIGN.md §6

  // TC-ENG-042 | mTLS 仅提供证书或缺私钥时拒绝
  //
  // 输入: 只给 certPath 不给 keyPath
  // 预期输出: 异常原因含 "Both client certificate and private key are required"
  // 断言点: 原因关键字；防回归: 半套 mTLS 配置被接受即失败
  @Test
  void mtlsRequiresBothCertificateAndKey() {
    assertCauseContains(
        "Both client certificate and private key",
        () -> SslContextFactory.create(true, null, clientCertPem.toString(), null, null, null));
  }

  // TC-ENG-043 | mTLS 文件不存在时拒绝
  //
  // 输入: certPath 指向不存在的文件
  // 预期输出: 异常原因含 "mTLS certificate or private-key file not found"
  // 断言点: 原因关键字；防回归: 文件存在性校验被删即失败
  @Test
  void mtlsMissingFilesAreRejected() {
    assertCauseContains(
        "file not found",
        () ->
            SslContextFactory.create(
                true,
                null,
                tempDir.resolve("no-such.pem").toString(),
                clientKeyPem.toString(),
                null,
                null));
  }

  // TC-ENG-044（不可达）| 空证书链分支被 JDK 解析错误前置拦截，见 TEST_DESIGN.md §6

  // TC-ENG-045 | PKCS#1 PEM 被明确拒绝
  //
  // 输入: "-----BEGIN RSA PRIVATE KEY-----" 头
  // 预期输出: 异常原因含 "Only PKCS#8 PEM private keys are supported"
  // 断言点: 原因关键字；防回归: 非 PKCS#8 放行即失败
  @Test
  void pkcs1PemIsRejected() throws Exception {
    Path pkcs1 = tempDir.resolve("pkcs1.pem");
    Files.writeString(
        pkcs1, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
    assertCauseContains(
        "Only PKCS#8",
        () ->
            SslContextFactory.create(
                true, null, clientCertPem.toString(), pkcs1.toString(), null, null));
  }

  // TC-ENG-046 | 加密 PKCS#8 缺密码时拒绝
  //
  // 输入: ENCRYPTED 头 + keyPassword=null
  // 预期输出: 异常原因含 "Encrypted private key requires a password"
  // 断言点: 原因关键字；防回归: 密码守卫被删即失败
  @Test
  void encryptedPkcs8WithoutPasswordIsRejected() throws Exception {
    Path encrypted = tempDir.resolve("encrypted.pem");
    Files.writeString(
        encrypted,
        "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");
    assertCauseContains(
        "requires a password",
        () ->
            SslContextFactory.create(
                true, null, clientCertPem.toString(), encrypted.toString(), null, null));
  }

  // TC-ENG-047 | 三种算法都解析失败的 DER 被拒绝
  //
  // 输入: 非 PEM 的垃圾字节
  // 预期输出: 异常原因含 "Unsupported PKCS#8 private-key algorithm"
  // 断言点: 原因关键字；防回归: 算法循环失败被吞即失败
  @Test
  void unrecognizedDerIsRejected() throws Exception {
    Path garbage = tempDir.resolve("garbage.key");
    Files.write(garbage, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    assertCauseContains(
        "Unsupported PKCS#8",
        () ->
            SslContextFactory.create(
                true, null, clientCertPem.toString(), garbage.toString(), null, null));
  }

  // TC-ENG-048 | 有效 mTLS 配置（keytool 证书 + 真实 EC 私钥）成功加载
  //
  // 输入: keytool 导出的客户端证书 + 同源 PKCS#8 私钥 PEM（含空密码分支）
  // 预期输出: Optional 非空
  // 断言点: isPresent；防回归: KeyManager 装配回归即失败
  @Test
  void validMtlsIdentityLoads() {
    assertNotNull(
        SslContextFactory.create(
                true, null, clientCertPem.toString(), clientKeyPem.toString(), null, null)
            .orElseThrow());
  }

  // TC-ENG-049 | CRL 文件不存在时 fail-closed
  //
  // 输入: crlPath 指向不存在的文件
  // 预期输出: 异常原因含 "CRL file not found"
  // 断言点: 原因关键字；防回归: 缺文件被忽略即失败
  @Test
  void missingCrlFileFailsClosed() {
    assertCauseContains(
        "CRL file not found",
        () ->
            SslContextFactory.create(
                true,
                caPem.toString(),
                null,
                null,
                null,
                tempDir.resolve("no-such.crl").toString()));
  }

  // TC-ENG-050（不可达）| 空 CRL 分支被 JDK `No CRL data found` 前置拦截，见 TEST_DESIGN.md §6
}

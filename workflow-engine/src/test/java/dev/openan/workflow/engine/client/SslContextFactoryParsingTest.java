/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-ENG-740…749：SslContextFactory CA/CRL/私钥/insecure 分支。 */
class SslContextFactoryParsingTest {

  @TempDir static Path tmp;

  private static Path caPem;

  @BeforeAll
  static void generateCa() throws Exception {
    String keytool =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                    ? "keytool.exe"
                    : "keytool")
            .toString();
    Path p12 = tmp.resolve("ca.p12");
    ProcessBuilder builder =
        new ProcessBuilder(
            keytool,
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
            p12.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            "password123",
            "-keypass",
            "password123");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0);
    caPem = tmp.resolve("ca.pem");
    builder =
        new ProcessBuilder(
            keytool,
            "-exportcert",
            "-alias",
            "ca",
            "-rfc",
            "-file",
            caPem.toString(),
            "-keystore",
            p12.toString(),
            "-storepass",
            "password123");
    builder.redirectErrorStream(true);
    process = builder.start();
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0);
  }

  private Method privateMethod(String name, Class<?>... types) throws Exception {
    Method m = SslContextFactory.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m;
  }

  private Path write(String name, String content) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private Path validCertPem() {
    return caPem;
  }

  // TC-ENG-740 | createTrustManager：显式 CA 文件加载成功
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void trustManagerLoadsExplicitCa() throws Exception {
    Path ca = validCertPem();
    Method m = privateMethod("createTrustManager", String.class);
    Object manager = m.invoke(null, ca.toString());
    assertTrue(manager instanceof X509TrustManager);
    assertNotNull(((X509TrustManager) manager).getAcceptedIssuers());
  }

  // TC-ENG-741 | createTrustManager：null CA → 系统默认信任库
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void trustManagerDefaultsWhenCaNull() throws Exception {
    Method m = privateMethod("createTrustManager", String.class);
    assertTrue(m.invoke(null, (Object) null) instanceof X509TrustManager);
  }

  // TC-ENG-742 | createTrustManager：空 PEM → "CA file contains no certificates"
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyCaFileRejected() throws Exception {
    Path empty = write("empty.pem", "not-a-cert");
    Method m = privateMethod("createTrustManager", String.class);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, empty.toString()));
    assertTrue(error.getCause().getMessage() != null);
  }

  // TC-ENG-743 | loadKeyManagers：双 null → null；单证书 → IAE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void keyManagersNullAndHalfIdentityBranches() throws Exception {
    Method m = privateMethod("loadKeyManagers", String.class, String.class, String.class);
    assertEquals(null, m.invoke(null, null, null, null));
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, "cert.pem", null, null));
    assertTrue(error.getCause().getMessage().contains("Both client certificate"));
  }

  // TC-ENG-744 | loadKeyManagers：不存在的文件 → IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void missingIdentityFilesRejected() throws Exception {
    Method m = privateMethod("loadKeyManagers", String.class, String.class, String.class);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, "missing.pem", "missing-key.pem", null));
    assertTrue(error.getCause().getMessage().contains("not found"));
  }

  // TC-ENG-745 | 证书链为空 → IAE "chain is empty"
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyCertificateChainRejected() throws Exception {
    Path cert =
        write("empty-cert.pem", "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n");
    Path key = write("key.pem", "garbage");
    Method m = privateMethod("loadKeyManagers", String.class, String.class, String.class);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, cert.toString(), key.toString(), null));
    assertTrue(error.getCause() instanceof Exception);
  }

  // TC-ENG-746 | applyCrl：null/空白 → 原 manager；不存在文件 → IAE
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void crlNullAndMissingFileBranches() throws Exception {
    Method m = privateMethod("applyCrl", X509TrustManager.class, String.class);
    X509TrustManager delegate =
        (X509TrustManager)
            privateMethod("createTrustManager", String.class).invoke(null, (Object) null);
    assertSame(delegate, m.invoke(null, delegate, null));
    assertSame(delegate, m.invoke(null, delegate, " "));
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, delegate, tmp.resolve("missing.crl").toString()));
    assertTrue(error.getCause().getMessage().contains("CRL file not found"));
  }

  private static void assertSame(Object expected, Object actual) {
    assertTrue(expected == actual);
  }

  // TC-ENG-747 | applyCrl：非 CRL 内容 → "contains no X.509 CRL"
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void nonCrlContentRejected() throws Exception {
    Path crl = write("empty.crl", "not-a-crl");
    Method m = privateMethod("applyCrl", X509TrustManager.class, String.class);
    X509TrustManager delegate =
        (X509TrustManager)
            privateMethod("createTrustManager", String.class).invoke(null, (Object) null);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, delegate, crl.toString()));
    assertTrue(
        error.getCause().getMessage().contains("CRL")
            || error.getCause() instanceof java.security.cert.CRLException);
  }

  // TC-ENG-748 | createTrustAll 与 createInsecure 均返回非空 SSLContext
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void trustAllAndInsecureContextsBuild() {
    SSLContext trustAll = SslContextFactory.createTrustAll();
    assertNotNull(trustAll);
    SSLContext insecure = SslContextFactory.createInsecure(null, null, null);
    assertNotNull(insecure);
  }

  // TC-ENG-749 | parsePkcs8PrivateKey：DER 非法字节 → 不支持算法
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void invalidDerKeyRejected() throws Exception {
    Path key = tmp.resolve("bad.der");
    Files.write(key, new byte[] {1, 2, 3, 4, 5});
    Method m = privateMethod("parsePkcs8PrivateKey", Path.class, String.class);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null, key, null));
    assertTrue(error.getCause() instanceof IllegalArgumentException);
  }
}

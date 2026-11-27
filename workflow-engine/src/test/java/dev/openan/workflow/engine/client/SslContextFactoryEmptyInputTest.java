/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TC-ENG-1116…1118：SslContextFactory 对"零字节输入文件"的守卫。 JDK CertificateFactory 对空流返回空集合（不抛异常），因此三个
 * "contains no certificates / chain is empty / contains no X.509 CRL" 守卫真实可达。
 */
class SslContextFactoryEmptyInputTest {

  @TempDir static Path tmp;
  private static PrivateKey privateKey;
  private static X509TrustManager trustAll;

  @BeforeAll
  static void generateKeyFixture() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    KeyPair keyPair = rsa.generateKeyPair();
    privateKey = keyPair.getPrivate();
    trustAll = trustAllManager();
  }

  private static X509TrustManager trustAllManager() throws Exception {
    java.lang.reflect.Method m = SslContextFactory.class.getDeclaredMethod("trustAllManager");
    m.setAccessible(true);
    return (X509TrustManager) m.invoke(null);
  }

  private static Path emptyFile(String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.createFile(file);
    return file;
  }

  private static Path writePkcs8Pem(String name) throws Exception {
    Path file = tmp.resolve(name);
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .encodeToString(privateKey.getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    Files.write(file, pem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return file;
  }

  // TC-ENG-1116 | 零字节 CA 文件 → 信任集合为空 → "CA file contains no certificates"
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyCaFileRejected() throws Exception {
    Path emptyCa = emptyFile("empty-ca.pem");
    Method m = SslContextFactory.class.getDeclaredMethod("createTrustManager", String.class);
    m.setAccessible(true);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, emptyCa.toString()));
    assertTrue(
        error.getCause().getMessage().contains("CA file contains no certificates"),
        error.getCause().getMessage());
  }

  // TC-ENG-1117 | 零字节证书文件 → 证书链为空 → "mTLS certificate chain is empty"
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyCertificateFileRejected() throws Exception {
    Path emptyCert = emptyFile("empty-cert.pem");
    Path keyPem = writePkcs8Pem("key.pem");
    Method m =
        SslContextFactory.class.getDeclaredMethod(
            "loadKeyManagers", String.class, String.class, String.class);
    m.setAccessible(true);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, emptyCert.toString(), keyPem.toString(), null));
    assertTrue(
        error.getCause().getMessage().contains("mTLS certificate chain is empty"),
        error.getCause().getMessage());
  }

  // TC-ENG-1118 | 零字节 CRL 文件 → CRL 集合为空 → "CRL file contains no X.509 CRL"
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptyCrlFileRejected() throws Exception {
    Path emptyCrl = emptyFile("empty.crl");
    Method m =
        SslContextFactory.class.getDeclaredMethod("applyCrl", X509TrustManager.class, String.class);
    m.setAccessible(true);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> m.invoke(null, trustAll, emptyCrl.toString()));
    assertTrue(
        error.getCause().getMessage().contains("CRL file contains no X.509 CRL"),
        error.getCause().getMessage());
  }
}

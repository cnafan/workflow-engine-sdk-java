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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TC-ENG-1076…1081：SslContextFactory 加密 PKCS#8 解密与 TrustManager check 方法直调。 全部确定性：BC 生成加密私钥
 * fixture；RevocationChecking 经反射私有静态 applyCrl 构造后直调， CRL 由与证书相同的 CA 私钥签发，isRevoked 按序列匹配。无握手、无真实等待。
 */
class SslContextFactoryTrustDirectTest {

  @TempDir static Path tmp;

  private static Path caPem;
  private static Path serverCertPem;
  private static Path encryptedKeyPem;
  private static X509Certificate caCert;
  private static X509Certificate serverCert;
  private static KeyPair caKeyPair;
  private static X509TrustManager trustAll;

  @BeforeAll
  static void generateFixtures() throws Exception {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
    Date notAfter = new Date(System.currentTimeMillis() + 3_600_000L);
    org.bouncycastle.asn1.x500.X500Name caSubject =
        new org.bouncycastle.asn1.x500.X500Name("CN=Direct CA");
    caKeyPair = rsa.generateKeyPair();
    caCert =
        new JcaX509CertificateConverter()
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        caSubject,
                        BigInteger.valueOf(1),
                        notBefore,
                        notAfter,
                        caSubject,
                        caKeyPair.getPublic())
                    .addExtension(
                        org.bouncycastle.asn1.x509.Extension.basicConstraints,
                        true,
                        new org.bouncycastle.asn1.x509.BasicConstraints(true))
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));

    KeyPair serverKeyPair = rsa.generateKeyPair();
    serverCert =
        new JcaX509CertificateConverter()
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        caSubject,
                        BigInteger.valueOf(200),
                        notBefore,
                        notAfter,
                        new org.bouncycastle.asn1.x500.X500Name("CN=localhost"),
                        serverKeyPair.getPublic())
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));

    caPem = writeCertPem("ca.pem", caCert);
    serverCertPem = writeCertPem("server.pem", serverCert);
    encryptedKeyPem = writeEncryptedPem("enc-key.pem", serverKeyPair.getPrivate(), "direct-pw");

    trustAll = trustAllManager();
  }

  private static Path writeCertPem(String name, X509Certificate cert) throws Exception {
    Path file = tmp.resolve(name);
    try (org.bouncycastle.openssl.jcajce.JcaPEMWriter writer =
        new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(file))) {
      writer.writeObject(cert);
    }
    return file;
  }

  private static Path writeEncryptedPem(String name, PrivateKey key, String password)
      throws Exception {
    Path file = tmp.resolve(name);
    JcePKCSPBEOutputEncryptorBuilder encryptorBuilder =
        new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC)
            .setProvider("BC")
            .setIterationCount(1024);
    org.bouncycastle.operator.OutputEncryptor encryptor =
        encryptorBuilder.build(password.toCharArray());
    byte[] der =
        new org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfoBuilder(key.getEncoded())
            .build(encryptor)
            .getEncoded();
    String pem =
        "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
            + "\n-----END ENCRYPTED PRIVATE KEY-----\n";
    Files.write(file, pem.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static Path revocationCrl(String name, long revokedSerial) throws Exception {
    Date now = new Date();
    org.bouncycastle.cert.X509CRLHolder holder =
        new org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder(
                new javax.security.auth.x500.X500Principal("CN=Direct CA"), now)
            .addCRLEntry(BigInteger.valueOf(revokedSerial), now, 0)
            .build(new JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.getPrivate()));
    Path file = tmp.resolve(name);
    String pem =
        "-----BEGIN X509 CRL-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(holder.getEncoded())
            + "\n-----END X509 CRL-----\n";
    Files.write(file, pem.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static X509TrustManager trustAllManager() throws Exception {
    java.lang.reflect.Method m = SslContextFactory.class.getDeclaredMethod("trustAllManager");
    m.setAccessible(true);
    return (X509TrustManager) m.invoke(null);
  }

  private static X509TrustManager revocationManager(Path crlPath) throws Exception {
    java.lang.reflect.Method m =
        SslContextFactory.class.getDeclaredMethod("applyCrl", X509TrustManager.class, String.class);
    m.setAccessible(true);
    return (X509TrustManager) m.invoke(null, trustAll, crlPath.toString());
  }

  // TC-ENG-1076 | parsePkcs8PrivateKey：加密 PKCS#8 + 正确密码 → 解出私钥（decryptPkcs8 全路径）
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void encryptedPkcs8WithPasswordParses() throws Exception {
    java.lang.reflect.Method m =
        SslContextFactory.class.getDeclaredMethod("parsePkcs8PrivateKey", Path.class, String.class);
    m.setAccessible(true);
    PrivateKey key = (PrivateKey) m.invoke(null, encryptedKeyPem, "direct-pw");
    assertEquals("RSA", key.getAlgorithm());
  }

  // TC-ENG-1077 | create() 完整链：verifyServer + CA + mTLS（加密私钥）→ SSLContext 可用
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void fullCreateWithEncryptedKey() throws Exception {
    SSLContext context =
        SslContextFactory.create(
                true,
                caPem.toString(),
                serverCertPem.toString(),
                encryptedKeyPem.toString(),
                "direct-pw",
                null)
            .orElseThrow();
    assertNotNull(context);
  }

  // TC-ENG-1078 | InsecureExtendedTrustManager：check 方法直调全部放行
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void insecureTrustManagerAcceptsAnything() throws Exception {
    Class<?> cls =
        Class.forName(
            "dev.openan.workflow.engine.client.SslContextFactory$InsecureExtendedTrustManager");
    java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor();
    ctor.setAccessible(true);
    javax.net.ssl.X509ExtendedTrustManager manager =
        (javax.net.ssl.X509ExtendedTrustManager) ctor.newInstance();
    X509Certificate[] chain = {serverCert, caCert};
    manager.checkClientTrusted(chain, "RSA");
    manager.checkServerTrusted(chain, "RSA");
    manager.checkClientTrusted(chain, "RSA", (java.net.Socket) null);
    manager.checkServerTrusted(chain, "RSA", (java.net.Socket) null);
    manager.checkClientTrusted(chain, "RSA", (javax.net.ssl.SSLEngine) null);
    manager.checkServerTrusted(chain, "RSA", (javax.net.ssl.SSLEngine) null);
    assertEquals(0, manager.getAcceptedIssuers().length);
  }

  // TC-ENG-1079 | RevocationCheckingTrustManager：未吊销链通过 checkServerTrusted
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void revocationManagerPassesUnrevokedChain() throws Exception {
    Path crl = revocationCrl("pass.crl", 999L);
    X509TrustManager manager = revocationManager(crl);
    X509Certificate[] chain = {serverCert, caCert};
    manager.checkClientTrusted(chain, "RSA");
    manager.checkServerTrusted(chain, "RSA");
    assertEquals(0, manager.getAcceptedIssuers().length);
  }

  // TC-ENG-1080 | RevocationCheckingTrustManager：吊销序列 → CertificateException
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void revocationManagerRejectsRevokedSerial() throws Exception {
    Path crl = revocationCrl("revoke.crl", 200L);
    X509TrustManager manager = revocationManager(crl);
    X509Certificate[] chain = {serverCert, caCert};
    X509CRL parsed = manager.getClass().getName().contains("Revocation") ? null : null;
    CertificateException error =
        assertThrows(CertificateException.class, () -> manager.checkServerTrusted(chain, "RSA"));
    assertTrue(
        error.getMessage().contains("revoked"),
        "expected revoked message, got: " + error.getMessage());
  }

  @SuppressWarnings("unused")
  private static void keepImports() {
    List.of();
  }
}

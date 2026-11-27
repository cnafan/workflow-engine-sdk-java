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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * TC-ENG-054…056：用 BouncyCastle 生成真实证书链与 CRL，验证 RevocationCheckingTrustManager 的吊销判定与
 * InsecureExtendedTrustManager 的信任所有行为。
 */
class SslContextFactoryRevocationTest {
  private static final String STORE_PASSWORD = "store-pw"; // createInsecure 客户端身份加载用
  @TempDir static Path tempDir;

  private static Path caPem;
  private static Path serverCertPem;
  private static Path serverKeyPem;
  private static Path clientCertPem;
  private static Path clientKeyPem;
  private static Path revokedCrl;
  private static SSLContext serverContext;
  private static SSLContext untrustedServerContext;

  @BeforeAll
  static void generateFixtures() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
    Date notAfter = new Date(System.currentTimeMillis() + 3_600_000L);
    X500Name caSubject = new X500Name("CN=Test CA");
    KeyPair caKeyPair = rsa.generateKeyPair();
    X509Certificate caCert =
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

    X500Name serverSubject = new X500Name("CN=localhost");
    KeyPair serverKeyPair = rsa.generateKeyPair();
    X509Certificate serverCert =
        new JcaX509CertificateConverter()
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        caSubject,
                        BigInteger.valueOf(100),
                        notBefore,
                        notAfter,
                        serverSubject,
                        serverKeyPair.getPublic())
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));

    KeyPair clientKeyPair = rsa.generateKeyPair();
    X509Certificate clientCert =
        new JcaX509CertificateConverter()
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        caSubject,
                        BigInteger.valueOf(101),
                        notBefore,
                        notAfter,
                        new X500Name("CN=Test Client"),
                        clientKeyPair.getPublic())
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));

    // CRL：吊销服务端证书序列号 100
    X509CRL crl =
        new JcaX509CRLConverter()
            .getCRL(
                new JcaX509v2CRLBuilder(
                        new javax.security.auth.x500.X500Principal("CN=Test CA"), notBefore)
                    .addCRLEntry(BigInteger.valueOf(100), notBefore, 0)
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));

    caPem = writeCertPem(tempDir.resolve("ca.pem"), caCert);
    serverCertPem = writeCertPem(tempDir.resolve("server-cert.pem"), serverCert);
    clientCertPem = writeCertPem(tempDir.resolve("client-cert.pem"), clientCert);
    serverKeyPem = writeKeyPem(tempDir.resolve("server-key.pem"), serverKeyPair.getPrivate());
    clientKeyPem = writeKeyPem(tempDir.resolve("client-key.pem"), clientKeyPair.getPrivate());
    revokedCrl = tempDir.resolve("revoked.crl");
    try (FileOutputStream output = new FileOutputStream(revokedCrl.toFile())) {
      output.write(crl.getEncoded());
    }

    // 服务端上下文：CA 签发的证书 + 私钥
    KeyStore serverStore = KeyStore.getInstance(KeyStore.getDefaultType());
    serverStore.load(null, null);
    serverStore.setKeyEntry(
        "server", serverKeyPair.getPrivate(), null, new Certificate[] {serverCert});
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(serverStore, null);
    serverContext = SSLContext.getInstance("TLS");
    serverContext.init(kmf.getKeyManagers(), null, null);

    // 不受信任的自签服务端上下文（信任所有测试用）
    KeyPair untrustedKeyPair = rsa.generateKeyPair();
    X509Certificate untrustedCert =
        new JcaX509CertificateConverter()
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        new X500Name("CN=Untrusted"),
                        BigInteger.valueOf(2),
                        notBefore,
                        notAfter,
                        new X500Name("CN=Untrusted"),
                        untrustedKeyPair.getPublic())
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(untrustedKeyPair.getPrivate())));
    KeyStore untrustedStore = KeyStore.getInstance(KeyStore.getDefaultType());
    untrustedStore.load(null, null);
    untrustedStore.setKeyEntry(
        "untrusted", untrustedKeyPair.getPrivate(), null, new Certificate[] {untrustedCert});
    KeyManagerFactory untrustedKmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    untrustedKmf.init(untrustedStore, null);
    untrustedServerContext = SSLContext.getInstance("TLS");
    untrustedServerContext.init(untrustedKmf.getKeyManagers(), null, null);
  }

  private static Path writeCertPem(Path path, X509Certificate certificate) throws Exception {
    String base64 =
        Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(certificate.getEncoded());
    Files.writeString(
        path, "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n");
    return path;
  }

  private static Path writeKeyPem(Path path, PrivateKey privateKey) throws Exception {
    String base64 =
        Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(privateKey.getEncoded());
    Files.writeString(
        path, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
    return path;
  }

  private static Thread acceptAndHandshake(SSLServerSocket serverSocket) {
    Thread thread =
        new Thread(
            () -> {
              try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                accepted.setSoTimeout(10_000);
                accepted.startHandshake();
              } catch (Exception ignored) {
                // 客户端在吊销场景主动断开；服务端侧异常无关紧要
              }
            });
    thread.start();
    return thread;
  }

  // TC-ENG-054 | 带 CRL 的客户端上下文装配成功（applyCrl 快乐路径）
  //
  // 输入: BouncyCastle 生成的真实 PEM 证书链 + DER CRL
  // 预期输出: Optional 非空
  // 断言点: isPresent；防回归: CRL 解析装配被删即失败
  @Test
  void crlConfigurationLoads() {
    assertNotNull(
        SslContextFactory.create(true, caPem.toString(), null, null, null, revokedCrl.toString())
            .orElseThrow());
  }

  // TC-ENG-055 | 吊销端到端：被吊销证书握手失败且原因含 revoked
  //
  // 输入: CA 签发并吊销的服务端证书；带 CRL 的客户端上下文
  // 预期输出: SSLHandshakeException，原因链含 CertificateException("...revoked...")
  // 断言点: 异常类型 + 原因关键字；防回归: 吊销检查被删即失败
  @Test
  @Timeout(60)
  void revokedCertificateIsRejectedDuringHandshake() throws Exception {
    SSLContext clientWithCrl =
        SslContextFactory.create(true, caPem.toString(), null, null, null, revokedCrl.toString())
            .orElseThrow();
    try (SSLServerSocket serverSocket =
        (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0)) {
      serverSocket.setSoTimeout(10_000);
      SSLSocket client = (SSLSocket) clientWithCrl.getSocketFactory().createSocket();
      client.connect(
          new java.net.InetSocketAddress("127.0.0.1", serverSocket.getLocalPort()), 10_000);
      client.setSoTimeout(10_000);
      Thread serverThread = acceptAndHandshake(serverSocket);
      CertificateException revoked = null;
      try {
        client.startHandshake();
      } catch (SSLHandshakeException error) {
        Throwable cause = error.getCause();
        while (cause != null && !(cause instanceof CertificateException)) {
          cause = cause.getCause();
        }
        revoked = (CertificateException) cause;
      } finally {
        client.close();
      }
      serverThread.join(10_000);
      assertTrue(
          revoked != null && revoked.getMessage().contains("revoked"),
          "expected revoked rejection, got: " + revoked);
    }
  }

  // TC-ENG-056 | 未吊销的合法证书链握手成功（吊销循环放行分支）
  //
  // 输入: 同一 CRL 但服务端证书未被吊销（客户端证书充当前端）
  // 预期输出: 握手完成且能取到对端证书
  // 断言点: 会话建立；防回归: CRL 循环误杀未列出证书即失败
  @Test
  @Timeout(60)
  void nonRevokedCertificateStillPassesWithCrl() throws Exception {
    // 服务端改用客户端证书（未被吊销）+ 同一把 CRL
    KeyStore clientStore = KeyStore.getInstance(KeyStore.getDefaultType());
    clientStore.load(null, null);
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    // 复用已生成的客户端证书与私钥
    PrivateKey clientPrivate = (PrivateKey) pemToPrivateKey(clientKeyPem);
    X509Certificate clientFront = pemToCertificate(clientCertPem);
    clientStore.setKeyEntry("front", clientPrivate, null, new Certificate[] {clientFront});
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(clientStore, null);
    SSLContext serverContextWithClientCert = SSLContext.getInstance("TLS");
    serverContextWithClientCert.init(kmf.getKeyManagers(), null, null);

    SSLContext clientWithCrl =
        SslContextFactory.create(true, caPem.toString(), null, null, null, revokedCrl.toString())
            .orElseThrow();
    try (SSLServerSocket serverSocket =
        (SSLServerSocket)
            serverContextWithClientCert.getServerSocketFactory().createServerSocket(0)) {
      serverSocket.setSoTimeout(10_000);
      SSLSocket client = (SSLSocket) clientWithCrl.getSocketFactory().createSocket();
      client.connect(
          new java.net.InetSocketAddress("127.0.0.1", serverSocket.getLocalPort()), 10_000);
      client.setSoTimeout(10_000);
      Thread serverThread = acceptAndHandshake(serverSocket);
      client.startHandshake();
      assertTrue(client.getSession().getPeerCertificates().length > 0);
      client.close();
      serverThread.join(10_000);
    }
  }

  // TC-ENG-057 | createInsecure 信任所有证书：自签不受信服务端握手成功
  //
  // 输入: createInsecure(客户端证书, 私钥, 密码) 客户端 + 默认上下文自签服务端
  // 预期输出: 握手完成（InsecureExtendedTrustManager 各 check 方法被 JSSE 调用）
  // 断言点: 会话建立；防回归: 信任所有方法实现被删即失败
  @Test
  @Timeout(60)
  void insecureContextAcceptsUntrustedServerWithClientIdentity() throws Exception {
    SSLContext insecure =
        SslContextFactory.createInsecure(
            clientCertPem.toString(), clientKeyPem.toString(), STORE_PASSWORD);
    assertNotNull(insecure);
    try (SSLServerSocket serverSocket =
        (SSLServerSocket) untrustedServerContext.getServerSocketFactory().createServerSocket(0)) {
      serverSocket.setSoTimeout(10_000);
      SSLSocket client = (SSLSocket) insecure.getSocketFactory().createSocket();
      client.connect(
          new java.net.InetSocketAddress("127.0.0.1", serverSocket.getLocalPort()), 10_000);
      client.setSoTimeout(10_000);
      Thread serverThread = acceptAndHandshake(serverSocket);
      client.startHandshake();
      assertTrue(client.getSession().getPeerCertificates().length > 0);
      client.close();
      serverThread.join(10_000);
    }
  }

  // TC-ENG-058 | createInsecure 半套客户端身份被拒绝
  //
  // 输入: 只给 certPath 不给 keyPath
  // 预期输出: IllegalStateException，原因含 "Both client certificate and private key"
  // 断言点: 原因关键字；防回归: 半套配置被接受即失败
  @Test
  void insecureWithoutKeyIsRejected() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> SslContextFactory.createInsecure(clientCertPem.toString(), null, null));
    assertTrue(error.getCause().getMessage().contains("Both client certificate and private key"));
  }

  private static PrivateKey pemToPrivateKey(Path pem) throws Exception {
    String body =
        Files.readString(pem)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    var spec = new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(body));
    return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
  }

  private static X509Certificate pemToCertificate(Path pem) throws Exception {
    String body =
        Files.readString(pem)
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "");
    var factory = java.security.cert.CertificateFactory.getInstance("X.509");
    return (X509Certificate)
        factory.generateCertificate(
            new java.io.ByteArrayInputStream(Base64.getDecoder().decode(body)));
  }
}

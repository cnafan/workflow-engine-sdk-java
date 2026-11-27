/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-ENG-1121 | GRPC mTLS：加密 PKCS#8 私钥 + 密码 → keyManager 密码分支真实解析。 */
class GrpcChannelEncryptedKeyTest {

  @TempDir static Path tmp;
  private static Path certPem;
  private static Path encryptedKeyPem;

  @BeforeAll
  static void generateFixtures() throws Exception {
    java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    KeyPair keyPair = rsa.generateKeyPair();
    PrivateKey privateKey = keyPair.getPrivate();

    org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder =
        new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            new org.bouncycastle.asn1.x500.X500Name("CN=Grpc Test"),
            java.math.BigInteger.valueOf(1),
            new java.util.Date(System.currentTimeMillis() - 60_000L),
            new java.util.Date(System.currentTimeMillis() + 3_600_000L),
            new org.bouncycastle.asn1.x500.X500Name("CN=Grpc Test"),
            keyPair.getPublic());
    var cert =
        new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(privateKey)));

    certPem = tmp.resolve("grpc-cert.pem");
    Files.writeString(
        certPem,
        "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .encodeToString(cert.getEncoded())
            + "\n-----END CERTIFICATE-----\n");

    JcePKCSPBEOutputEncryptorBuilder encryptorBuilder =
        new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC)
            .setProvider("BC")
            .setIterationCount(1024);
    var encryptor = encryptorBuilder.build("grpc-pw".toCharArray());
    byte[] der =
        new org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfoBuilder(privateKey.getEncoded())
            .build(encryptor)
            .getEncoded();
    encryptedKeyPem = tmp.resolve("grpc-enc-key.pem");
    Files.writeString(
        encryptedKeyPem,
        "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .encodeToString(der)
            + "\n-----END ENCRYPTED PRIVATE KEY-----\n");
  }

  // TC-ENG-1121 | createGrpcChannel：加密私钥 + 密码 → keyManager(File, File, String) 分支解析成功
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void encryptedKeyWithPasswordBuildsChannel() throws Exception {
    DefaultA2AJavaClientRuntime runtime =
        new DefaultA2AJavaClientRuntime(
            true,
            null,
            certPem.toString(),
            encryptedKeyPem.toString(),
            "grpc-pw",
            null,
            5L,
            "GRPC",
            4,
            16,
            256);
    Method m =
        DefaultA2AJavaClientRuntime.class.getDeclaredMethod("createGrpcChannel", String.class);
    m.setAccessible(true);
    Object channel = m.invoke(runtime, "127.0.0.1:1");
    assertNotNull(channel);
    if (channel instanceof io.grpc.ManagedChannel managed) {
      managed.shutdownNow();
    }
    runtime.close();
    assertTrue(true);
  }
}

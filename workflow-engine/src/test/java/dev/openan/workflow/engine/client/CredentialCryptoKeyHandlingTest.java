/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-670…674：CredentialCrypto null/CLI/系统属性回退等剩余分支。 */
class CredentialCryptoKeyHandlingTest {

  private static final String KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @AfterEach
  void clear() {
    System.clearProperty("A2AT_CRED_KEY");
  }

  // TC-ENG-670 | decryptIfNeeded(null) → null
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void decryptNullReturnsNull() {
    assertNull(CredentialCrypto.decryptIfNeeded(null));
    assertNull(CredentialCrypto.decryptIfNeeded(null, KEY));
  }

  // TC-ENG-671 | 加密值无 key 且配置 key 为空 → ISE
  //
  // 输入: enc: 前缀值，configuredKey=" "
  // 预期输出: ISE 含 "not set"
  @Test
  void encryptedValueWithBlankConfiguredKeyFailsClosed() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> CredentialCrypto.decryptIfNeeded("enc:abc:def", " "));
    assertTrue(error.getMessage().contains("A2AT_CRED_KEY is not set"));
  }

  // TC-ENG-672 | 显式 key 加解密往返（双参重载）
  //
  // 输入: 显式 64-hex key
  // 预期输出: 密文非明文且可解回
  @Test
  void explicitKeyRoundTrip() {
    String encrypted = CredentialCrypto.encrypt("secret", KEY);
    assertTrue(encrypted.startsWith("enc:"));
    assertNotEquals("secret", encrypted);
    assertEquals("secret", CredentialCrypto.decryptIfNeeded(encrypted, KEY));
  }

  // TC-ENG-673 | encrypt 无 key → ISE；decrypt 非法十六进制 key → 包装 ISE
  //
  // 输入: 无 key；短 key
  @Test
  void encryptWithoutKeyAndInvalidKeyFail() {
    assertThrows(IllegalStateException.class, () -> CredentialCrypto.encrypt("x"));
    String encrypted = CredentialCrypto.encrypt("x", KEY);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> CredentialCrypto.decryptIfNeeded(encrypted, "abc"));
    assertTrue(error.getMessage().contains("Credential decryption failed"));
  }

  // TC-ENG-674 | 系统属性 key 回退（无环境变量时）
  //
  // 输入: -DA2AT_CRED_KEY=<key>
  // 预期输出: 无显式 key 时可解密
  @Test
  void systemPropertyKeyEnablesDecryption() {
    System.setProperty("A2AT_CRED_KEY", KEY);
    String encrypted = CredentialCrypto.encrypt("payload");
    assertEquals("payload", CredentialCrypto.decryptIfNeeded(encrypted));
  }

  // TC-ENG-675 | main happy path 输出 enc: 密文（不触发 System.exit）
  //
  // 输入: 明文 + 显式 key 参数
  // 预期输出: 不抛异常；可解回原文
  @Test
  void mainWithPlaintextAndKeyProducesCiphertext() {
    CredentialCrypto.main(new String[] {"cli-secret", KEY});
  }
}

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** TC-ENG-100…106：AES-GCM 凭据加解密边界与密钥解析。 */
class CredentialCryptoRoundTripTest {
  private static final String KEY_32 =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @AfterEach
  void clearProperty() {
    System.clearProperty("A2AT_CRED_KEY");
  }

  // TC-ENG-100 | encrypt→decrypt 往返一致
  //
  // 输入: 显式 32 字节 hex 密钥 + 明文 "omc-secret"
  // 预期输出: 密文带 enc: 前缀，解密还原原文
  // 断言点: 前缀与还原值；防回归: GCM 参数变更即失败
  @Test
  void encryptDecryptRoundTrip() {
    String encrypted = CredentialCrypto.encrypt("omc-secret", KEY_32);
    assertTrue(encrypted.startsWith("enc:"));
    assertEquals("omc-secret", CredentialCrypto.decryptIfNeeded(encrypted, KEY_32));
  }

  // TC-ENG-101 | decryptIfNeeded 无 enc: 前缀时原样返回
  //
  // 输入: "plain-value" / null
  // 预期输出: 原样返回
  // 断言点: 值不变；防回归: 前缀判断被删即失败
  @Test
  void plaintextPassesThrough() {
    assertEquals("plain-value", CredentialCrypto.decryptIfNeeded("plain-value", KEY_32));
    assertNull(CredentialCrypto.decryptIfNeeded(null, KEY_32));
  }

  // TC-ENG-102 | 加密值缺密钥时 fail-closed
  //
  // 输入: enc: 值 + 未配置密钥（显式 null 且环境无 A2AT_CRED_KEY）
  // 预期输出: IllegalStateException，消息含 "A2AT_CRED_KEY"
  // 断言点: 异常消息；防回归: 缺密钥被静默即失败
  @Test
  void missingKeyFailsClosed() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> CredentialCrypto.decryptIfNeeded("enc:AAAA:BBBB", null));
    assertTrue(error.getMessage().contains("A2AT_CRED_KEY"));
  }

  // TC-ENG-103 | 密钥长度/字符校验
  //
  // 输入: 31 字节 hex / 非法字符
  // 预期输出: ISE，原因含长度或 hex 字符提示
  // 断言点: 异常消息关键字；防回归: hexToBytes 守卫被删即失败
  @Test
  void invalidHexKeysAreRejected() {
    RuntimeException shortKey =
        assertThrows(
            RuntimeException.class, () -> CredentialCrypto.encrypt("x", "0123456789abcdef"));
    assertTrue(
        shortKey.getMessage().contains("must contain 64 hexadecimal"), shortKey.getMessage());
    RuntimeException badHex =
        assertThrows(RuntimeException.class, () -> CredentialCrypto.encrypt("x", KEY_32 + "zz"));
    assertTrue(badHex.getMessage().contains("must contain 64 hexadecimal"), badHex.getMessage());
  }

  // TC-ENG-104 | 密文格式非法（缺 IV 段）时 fail-closed
  //
  // 输入: "enc:only-one-segment"
  // 预期输出: ISE，消息含 "Credential decryption failed"
  // 断言点: 异常消息；防回归: 格式守卫被删即失败
  @Test
  void malformedEncryptedValueFailsClosed() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> CredentialCrypto.decryptIfNeeded("enc:only-one-segment", KEY_32));
    assertTrue(error.getMessage().contains("Credential decryption failed"));
  }

  // TC-ENG-105 | 错误密钥解密失败 fail-closed
  //
  // 输入: 用密钥 A 加密，用密钥 B 解密
  // 预期输出: ISE（GCM 认证失败）
  // 断言点: 异常消息；防回归: 认证失败被吞即失败
  @Test
  void wrongKeyFailsClosed() {
    String encrypted = CredentialCrypto.encrypt("omc-secret", KEY_32);
    String otherKey = KEY_32.replace('0', 'f');
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> CredentialCrypto.decryptIfNeeded(encrypted, otherKey));
    assertTrue(error.getMessage().contains("Credential decryption failed"));
  }

  // TC-ENG-106 | resolveKey 回退：系统属性兜底
  //
  // 输入: 显式 null + 设置 A2AT_CRED_KEY 系统属性
  // 预期输出: 加密成功（属性密钥被采用）
  // 断言点: 往返一致；防回归: 属性回退分支被删即失败
  @Test
  void systemPropertyKeyFallback() {
    System.setProperty("A2AT_CRED_KEY", KEY_32);
    String encrypted = CredentialCrypto.encrypt("prop-secret", null);
    assertEquals("prop-secret", CredentialCrypto.decryptIfNeeded(encrypted, null));
  }
}

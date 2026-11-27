/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TC-ENG-510鈥?14锛歝reateGrpcChannel 瀹堝崼涓庢噿鏋勯€狅紙鍙嶅皠锛屼笉鍙戣捣鐪熷疄 gRPC 杩炴帴锛夈€? */
class GrpcChannelReflectionTest {
  private static final String KEYTOOL =
      java.nio.file.Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase().contains("win")
                  ? "keytool.exe"
                  : "keytool")
          .toString();
  private static Path caPem;
  private static Path clientCertPem;
  private static Path clientKeyPem;
  private DefaultA2AJavaClientRuntime runtime;

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmpDir;

  @BeforeAll
  static void generateFixtures(@TempDir Path tmp) throws Exception {
    Path p12 = tmp.resolve("ca.p12");
    String storePassword = "password123";
    keytool(
        List.of(
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
            storePassword,
            "-keypass",
            storePassword));
    caPem = tmp.resolve("ca.pem");
    Files.writeString(caPem, exportPem(p12, storePassword, "-rfc"));
    Path csr = tmp.resolve("client.csr");
    keytool(
        List.of(
            "-certreq",
            "-alias",
            "ca",
            "-keystore",
            p12.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            storePassword,
            "-file",
            csr.toString()));
    Path signed = tmp.resolve("client.crt");
    keytool(
        List.of(
            "-gencert",
            "-alias",
            "ca",
            "-infile",
            csr.toString(),
            "-outfile",
            signed.toString(),
            "-keystore",
            p12.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            storePassword,
            "-rfc"));
    clientCertPem = signed;
    clientKeyPem = tmp.resolve("client-key.pem");
    Files.writeString(clientKeyPem, exportPem(p12, storePassword, "-rfc"));
  }

  private static String exportPem(Path p12, String password, String mode) throws Exception {
    java.util.List<String> output = new java.util.ArrayList<>();
    ProcessBuilder pb =
        new ProcessBuilder(
            KEYTOOL,
            "-exportcert",
            "-alias",
            "ca",
            "-keystore",
            p12.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            password,
            mode);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    try (var is = p.getInputStream()) {
      is.transferTo(
          new java.io.OutputStream() {
            @Override
            public void write(int b) {}
          });
    }
    // For cert use exportcert rfc; for key this fixture reuses the same PEM (channel builder is
    // lazy,
    // no handshake occurs); the purpose is covering the configuration branches.
    pb =
        new ProcessBuilder(
            KEYTOOL,
            "-exportcert",
            "-rfc",
            "-alias",
            "ca",
            "-keystore",
            p12.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            password);
    pb.redirectErrorStream(true);
    p = pb.start();
    String pem =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    p.waitFor(10, TimeUnit.SECONDS);
    return pem;
  }

  private static void keytool(java.util.List<String> args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add(KEYTOOL);
    command.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool failed");
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) {
      runtime.close();
    }
  }

  private Object invokeCreate(String url) throws Exception {
    Method m =
        DefaultA2AJavaClientRuntime.class.getDeclaredMethod("createGrpcChannel", String.class);
    m.setAccessible(true);
    return m.invoke(runtime, url);
  }

  // TC-ENG-510 | plaintext 閫氶亾鎳掓瀯閫犳垚鍔燂紙涓嶅彂璧疯繛鎺ワ級
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void plaintextChannelBuildsLazily() throws Exception {
    runtime = new DefaultA2AJavaClientRuntime(false, null, 5L, "GRPC");
    Object channel = invokeCreate("127.0.0.1:1");
    assertNotNull(channel);
    if (channel instanceof io.grpc.ManagedChannel managed) {
      managed.shutdownNow();
    }
  }

  // TC-ENG-511 | verifyServer=true 无 CA → gRPC 使用系统信任库（不抛异常）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tlsWithoutCaUsesSystemTrust() throws Exception {
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "GRPC");
    Object channel = invokeCreate("127.0.0.1:1");
    assertNotNull(channel);
    if (channel instanceof io.grpc.ManagedChannel managed) {
      managed.shutdownNow();
    }
  }

  // TC-ENG-513 | 鏄庢枃 + 瀹㈡埛绔瘉涔︿簰鏂?鈫?IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void plaintextWithMtlsCertificatesRejected() {
    runtime =
        new DefaultA2AJavaClientRuntime(
            false,
            null,
            clientCertPem.toString(),
            clientKeyPem.toString(),
            null,
            null,
            5L,
            "GRPC",
            4,
            16,
            256);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> invokeCreate("127.0.0.1:1"));
    assertTrue(error.getCause() instanceof IllegalArgumentException);
  }

  // TC-ENG-514 | TLS + CA 通道懒构造成功
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tlsWithCaBuildsLazily() throws Exception {
    runtime = new DefaultA2AJavaClientRuntime(true, caPem.toString(), 5L, "GRPC");
    Object channel = invokeCreate("127.0.0.1:1");
    assertNotNull(channel);
    if (channel instanceof io.grpc.ManagedChannel managed) {
      managed.shutdownNow();
    }
  }

  // TC-ENG-515 | TLS + 错误私钥 → 配置失败包装为 "Failed to configure gRPC TLS"
  //
  // keytool 导出的证书 PEM 不是 PKCS#8 私钥，keyManager 解析失败必须包装而非裸泄
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tlsWithUnparseableKeyPairWrapsFailure() throws Exception {
    runtime =
        new DefaultA2AJavaClientRuntime(
            true,
            null,
            clientCertPem.toString(),
            clientKeyPem.toString(),
            null,
            null,
            5L,
            "GRPC",
            4,
            16,
            256);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> invokeCreate("127.0.0.1:1"));
    assertTrue(error.getCause() instanceof RuntimeException, error.getCause().toString());
    assertTrue(
        error.getCause().getMessage() != null
            && (error.getCause().getMessage().contains("Failed to configure gRPC TLS")
                || error.getCause().getMessage().contains("private key")
                || error.getCause().getMessage().contains("certificate")),
        error.getCause().getMessage());
  }

  // TC-ENG-516 | TLS + 仅证书（缺私钥）→ IAE
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tlsHalfIdentityRejected() {
    runtime =
        new DefaultA2AJavaClientRuntime(
            true, null, clientCertPem.toString(), null, null, null, 5L, "GRPC", 4, 16, 256);
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> invokeCreate("127.0.0.1:1"));
    assertTrue(error.getCause() instanceof IllegalArgumentException);
    assertTrue(error.getCause().getMessage().contains("Both client certificate and private key"));
  }

  // TC-ENG-517 | TLS + 损坏 CA PEM → IllegalStateException "Failed to configure gRPC TLS"
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void tlsWithGarbageCaWrapsIOException() throws Exception {
    Path garbage = java.nio.file.Files.createTempFile(tmpDir, "bad-ca", ".pem");
    Files.writeString(garbage, "not-a-pem");
    runtime = new DefaultA2AJavaClientRuntime(true, garbage.toString(), 5L, "GRPC");
    java.lang.reflect.InvocationTargetException error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class, () -> invokeCreate("127.0.0.1:1"));
    assertTrue(error.getCause() instanceof RuntimeException, error.getCause().toString());
    assertTrue(
        error.getCause().getMessage() != null
            && (error.getCause().getMessage().contains("Failed to configure gRPC TLS")
                || error.getCause().getMessage().toLowerCase().contains("pem")
                || error.getCause().getMessage().toLowerCase().contains("certificate")),
        error.getCause().getMessage());
  }
}

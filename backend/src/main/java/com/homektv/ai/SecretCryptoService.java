package com.homektv.ai;

import com.homektv.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

@Service
public class SecretCryptoService {
    private static final int GCM_TAG_BITS = 128;
    private final AppProperties properties;
    private volatile byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCryptoService(AppProperties properties) {
        this.properties = properties;
    }

    public EncryptedValue encrypt(String name, String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (Exception e) {
            throw new IllegalStateException("敏感配置加密失败", e);
        }
    }

    public String decrypt(String name, byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(), "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecretUnreadableException(
                    "敏感配置解密失败，当前主密钥（" + describeKeySource() + "）与写入时不一致，请重新保存该项配置。", e);
        }
    }

    /**
     * 已保存的密文无法用当前主密钥解密。
     *
     * <p>典型成因是主密钥来源发生变化：例如先在容器内（{@code /data/secrets/config.key}）保存过密钥，
     * 之后改为本地运行（回落到 {@code ./data/secrets/config.key}），或设置了不同的
     * {@code KTV_CONFIG_MASTER_KEY}。此时旧密文永远解不开，只能重新填写并保存。
     *
     * <p>这是可预期的运维状态而非程序缺陷，因此调用方应降级处理并向用户给出可操作的提示，
     * 而不是让整个接口 500。
     */
    public static class SecretUnreadableException extends IllegalStateException {
        public SecretUnreadableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 当前主密钥来源描述，用于错误提示与排查（不泄露密钥内容）。 */
    public String describeKeySource() {
        String environment = System.getenv("KTV_CONFIG_MASTER_KEY");
        if (environment != null && !environment.isBlank()) return "环境变量 KTV_CONFIG_MASTER_KEY";
        return "密钥文件 " + Path.of(properties.getConfigMasterKeyPath()).toAbsolutePath().normalize();
    }

    private byte[] loadMasterKey(AppProperties properties) {
        String environment = System.getenv("KTV_CONFIG_MASTER_KEY");
        if (environment != null && !environment.isBlank()) return normalizeKey(environment.trim());
        Path keyPath = Path.of(properties.getConfigMasterKeyPath()).toAbsolutePath().normalize();
        try {
            if (Files.exists(keyPath)) return normalizeKey(Files.readString(keyPath).trim());
            try {
                Files.createDirectories(keyPath.getParent());
            } catch (Exception unavailable) {
                // Local development may not be allowed to create /data; retain the
                // same permissions policy under the configured application data path.
                keyPath = Path.of(properties.getDataPath(), "secrets", "config.key").toAbsolutePath().normalize();
                Files.createDirectories(keyPath.getParent());
            }
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            String encoded = Base64.getEncoder().encodeToString(generated);
            try {
                Files.writeString(keyPath, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                return normalizeKey(Files.readString(keyPath).trim());
            }
            try {
                Files.setPosixFilePermissions(keyPath, Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows/non-POSIX filesystems do not expose POSIX permissions.
            }
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取或生成配置主密钥：" + keyPath, e);
        }
    }

    private byte[] masterKey() {
        byte[] current = masterKey;
        if (current != null) return current;
        synchronized (this) {
            if (masterKey == null) masterKey = loadMasterKey(properties);
            return masterKey;
        }
    }

    private byte[] normalizeKey(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) { }
        try {
            byte[] decoded = HexFormat.of().parseHex(value);
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) { }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化配置主密钥", e);
        }
    }

    public record EncryptedValue(byte[] ciphertext, byte[] nonce) { }
}

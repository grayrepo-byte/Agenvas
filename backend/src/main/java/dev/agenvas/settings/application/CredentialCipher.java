package dev.agenvas.settings.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 使用仅由部署环境提供的 AES-256-GCM 主密钥加密 Provider 凭证，并将密文绑定到配置身份。 */
@Component
public class CredentialCipher {

    /** GCM 每次加密使用的随机 nonce 长度。 */
    private static final int NONCE_BYTES = 12;
    /** GCM 完整性认证标签长度；密文中包含此标签。 */
    private static final int TAG_BITS = 128;
    /** 当前密钥；未配置主密钥时为 null，密钥相关操作会显式失败。 */
    private final SecretKeySpec key;
    /** 当前密钥版本号，写入每条密文记录以支持轮换。 */
    private final int keyVersion;
    /** 仅供解密历史配置的旧密钥环，不能用于创建新密文。 */
    private final Map<Integer, SecretKeySpec> previousKeys;
    /** 为每次加密生成独立 nonce，避免同一密钥下重复使用。 */
    private final SecureRandom random = new SecureRandom();

    /** 解析当前密钥与旧密钥环，并拒绝重复、非法或非递增的历史版本。 */
    public CredentialCipher(CredentialProperties properties) {
        if (properties.keyVersion() < 1) {
            throw new IllegalArgumentException("Credential key version must be positive");
        }
        this.keyVersion = properties.keyVersion();
        String encoded = properties.masterKeyBase64();
        if (encoded == null || encoded.isBlank()) {
            this.key = null;
        } else {
            this.key = decodeKey(encoded);
        }
        Map<Integer, SecretKeySpec> old = new HashMap<>();
        String previous = properties.previousKeys();
        if (previous != null && !previous.isBlank()) {
            for (String entry : previous.split(",", -1)) {
                String[] parts = entry.trim().split("=", 2);
                int version;
                try {
                    version = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Previous credential key version is invalid");
                }
                if (parts.length != 2 || parts[1].isBlank() || version < 1
                        || version >= keyVersion || old.containsKey(version)) {
                    throw new IllegalArgumentException("Previous credential keyring is invalid");
                }
                old.put(version, decodeKey(parts[1].trim()));
            }
        }
        this.previousKeys = Map.copyOf(old);
    }

    /** 解码并校验恰为 256 位的 Base64 AES 密钥，随后清除临时字节数组。 */
    private SecretKeySpec decodeKey(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Credential master key must be Base64", invalid);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("Credential master key must contain 32 bytes");
        }
        SecretKeySpec parsed = new SecretKeySpec(decoded, "AES");
        java.util.Arrays.fill(decoded, (byte) 0);
        return parsed;
    }

    /** 为每个不可变配置版本使用独立随机 nonce 加密，返回的密文包含 GCM 认证标签。 */
    public Encrypted encrypt(UUID configId, int version, String secret) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(configId, version));
            return new Encrypted(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)),
                    nonce, keyVersion);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Credential encryption failed", failure);
        }
    }

    /** 校验密钥版本、nonce 和 GCM 认证；配置被调换或密文损坏时拒绝返回明文。 */
    public String decrypt(UUID configId, int version, Encrypted encrypted) {
        requireKeyVersion(encrypted.keyVersion());
        SecretKeySpec selected = encrypted.keyVersion() == keyVersion
                ? key : previousKeys.get(encrypted.keyVersion());
        if (encrypted.nonce().length != NONCE_BYTES) {
            throw new IllegalStateException("Credential nonce is invalid");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, selected,
                    new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(aad(configId, version));
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Credential authentication failed", failure);
        }
    }

    /** 在预留可能计费的模型回合前确认对应当前或历史密钥仍可用。 */
    public void requireKeyVersion(int requestedVersion) {
        if ((requestedVersion != keyVersion && !previousKeys.containsKey(requestedVersion))
                || (requestedVersion == keyVersion && key == null)) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CREDENTIAL_KEY_VERSION_MISSING", "历史凭证密钥不可用",
                    "部署者尚未提供该配置版本对应的解密主密钥。", false);
        }
    }

    /** 将配置 ID 和版本编码为认证附加数据，防止密文跨配置或版本搬用。 */
    private byte[] aad(UUID configId, int version) {
        return ("agenvas:llm-provider-config:" + configId + ":" + version)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 加密前要求已配置当前主密钥，并返回稳定的部署配置错误。 */
    private void requireKey() {
        if (key == null) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CREDENTIAL_MASTER_KEY_MISSING", "密钥存储未就绪",
                    "部署者尚未配置凭证加密主密钥。", false);
        }
    }

    /** 仅供配置仓储保存的加密字段，不得序列化到公开响应。
     * @param ciphertext AES-GCM 密文与认证标签
     * @param nonce 本条密文独占的 12 字节随机数
     * @param keyVersion 加密时使用的密钥版本
     */
    public record Encrypted(byte[] ciphertext, byte[] nonce, int keyVersion) {}
}

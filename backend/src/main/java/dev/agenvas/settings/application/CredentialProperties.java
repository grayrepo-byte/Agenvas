package dev.agenvas.settings.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 部署级密钥配置；主密钥与数据库中的 Provider 密文分开管理。
 * @param masterKeyBase64 当前主密钥的 Base64 编码
 * @param keyVersion 当前用于解密和新加密的密钥版本
 * @param previousKeys 轮换期间仍需读取的旧版本密钥映射
 */
@ConfigurationProperties(prefix = "agenvas.credentials")
public record CredentialProperties(String masterKeyBase64, int keyVersion,
        String previousKeys) {}

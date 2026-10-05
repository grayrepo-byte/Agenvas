package dev.agenvas.settings.application;

import java.time.Instant;
import java.util.UUID;

/** 一个不可变的 LLM 配置版本；更新配置时新增版本，仅活动版本指针会变化。
 * @param id 配置版本 UUID
 * @param version 项目级配置递增版本
 * @param endpoint 经管理员配置并由策略校验的 Provider 地址
 * @param modelId 此版本使用的模型标识
 * @param credentialCiphertext API 密钥密文，不得记录日志或传入前端
 * @param credentialNonce 解密密钥所需随机数，与密文一同仅留在服务端
 * @param keyVersion 用于选择服务端加密密钥的版本
 * @param keyMask 前端安全展示的密钥掩码
 * @param toolCallingVerified 此版本是否通过工具调用诊断
 * @param active 此版本是否为当前生效配置
 * @param createdAt 配置版本创建时间
 */
public record LlmProviderConfig(UUID id, int version, String endpoint, String modelId,
        byte[] credentialCiphertext, byte[] credentialNonce, int keyVersion,
        String keyMask, boolean toolCallingVerified, boolean active, Instant createdAt) {}

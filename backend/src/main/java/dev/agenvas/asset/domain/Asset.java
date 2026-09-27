package dev.agenvas.asset.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 私有不可变媒体文件的数据库身份及归档时核验的元数据。
 *
 * @param id 素材 ID
 * @param projectId 素材所属项目
 * @param mediaKind 图片或视频
 * @param objectKey 由服务端生成且相对资产根目录的存储键
 * @param contentType 根据实际解码结果确认的媒体类型
 * @param byteSize 原始文件字节数
 * @param sha256 原始文件 SHA-256 摘要
 * @param width 解码得到的像素宽度
 * @param height 解码得到的像素高度
 * @param durationMs 视频时长毫秒数；图片为空
 * @param thumbnailKey 封面帧存储键；图片不生成预览，没有封面帧时为空
 * @param thumbnailByteSize 封面帧字节数
 * @param thumbnailSha256 封面帧 SHA-256 摘要
 * @param createdAt READY 元数据创建时间
 */
public record Asset(UUID id, UUID projectId, MediaKind mediaKind, String objectKey,
        String contentType, long byteSize, String sha256, Integer width, Integer height,
        Integer durationMs,
        String thumbnailKey, Long thumbnailByteSize, String thumbnailSha256,
        Instant createdAt) {

    /** 本地归档接受并保留的媒体类别。 */
    public enum MediaKind {
        /** 经图像解码验证的图片文件。 */
        IMAGE,
        /** 经探测和解码验证的 MP4 视频。 */
        VIDEO
    }
}

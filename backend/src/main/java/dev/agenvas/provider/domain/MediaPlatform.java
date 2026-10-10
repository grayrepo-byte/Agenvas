package dev.agenvas.provider.domain;

/** `media_provider_connection.platform` 列取值；决定端点校验、凭据形态与适配器归属。 */
public enum MediaPlatform {
    /** No-network image processing compiled into the self-hosted server. */
    LOCAL,
    /** 无外部模型的内置确定性演示平台。 */
    MOCK,
    /** 部署者自托管的 ComfyUI 固定模板端点。 */
    COMFYUI,
    /** OpenAI 图像端点。 */
    OPENAI,
    /** 火山方舟视频端点。 */
    ARK,
    /** Google 图像端点。 */
    GOOGLE,
    /** Fixed Volcano Engine speech synthesis API. */
    VOLCENGINE,
    /** Fixed RunningHub V2 task protocol with administrator-published input mappings. */
    RUNNINGHUB,
    /** AutoDL hosted ComfyUI workflow task API. */
    AUTODL,
    /** MiniMax official H3 V2 video generation API. */
    MINIMAX
}

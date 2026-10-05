package dev.agenvas.provider.domain;

/** 媒体 Provider 提交抽象；具体适配器不得在 UNKNOWN 时自行盲目重提。
 */
public interface GenerationGateway {

    /** 执行一次 Provider 提交；调用方通过持久化 attempt 记录和去重控制副作用。
     * @param request 已通过服务端校验的生成请求
     * @return Provider 接受、拒绝或结果未知的受理状态
     */
    GenerationResult submit(GenerationRequest request);
}

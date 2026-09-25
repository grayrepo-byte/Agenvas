package dev.agenvas.plan.application;

import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.shared.error.ApiProblemException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 只为服务端已安装的固定媒体工作流提供版本，并在创建计划前拒绝无实现能力。 */
@Component
public class PlanWorkflowPolicy {

    /** 当前媒体 Provider 模式配置。 */
    private final PlanProviderProperties provider;
    /** 可选的固定图片模板；Mock 模式或未安装时可能不存在。 */
    private final ObjectProvider<ComfyUiImageWorkflow> comfyImage;
    /** 可选的固定视频模板；Mock 模式或未安装时可能不存在。 */
    private final ObjectProvider<ComfyUiVideoWorkflow> comfyVideo;

    /** 注入模式配置与条件装配的固定工作流。
     * @param provider 图片和视频 Provider 模式
     * @param comfyImage 当前容器内的 ComfyUI 图片模板
     * @param comfyVideo 当前容器内的 ComfyUI 视频模板
     */
    public PlanWorkflowPolicy(PlanProviderProperties provider,
            ObjectProvider<ComfyUiImageWorkflow> comfyImage,
            ObjectProvider<ComfyUiVideoWorkflow> comfyVideo) {
        this.provider = provider;
        this.comfyImage = comfyImage;
        this.comfyVideo = comfyVideo;
    }

    /** 在持久化计划或已审批任务前拒绝当前模板不支持的媒体阶段。 */
    public String version(ExecutionPlan.Stage stage) {
        if ("mock".equalsIgnoreCase(provider.mode())) {
            return stage == ExecutionPlan.Stage.IMAGE ? "mock-image-v1" : "mock-video-v1";
        }
        if ("comfyui".equalsIgnoreCase(provider.mode())
                && stage == ExecutionPlan.Stage.IMAGE) {
            ComfyUiImageWorkflow workflow = comfyImage.getIfAvailable();
            if (workflow != null) return workflow.version();
        }
        if ("comfyui".equalsIgnoreCase(provider.mode())
                && stage == ExecutionPlan.Stage.VIDEO) {
            ComfyUiVideoWorkflow workflow = comfyVideo.getIfAvailable();
            if (workflow != null) return workflow.version();
        }
        throw new ApiProblemException(HttpStatus.CONFLICT,
                "PROVIDER_UNSUPPORTED_CAPABILITY", "媒体能力未安装",
                "当前 Provider 没有经过固定模板校验的该阶段工作流。", false);
    }

    /** 拒绝固定模板无法精确表达的镜头时长。 */
    public void requireVideoDuration(int durationSeconds) {
        if ("comfyui".equalsIgnoreCase(provider.mode())) {
            ComfyUiVideoWorkflow workflow = comfyVideo.getIfAvailable();
            if (workflow == null || !workflow.supportsDurationSeconds(durationSeconds)) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "PROVIDER_UNSUPPORTED_DURATION", "视频时长不受支持",
                        "当前固定图生视频模板只支持 1–5 整数秒的镜头。", false);
            }
        }
    }
}

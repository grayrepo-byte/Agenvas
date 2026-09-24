package dev.agenvas.plan.application;

import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.shared.error.ApiProblemException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Names only media workflows that have a server-owned fixed implementation installed. */
@Component
public class PlanWorkflowPolicy {

    private final PlanProviderProperties provider;
    private final ObjectProvider<ComfyUiImageWorkflow> comfyImage;
    private final ObjectProvider<ComfyUiVideoWorkflow> comfyVideo;

    public PlanWorkflowPolicy(PlanProviderProperties provider,
            ObjectProvider<ComfyUiImageWorkflow> comfyImage,
            ObjectProvider<ComfyUiVideoWorkflow> comfyVideo) {
        this.provider = provider;
        this.comfyImage = comfyImage;
        this.comfyVideo = comfyVideo;
    }

    /** Rejects unsupported media stages before any plan or approved Task is persisted. */
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

    /** Rejects shot lengths the installed fixed template cannot represent exactly. */
    public void requireVideoDuration(int durationMs) {
        if ("comfyui".equalsIgnoreCase(provider.mode())) {
            ComfyUiVideoWorkflow workflow = comfyVideo.getIfAvailable();
            if (workflow == null || !workflow.supportsDuration(durationMs)) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "PROVIDER_UNSUPPORTED_DURATION", "视频时长不受支持",
                        "当前固定图生视频模板只支持 1–5 秒、以 0.25 秒递增的镜头。", false);
            }
        }
    }
}

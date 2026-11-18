package dev.agenvas.provider.domain;

import static dev.agenvas.provider.domain.MediaAdapterRegistry.*;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.task.domain.Task;
import java.util.Map;
import java.util.Set;

/** Built-in declarations remain readable even when an execution adapter is not installed. */
final class BuiltinMediaAdapters {
    private BuiltinMediaAdapters() {}
    static Map<String, Declaration> declarations() { return DECLARATIONS; }
    static Declaration require(String id) {
        Declaration value = DECLARATIONS.get(id);
        if (value == null) throw new IllegalStateException("Media adapter must declare its capabilities: " + id);
        return value;
    }

    private static final Map<String, Declaration> DECLARATIONS = Map.ofEntries(
            Map.entry("WAN_IMAGE", new WanModels().declaration(new WanModels().profiles().getFirst())),
            Map.entry("WAN_VIDEO",
                    new WanModels().declaration(new WanModels().profiles().stream()
                            .filter(p -> "WAN_VIDEO".equals(p.adapterId())).findFirst().orElseThrow())),
            Map.entry("ARK_SEEDREAM_IMAGE",
                    new SeedreamModels().declaration(new SeedreamModels().profiles().getFirst())),
            Map.entry("GOOGLE_VEO", new GoogleVideoModels().declaration(new GoogleVideoModels().profiles().getFirst())),
            Map.entry("GOOGLE_OMNI_VIDEO",
                    new GoogleVideoModels().declaration(new GoogleVideoModels().profiles().stream()
                            .filter(p -> "GOOGLE_OMNI_VIDEO".equals(p.adapterId())).findFirst().orElseThrow())),
            Map.entry(KlingModels.IMAGE_ADAPTER_ID, image(MediaPlatform.KLING, true, 10,
                    Set.of(), Set.of(), Set.of(), false, false)),
            Map.entry(KlingModels.VIDEO_ADAPTER_ID, new Declaration(MediaPlatform.KLING, Task.Kind.VIDEO_GENERATION,
                    0, 30, true, 10, Set.of("TEXT", "GENERAL_REFERENCE"), "TEXT", false,
                    Set.of(), Set.of(), Set.of(), false, false, 0, 1)),
            Map.entry(KlingProtocol.IMAGE_ADAPTER_ID, image(MediaPlatform.KLING, true, 1,
                    Set.of("AUTO", "16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3", "21:9"),
                    Set.of("1K", "2K"), Set.of(), false, false)),
            Map.entry(KlingProtocol.VIDEO_ADAPTER_ID, video(MediaPlatform.KLING, 3, 15, true, 2,
                    Set.of("TEXT", "START_END"), "TEXT", true)),
            Map.entry(MiniMaxH3Protocol.ADAPTER_ID, video(MediaPlatform.MINIMAX, 4, 15, true, 9,
                    Set.of("TEXT", "START_END", "GENERAL_REFERENCE"), "TEXT", true)),
            Map.entry(LOCAL_VIDEO_PROCESSOR, video(MediaPlatform.LOCAL, 0, 30, false, 0,
                    Set.of("GENERAL_REFERENCE"), "GENERAL_REFERENCE", false)),
            Map.entry(LOCAL_VIDEO_AUDIO_EXTRACTOR, audio(MediaPlatform.LOCAL)),
            Map.entry(LOCAL_AUDIO_TRIMMER, audio(MediaPlatform.LOCAL)),
            Map.entry(LOCAL_VIDEO_COMPOSITOR, video(MediaPlatform.LOCAL, 1, 600, false, 0,
                    Set.of("GENERAL_REFERENCE"), "GENERAL_REFERENCE", false)),
            Map.entry(RUNNINGHUB_IMAGE, runningHub(Task.Kind.IMAGE_GENERATION)),
            Map.entry(RUNNINGHUB_VIDEO, runningHub(Task.Kind.VIDEO_GENERATION)),
            Map.entry(RUNNINGHUB_AUDIO, runningHub(Task.Kind.AUDIO_GENERATION)),
            Map.entry(LOCAL_IMAGE_PROCESSOR, image(MediaPlatform.LOCAL, false, 1,
                    ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS,
                    ImageGenerationParameters.QUALITIES, true, false)),
            Map.entry("MOCK_IMAGE", image(MediaPlatform.MOCK, false, 4,
                    ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS,
                    ImageGenerationParameters.QUALITIES, true, false)),
            Map.entry("MOCK_AUDIO", audio(MediaPlatform.MOCK)),
            Map.entry("VOLC_SEED_AUDIO_1", audio(MediaPlatform.VOLCENGINE)),
            Map.entry("MOCK_VIDEO", video(MediaPlatform.MOCK, 1, 30, false, 4,
                    Set.of("TEXT", "START_END", "GENERAL_REFERENCE"), "TEXT", true)),
            Map.entry(COMFY_IMAGE_V1, image(MediaPlatform.COMFYUI, true, 1,
                    Set.of("AUTO", "1:1", "9:16", "16:9"), Set.of("1K"), Set.of(), false,
                    false)),
            Map.entry("COMFY_VIDEO_V1", video(MediaPlatform.COMFYUI, 1, 5, true, 1,
                    Set.of("START_END"), "START_END", false)),
            Map.entry(OPENAI_GPT_IMAGE_2, image(MediaPlatform.OPENAI, false,
                    OPENAI_MAX_REFERENCE_IMAGES, ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS,
                    ImageGenerationParameters.QUALITIES, true, true)),
            Map.entry(GOOGLE_NANO_BANANA_2, image(MediaPlatform.GOOGLE, false,
                    GOOGLE_MAX_REFERENCE_IMAGES, ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS, Set.of(), false, false)),
            Map.entry("ARK_SEEDANCE_2_I2V", video(MediaPlatform.ARK, 4, 15, false, 9,
                    Set.of("TEXT", "START_END", "GENERAL_REFERENCE"), "START_END", true)),
            Map.entry(AutoDlWorkflows.ADAPTER_ID,
                    AutoDlWorkflows.require(AutoDlWorkflows.DEFAULT_WORKFLOW).declaration()));

    private static Declaration runningHub(Task.Kind kind) {
        return new Declaration(MediaPlatform.RUNNINGHUB, kind, 0,
                kind == Task.Kind.VIDEO_GENERATION ? RUNNINGHUB_MAX_VIDEO_SECONDS : 0,
                true, RUNNINGHUB_MAX_INPUTS, kind == Task.Kind.VIDEO_GENERATION
                        ? Set.of("TEXT", "GENERAL_REFERENCE")
                        : Set.of(),
                kind == Task.Kind.VIDEO_GENERATION ? "TEXT" : null,
                false, Set.of(), Set.of(), Set.of(), false, false, RUNNINGHUB_MAX_INPUTS, RUNNINGHUB_MAX_INPUTS);
    }

    private static Declaration image(MediaPlatform platform, boolean originRequired,
            int maxReferenceImages, Set<String> aspectRatios, Set<String> resolutions,
            Set<String> qualities, boolean transparentBackground, boolean imageMask) {
        return new Declaration(platform, Task.Kind.IMAGE_GENERATION, 0, 0, originRequired,
                maxReferenceImages, Set.of(), null, false, aspectRatios, resolutions,
                qualities, transparentBackground, imageMask, 0, 0);
    }

    private static Declaration video(MediaPlatform platform, int minimumSeconds,
            int maximumSeconds, boolean originRequired, int maxReferenceImages,
            Set<String> inputModes, String defaultInputMode, boolean supportsEndFrame) {
        return new Declaration(platform, Task.Kind.VIDEO_GENERATION, minimumSeconds,
                maximumSeconds, originRequired, maxReferenceImages, inputModes, defaultInputMode,
                supportsEndFrame, Set.of(), Set.of(), Set.of(), false, false,
                platform == MediaPlatform.MOCK || platform == MediaPlatform.ARK || platform == MediaPlatform.MINIMAX
                        ? 3
                        : 0,
                platform == MediaPlatform.ARK || platform == MediaPlatform.MINIMAX ? 3 : 0);
    }

    private static Declaration audio(MediaPlatform platform) {
        return new Declaration(platform, Task.Kind.AUDIO_GENERATION, 0, 0, false, 1,
                Set.of(), null, false, Set.of(), Set.of(), Set.of(), false, false, 3, 0);
    }

}

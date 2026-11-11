package dev.agenvas.subtitle.runtime;

import java.net.URI;

/** Reviewed upstream identity, independent of browser input and mutable upstream branch names. */
public final class ManagedAsrCatalog {
    private ManagedAsrCatalog() {}
    public static final String ENGINE_VERSION = "1.9.5";
    public static final String ENGINE_COMMIT = "d1be6fde11ac6e0407606b4e42fe72d34add8037";
    public static final Model MODEL = new Model("whisper-tiny-q5_1", "ggml-tiny-q5_1.bin", 32_152_673L,
            "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
            URI.create("https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin"));
    public record Model(String id, String fileName, long bytes, String sha256, URI source) {}
}

package dev.agenvas.skill;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.skill.application.BuiltinSkillCatalogue;
import dev.agenvas.skill.application.SkillFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class BuiltinSkillCatalogueTest {
    private final BuiltinSkillCatalogue catalogue = new BuiltinSkillCatalogue(new JsonMapper(), new SkillFormat());

    @Test void packagesEightCanvasSkillsWithNativeCardTemplatesAndUsefulReferences() throws IOException {
        assertThat(catalogue.entries()).extracting(entry -> entry.bundle().name()).containsExactlyInAnyOrder(
                "short-drama-novel-analyze", "short-drama-develop", "short-drama-write", "short-drama-assets",
                "short-drama-image-prompts", "short-drama-storyboard", "short-drama-video-prompts", "short-drama-review");
        for (String excluded : new String[] { "short-drama", "short-drama-produce", "short-drama-edit" }) {
            assertThat(new ClassPathResource("builtin-skills/drama-skills/skills/" + excluded + "/SKILL.md").exists()).isFalse();
        }
        assertThat(catalogue.entries().stream().mapToInt(entry -> entry.bundle().resources().size()).sum()).isEqualTo(112);
        for (var entry : catalogue.entries()) {
            var bundle = entry.bundle();
            assertThat(entry.key()).isEqualTo("drama-skills/agenvas-canvas-v1/" + bundle.name());
            assertThat(bundle.skillMd()).isEqualTo(read("skills/" + bundle.name() + "/SKILL.md"))
                    .contains("references/agenvas-cards.md", "assets/card-templates.md");
            assertThat(bundle.assets()).isEmpty();
            assertThat(bundle.inputSlots()).isEmpty();
            for (var resource : bundle.resources()) {
                assertThat(resource.path()).doesNotContain("scripts/", "..", "\\");
                String source = BuiltinSkillCatalogue.CARD_WORKFLOW_PATH.equals(resource.path()) ? resource.path()
                        : "skills/" + bundle.name() + "/" + resource.path();
                assertThat(resource.content()).isEqualTo(read(source));
                assertThat(resource.contentHash()).isEqualTo(Sha256.hex(resource.content()));
            }
        }
        assertThat(catalogue.entries().stream().flatMap(entry -> entry.bundle().resources().stream()))
                .anySatisfy(resource -> assertThat(resource.content().length()).isGreaterThan(20000));
    }

    @Test void allModelVisibleContentUsesCanvasWorkflowsWithoutCommandsOrDanglingResourceLinks() {
        Pattern unsupported = Pattern.compile("(?i)\\b(?:python3?|ffmpeg|bash)\\b|scripts/|\\.py\\b|脚本|```(?:sh|shell)|项目开发/|剧集/|输入/|short-drama\\.json|short-drama-produce|short-drama-edit|\\$short-drama\\b(?!-)");
        Pattern links = Pattern.compile("\\]\\(([^)]+)\\)");
        for (var entry : catalogue.entries()) {
            var bundle = entry.bundle();
            var contents = new java.util.LinkedHashMap<String, String>();
            contents.put("SKILL.md", bundle.skillMd());
            bundle.resources().forEach(resource -> contents.put(resource.path(), resource.content()));
            contents.forEach((path, content) -> {
                assertThat(unsupported.matcher(content).find()).as("%s / %s has no unsupported execution workflow", bundle.name(), path).isFalse();
                var matcher = links.matcher(content);
                while (matcher.find()) {
                    String target = matcher.group(1);
                    if (target.startsWith("#") || target.startsWith("https://") || target.startsWith("http://")) continue;
                    Path parent = Path.of(path).getParent();
                    String resolved = (parent == null ? Path.of(target.split("#")[0]) : parent.resolve(target.split("#")[0])).normalize().toString();
                    assertThat(contents).as("%s / %s resolves %s", bundle.name(), path, target).containsKey(resolved);
                }
            });
            assertThat(contents.get(BuiltinSkillCatalogue.CARD_WORKFLOW_PATH)).contains(
                    "read_artifacts", "contentTruncated", "create_text", "自动放到", "revise_artifact",
                    "expectedVersion", "affectedVersions", "artifactVersions", "propose_media_generation");
        }
        var novel = catalogue.entries().stream().filter(entry -> entry.bundle().name().equals("short-drama-novel-analyze")).findFirst().orElseThrow();
        assertThat(novel.bundle().skillMd()).contains("章节索引卡", "覆盖检查", "缺章", "重复", "来源变化", "未核对");
    }

    @Test void preservesUpstreamAttributionAndPinsTheBundledRevision() throws IOException {
        String manifest = read("manifest.json");
        assertThat(manifest).contains("c2426e03c0e7722bebcc6a488b6658dc38c65ac3", "0.8.1", "https://github.com/zenstory-ai/drama-skills", "agenvas-canvas-v1");
        assertThat(read("LICENSE")).contains("Copyright (c) 2026 drama-skills contributors", "MIT License");
        assertThat(catalogue.entries()).extracting(entry -> entry.bundle().name()).contains("short-drama-write", "short-drama-review");
    }
    private String read(String path) throws IOException {
        try (var stream = new ClassPathResource("builtin-skills/drama-skills/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

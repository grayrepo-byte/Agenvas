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
    private final JsonMapper mapper = new JsonMapper();
    private final BuiltinSkillCatalogue catalogue = new BuiltinSkillCatalogue(mapper, new SkillFormat());

    @Test void packagesReviewedCanvasSkillsWithEveryManifestResourceAndNativeCardTemplates() throws IOException {
        var manifest = mapper.readValue(read("manifest.json"), BuiltinSkillCatalogue.Manifest.class);
        assertThat(dramaEntries()).extracting(entry -> entry.bundle().name()).containsExactlyInAnyOrder(
                "short-drama-novel-analyze", "short-drama-develop", "short-drama-write", "short-drama-assets",
                "short-drama-image-prompts", "short-drama-storyboard", "short-drama-video-prompts", "short-drama-review");
        assertThat(dramaEntries()).extracting(entry -> entry.bundle().name())
                .containsExactlyInAnyOrderElementsOf(manifest.skills().stream().map(BuiltinSkillCatalogue.Definition::name).toList());
        for (String excluded : new String[] { "short-drama", "short-drama-produce", "short-drama-edit" }) {
            assertThat(new ClassPathResource("builtin-skills/drama-skills/skills/" + excluded + "/SKILL.md").exists()).isFalse();
        }
        for (var entry : dramaEntries()) {
            var bundle = entry.bundle();
            var definition = manifest.skills().stream().filter(skill -> skill.name().equals(bundle.name())).findFirst().orElseThrow();
            assertThat(entry.key()).isEqualTo("drama-skills/agenvas-canvas-v1/" + bundle.name());
            assertThat(entry.title()).isEqualTo(definition.title());
            assertThat(bundle.description()).isEqualTo(definition.description());
            assertThat(bundle.skillMd()).isEqualTo(read("skills/" + bundle.name() + "/SKILL.md"))
                    .isNotBlank().hasSizeLessThanOrEqualTo(BuiltinSkillCatalogue.MAX_MAIN_LENGTH)
                    .contains("references/agenvas-cards.md", "assets/card-templates.md");
            assertThat(bundle.assets()).isEmpty();
            assertThat(bundle.inputSlots()).isEmpty();
            // The reviewed manifest defines the attachments; adding a valid reference must not require a new magic total.
            assertThat(bundle.resources()).isNotEmpty().hasSizeLessThanOrEqualTo(BuiltinSkillCatalogue.MAX_RESOURCES);
            assertThat(bundle.resources()).extracting(resource -> resource.path())
                    .containsExactlyElementsOf(definition.resources()).doesNotHaveDuplicates();
            for (var resource : bundle.resources()) {
                assertThat(resource.path()).hasSizeLessThanOrEqualTo(SkillFormat.MAX_PATH_LENGTH)
                        .doesNotContain("scripts/", "..", "\\");
                String source = BuiltinSkillCatalogue.CARD_WORKFLOW_PATH.equals(resource.path()) ? resource.path()
                        : "skills/" + bundle.name() + "/" + resource.path();
                assertThat(resource.content()).isEqualTo(read(source))
                        .isNotBlank().hasSizeLessThanOrEqualTo(BuiltinSkillCatalogue.MAX_RESOURCE_LENGTH);
                assertThat(resource.contentHash()).isEqualTo(Sha256.hex(resource.content()));
            }
        }
    }

    @Test void allModelVisibleContentUsesCanvasWorkflowsWithoutCommandsOrDanglingResourceLinks() {
        Pattern unsupported = Pattern.compile("(?i)\\b(?:python3?|ffmpeg|bash)\\b|scripts/|\\.py\\b|脚本|```(?:sh|shell)|项目开发/|剧集/|输入/|short-drama\\.json|short-drama-produce|short-drama-edit|\\$short-drama\\b(?!-)");
        Pattern links = Pattern.compile("\\]\\(([^)]+)\\)");
        for (var entry : dramaEntries()) {
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
        var novel = dramaEntries().stream().filter(entry -> entry.bundle().name().equals("short-drama-novel-analyze")).findFirst().orElseThrow();
        assertThat(novel.bundle().skillMd()).contains("章节索引卡", "覆盖检查", "缺章", "重复", "来源变化", "未核对");
    }

    @Test void preservesUpstreamAttributionAndPinsTheBundledRevision() throws IOException {
        String manifest = read("manifest.json");
        assertThat(manifest).contains("c2426e03c0e7722bebcc6a488b6658dc38c65ac3", "0.8.1", "https://github.com/zenstory-ai/drama-skills", "agenvas-canvas-v1");
        assertThat(read("LICENSE")).contains("Copyright (c) 2026 drama-skills contributors", "MIT License");
        assertThat(dramaEntries()).extracting(entry -> entry.bundle().name()).contains("short-drama-write", "short-drama-review");
    }
    @Test void packagesAllNineH3MethodsWithResolvableProgressiveReferencesAndAttribution() throws IOException {
        var h3 = catalogue.entries().stream().filter(entry -> entry.key().startsWith("minimax-h3/")).toList();
        assertThat(h3).extracting(entry -> entry.bundle().name()).containsExactlyInAnyOrder(
                "h3-prompt-writing", "minimalist-product-ad-generator", "3d-animation-short-generator",
                "brand-promo-video-generator", "music-video-subtitle-generator", "co-op-game-intro-generator",
                "paper-collage-explainer-generator", "papercraft-stop-motion-explainer", "handdrawn-live-video-generator");
        var manifest = mapper.readTree(readH3("manifest.json"));
        String revision = manifest.path("revision").asText();
        assertThat(revision).isEqualTo("d21241f0a4b3acbb34c97dae47fa417b7065e438");
        assertThat(Sha256.hex(readH3("LICENSE"))).isEqualTo(manifest.path("licenseSha256").asText());
        assertThat(readH3("NOTICE")).contains("MiniMax H3 Community License Agreement", "Modified by Agenvas", revision);
        assertThat(readH3("LICENSE")).contains("Excluded Territories", "Distribution and Redistribution");
        for (var entry : h3) {
            assertThat(entry.key()).isEqualTo("minimax-h3/agenvas-canvas-v1/" + entry.bundle().name());
            assertThat(entry.source().license()).isEqualTo("MiniMax H3 Community License");
            assertThat(entry.source().revision()).isEqualTo(revision);
            var contents = new java.util.LinkedHashMap<String, String>();
            contents.put("SKILL.md", entry.bundle().skillMd());
            for (var resource : entry.bundle().resources()) {
                String source = BuiltinSkillCatalogue.CARD_WORKFLOW_PATH.equals(resource.path()) ? resource.path()
                        : "skills/" + entry.bundle().name() + "/" + resource.path();
                assertThat(resource.content()).isEqualTo(readH3(source)).isNotBlank()
                        .hasSizeLessThanOrEqualTo(BuiltinSkillCatalogue.MAX_RESOURCE_LENGTH);
                assertThat(resource.contentHash()).isEqualTo(Sha256.hex(resource.content()));
                contents.put(resource.path(), resource.content());
            }
            assertThat(entry.bundle().skillMd()).hasSizeLessThanOrEqualTo(SkillFormat.MAX_SKILL_LENGTH);
            assertThat(contents).containsKey(BuiltinSkillCatalogue.CARD_WORKFLOW_PATH);
            contents.forEach((path, content) -> {
                assertThat(content).doesNotContain("hub_", "allowed-tools:", "webfetch", "scripts/", "choice card", "选项卡");
                var links = Pattern.compile("\\]\\(([^)]+)\\)").matcher(content);
                while (links.find()) {
                    String target = links.group(1);
                    if (target.startsWith("#") || target.startsWith("https://") || target.startsWith("http://")) continue;
                    Path parent = Path.of(path).getParent();
                    String resolved = (parent == null ? Path.of(target.split("#")[0]) : parent.resolve(target.split("#")[0])).normalize().toString();
                    assertThat(contents).as("%s / %s resolves %s", entry.key(), path, target).containsKey(resolved);
                }
            });
        }
    }
    @Test void categorizesEveryCurrentBuiltinWithoutChangingItsPublicationIdentity() {
        assertThat(catalogue.keys()).doesNotHaveDuplicates();
        assertThat(catalogue.entries()).allSatisfy(entry -> {
            assertThat(entry.category()).isNotNull();
            assertThat(catalogue.find(entry.key())).isEqualTo(entry);
            assertThat(catalogue.keys(entry.category())).contains(entry.key());
        });
        assertThat(catalogue.keys(BuiltinSkillCatalogue.Category.STORY)).containsExactlyInAnyOrderElementsOf(
                dramaEntries().stream().map(BuiltinSkillCatalogue.Entry::key).toList());
        assertThat(catalogue.keys(BuiltinSkillCatalogue.Category.ADVERTISING)).hasSize(2);
        assertThat(catalogue.find(null)).isNull();
        assertThat(catalogue.find("retired-edition")).isNull();
    }
    private String readH3(String path) throws IOException {
        try (var stream = new ClassPathResource("builtin-skills/minimax-h3/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private java.util.List<BuiltinSkillCatalogue.Entry> dramaEntries() {
        return catalogue.entries().stream().filter(entry -> entry.key().startsWith("drama-skills/")).toList();
    }
    private String read(String path) throws IOException {
        try (var stream = new ClassPathResource("builtin-skills/drama-skills/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

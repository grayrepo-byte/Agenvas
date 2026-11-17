package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReferenceCatalogParserTest {
    private final ReferenceCatalogParser parser = new ReferenceCatalogParser(new ObjectMapper());
    private final Source source = new Source("fixture", "Synthetic metadata", "owner", "repo", "a".repeat(40),
            "public/data/catalog.json", Format.OPEN_POSE_COSTUME,
            true, true, 0, null, "NEVER", null, null, null, SourceType.GITHUB);
    @Test
    void normalizesActualShapeWithoutConfusingProvenanceHashWithImageHash() {
        var result = parser.parse(source, "a".repeat(40), """
                {"items":[{"id":"synthetic_pose","kind":"pose","code":"P1","title":{"zh":"合成站姿","en":"Synthetic standing"},
                "category":{"id":"standing","zh":"站姿","en":"Standing"},"series":{"zh":"原作","en":"Original"},
                "extended":false,"keywords":{"zh":"测试","en":"test"},"source":"custom","collection":"custom",
                "tags":[{"id":"line-art","zh":"线稿","en":"Line art"}],
                "variants":[{"id":"synthetic_front","angle":"front","label":{"zh":"正面","en":"Front"},
                "thumbnail":"custom/pose/thumb.webp","image":"custom/pose/image.webp","original":"custom/pose/original.png",
                "provenance":{"originalSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]},
                {"id":"ignored","kind":"character"}],"stats":{}}
                """.getBytes());
        assertThat(result.entries()).hasSize(1);
        var entry = result.entries().getFirst();
        assertThat(entry.localizedTitle().get("zh")).isEqualTo("合成站姿");
        assertThat(entry.facets()).containsEntry("style", "line-art");
        assertThat(entry.variants()).hasSize(1);
        assertThat(entry.variants().getFirst().sha256()).isNull();
        assertThat(entry.variants().getFirst().path()).isEqualTo("public/custom/pose/original.png");
        assertThat(entry.variants().getFirst().externalUrl())
                .startsWith("https://raw.githubusercontent.com/owner/repo/" + "a".repeat(40));
    }
    @Test
    void originalSyntheticPackHasThreeEntriesAndSevenValidatedImages() throws Exception {
        Source pack = new Source("original", "Original fixture", "fixture", "repo", "a".repeat(40),
                "reference-pack.json", Format.AGENVAS_REFERENCE_PACK,
                true, true, 0, null, "NEVER", null, null, null, SourceType.GITHUB);
        var parsed = parser.parse(pack, "a".repeat(40), java.nio.file.Files
                .readAllBytes(java.nio.file.Path.of("../examples/reference-pack/reference-pack.json")));
        assertThat(parsed.entries()).hasSize(3);
        assertThat(parsed.entries().stream().mapToInt(e -> e.variants().size()).sum()).isEqualTo(7);
        for (var entry : parsed.entries())
            for (var variant : entry.variants()) {
                var bytes = java.nio.file.Files
                        .readAllBytes(java.nio.file.Path.of("../examples/reference-pack").resolve(variant.path()));
                dev.agenvas.referencecatalog.application.ReferenceCatalogValidation.image(bytes, variant.sha256(),
                        variant.byteSize(), variant.contentType());
                assertThat(ReferenceCatalogParser.importable(pack, parsed, variant)).isTrue();
            }
    }
    @Test
    void duplicateIdsAndExecutableLinksAreRejected() {
        String entry = """
                {"id":"same","kind":"costume","code":"C1","title":{"zh":"合成","en":"Synthetic"},"category":{"id":"test","zh":"测试","en":"Test"},
                "series":{"zh":"测试","en":"Test"},"extended":false,"keywords":{"zh":"测试","en":"test"},"source":"https://example.com/",
                "variants":[{"id":"v","angle":"front","label":{"zh":"正面","en":"Front"},"original":"javascript:alert(1)"}]}
                """;
        assertThatThrownBy(() -> parser.parse(source, "a".repeat(40), ("{\"items\":[" + entry + "]}").getBytes()))
                .isInstanceOf(RuntimeException.class);
        for (String url : java.util.List.of("http://127.0.0.1:8188/image.png", "http://[::1]:8090/image.png",
                "https://cdn.invalid:8443/image.png")) {
            String valid = entry.replace("javascript:alert(1)", url);
            assertThat(parser.parse(source, "a".repeat(40), ("{\"items\":[" + valid + "]}").getBytes())
                    .entries().getFirst().variants().getFirst().externalUrl()).isEqualTo(url);
        }
        String safe = entry.replace("javascript:alert(1)", "https://example.com/original.png");
        assertThatThrownBy(
                () -> parser.parse(source, "a".repeat(40), ("{\"items\":[" + safe + "," + safe + "]}").getBytes()))
                .isInstanceOf(RuntimeException.class);
    }
}

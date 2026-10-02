package dev.agenvas.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.skill.application.SkillFormat;
import dev.agenvas.skill.domain.SkillContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillFormatTest {
    private final SkillFormat format = new SkillFormat();
    @Test void acceptsStandardFrontmatterAndTreatsAllowedToolsAsInertMetadata() {
        var parsed = format.publishedFrontmatter("---\nname: warm-illustration\ndescription: Prepare a warm illustration.\nallowed-tools: shell\n---\n# Method\n");
        assertThat(parsed.name()).isEqualTo("warm-illustration");
        assertThat(parsed.description()).isEqualTo("Prepare a warm illustration.");
    }
    @Test void incompleteBodyCanBeSavedButCannotBePublished() {
        assertThat(format.validateDraft(draft("Editing without frontmatter", List.of())).skillMd()).isEqualTo("Editing without frontmatter");
        assertThatThrownBy(() -> format.publishedFrontmatter("Editing without frontmatter")).isInstanceOf(ApiProblemException.class);
    }
    @Test void rejectsDuplicateYamlKeysAndObjectConstruction() {
        for (String yaml : List.of("name: first\nname: second\ndescription: Method", "name: !!java.lang.String evil\ndescription: Method"))
            assertThatThrownBy(() -> format.publishedFrontmatter("---\n" + yaml + "\n---\n"))
                    .isInstanceOf(ApiProblemException.class);
    }
    @Test void rejectsUnsafePathsScriptsAndHtml() {
        for (String path : List.of("references/../secret.md", "references/style.js", "references\\style.md", "/references/style.md", "https://example.invalid/style.md"))
            assertThatThrownBy(() -> format.validateDraft(draft("", List.of(new SkillContent.Resource(path, "text")))))
                    .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> format.validateDraft(draft("", List.of(new SkillContent.Resource("references/style.md", "<script>alert(1)</script>")))))
                .isInstanceOf(ApiProblemException.class);
    }
    @Test void boundsTheCompleteResourceBundleAndRejectsDuplicatePaths() {
        var resource = new SkillContent.Resource("references/style.md", "Use warm colors.");
        assertThatThrownBy(() -> format.validateDraft(draft("", List.of(resource, resource)))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> format.validateDraft(draft("", List.of(new SkillContent.Resource("references/style.md", "a".repeat(SkillFormat.MAX_RESOURCE_LENGTH + 1))))))
                .isInstanceOf(ApiProblemException.class);
    }
    @Test void inputAndAssetAliasesShareOneUnambiguousNamespace() {
        var content = new SkillContent.DraftContent(1, "", List.of(Artifact.Kind.IMAGE),
                List.of(new SkillContent.InputSlot("subject-image", Artifact.Kind.IMAGE, true)), List.of(),
                List.of(new SkillContent.DraftAsset("subject-image", java.util.UUID.randomUUID(), 0L, null, null, null,
                        SkillContent.Usage.PROVIDER_REFERENCE, true, "Subject")));
        assertThatThrownBy(() -> format.validateDraft(content)).isInstanceOf(ApiProblemException.class);
    }
    @Test void rejectsControlCharactersAndOverlongBody() {
        assertThatThrownBy(() -> format.validateDraft(draft("bad\0content", List.of()))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> format.validateDraft(draft("a".repeat(SkillFormat.MAX_SKILL_LENGTH + 1), List.of()))).isInstanceOf(ApiProblemException.class);
    }
    @Test void rejectsUnpairedSurrogatesBeforeTheyReachPostgresqlJsonb() {
        assertThatThrownBy(() -> format.validateDraft(draft("bad\uD800text", List.of()))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> format.validateDraft(draft("bad\uDC00text", List.of()))).isInstanceOf(ApiProblemException.class);
    }
    private SkillContent.DraftContent draft(String body, List<SkillContent.Resource> resources) {
        return new SkillContent.DraftContent(1, body, List.of(Artifact.Kind.IMAGE), List.of(), resources, List.of());
    }
}

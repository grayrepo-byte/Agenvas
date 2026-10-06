package dev.agenvas.skill.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.skill.domain.SkillContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Local creative instructions adapted to the canvas tools; edition keys preserve old immutable publications. */
@Component
public final class BuiltinSkillCatalogue {
    public static final int MAX_MAIN_LENGTH = 16000;
    public static final int MAX_RESOURCE_LENGTH = 32000;
    public static final int MAX_RESOURCES = 100;
    private static final String ROOT = "builtin-skills/drama-skills/";
    public static final String CARD_WORKFLOW_PATH = "references/agenvas-cards.md";
    private final List<Entry> entries;
    public record Entry(String key, String title, String bundleHash, SkillContent.Bundle bundle) {}
    public record Manifest(String repository, String revision, String version, String license, String edition, List<Definition> skills) {}
    public record Definition(String name, String title, String description, List<String> resources) {}

    public BuiltinSkillCatalogue(ObjectMapper mapper, SkillFormat format) {
        var manifest = mapper.readValue(read("manifest.json"), Manifest.class);
        format.alias(manifest.edition());
        var names = new HashSet<String>();
        entries = manifest.skills().stream().map(definition -> {
            format.alias(definition.name());
            if (!names.add(definition.name()) || definition.resources().size() > MAX_RESOURCES)
                throw new IllegalStateException("Invalid packaged Skill catalogue");
            format.text(definition.title(), SkillFormat.MAX_TITLE_LENGTH, true);
            format.text(definition.description(), SkillFormat.MAX_DESCRIPTION_LENGTH, true);
            String directory = "skills/" + definition.name() + "/";
            String body = read(directory + "SKILL.md");
            format.text(body, MAX_MAIN_LENGTH, true);
            var frontmatter = format.publishedFrontmatter(body);
            if (!definition.name().equals(frontmatter.name()) || !definition.description().equals(frontmatter.description()))
                throw new IllegalStateException("Packaged Skill metadata does not match its main instructions");
            var paths = new HashSet<String>();
            var resources = definition.resources().stream().map(path -> {
                // Manifest paths address classpath text only; user input never reaches this resolver.
                if (path.length() > SkillFormat.MAX_PATH_LENGTH || path.contains("..") || !paths.add(path)
                        || !path.matches("(?:references|assets)/[\\p{L}\\p{N}._/-]+\\.md"))
                    throw new IllegalStateException("Invalid packaged Skill resource path");
                String text = read(CARD_WORKFLOW_PATH.equals(path) ? path : directory + path);
                format.text(text, MAX_RESOURCE_LENGTH, false);
                return new SkillContent.PublishedResource(path, text, Sha256.hex(text));
            }).toList();
            // Creative text may lead to user-requested media through the existing approval/capability checks.
            var bundle = new SkillContent.Bundle(SkillContent.SCHEMA_VERSION, definition.name(), definition.description(), body,
                    List.of(Artifact.Kind.TEXT, Artifact.Kind.IMAGE, Artifact.Kind.VIDEO, Artifact.Kind.AUDIO),
                    List.of(), resources, List.of());
            return new Entry("drama-skills/" + manifest.edition() + "/" + definition.name(), definition.title(), Sha256.hex(mapper.writeValueAsString(bundle)), bundle);
        }).toList();
    }
    public List<Entry> entries() { return entries; }
    public List<String> keys() { return entries.stream().map(Entry::key).toList(); }
    private String read(String path) {
        try (var stream = new ClassPathResource(ROOT + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Packaged Skill content is missing: " + path, failure);
        }
    }
}

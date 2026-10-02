package dev.agenvas.skill.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.skill.domain.SkillContent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** A logical text bundle, with no filesystem resolution, scripts, or user-authored tool privileges. */
@Component
public final class SkillFormat {
    public static final int MAX_TITLE_LENGTH = 160;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final int MAX_SKILL_LENGTH = 8000;
    public static final int MAX_RESOURCES = 8;
    public static final int MAX_RESOURCE_LENGTH = 8000;
    public static final int MAX_ASSETS = 14;
    public static final int MAX_INPUTS = 14;
    public static final int MAX_PATH_LENGTH = 160;
    public static final int MAX_PURPOSE_LENGTH = 1024;
    public static final int MAX_KEY_LENGTH = 200;
    private static final int MAX_YAML_DEPTH = 10;
    private static final int MIN_FRONTMATTER_LINES = 4;
    private static final Pattern ALIAS = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern RESOURCE_PATH = Pattern.compile("references/(?:[A-Za-z0-9][A-Za-z0-9._-]*/)*[A-Za-z0-9][A-Za-z0-9._-]*\\.(?:md|txt)");
    private static final Pattern HTML_TAG = Pattern.compile("<\\s*/?\\s*[A-Za-z][A-Za-z0-9-]*(?:\\s[^>]*|\\s*)>");
    public record Frontmatter(String name, String description) {}

    public SkillContent.DraftContent validateDraft(SkillContent.DraftContent content) {
        if (content == null || content.schemaVersion() != SkillContent.SCHEMA_VERSION) invalid();
        text(content.skillMd(), MAX_SKILL_LENGTH, false);
        List<Artifact.Kind> outputs = requiredList(content.outputKinds(), Artifact.Kind.values().length);
        if (outputs.isEmpty() || new HashSet<>(outputs).size() != outputs.size() || outputs.stream().anyMatch(java.util.Objects::isNull)) invalid();
        List<SkillContent.InputSlot> inputs = requiredList(content.inputSlots(), MAX_INPUTS);
        Set<String> aliases = new HashSet<>();
        for (var slot : inputs) {
            if (slot == null || slot.kind() == null) invalid();
            alias(slot.alias());
            if (!aliases.add(slot.alias())) invalid();
        }
        List<SkillContent.Resource> resources = requiredList(content.resources(), MAX_RESOURCES);
        Set<String> paths = new HashSet<>();
        for (var resource : resources) {
            if (resource == null || resource.path() == null || resource.path().length() > MAX_PATH_LENGTH
                    || !RESOURCE_PATH.matcher(resource.path()).matches()
                    || resource.path().contains("..") || !paths.add(resource.path())) invalid();
            text(resource.content(), MAX_RESOURCE_LENGTH, false);
            if (HTML_TAG.matcher(resource.content()).find()) invalid();
        }
        List<SkillContent.DraftAsset> assets = requiredList(content.assets(), MAX_ASSETS);
        for (var asset : assets) {
            if (asset == null || asset.usage() == null) invalid();
            alias(asset.alias());
            if (!aliases.add(asset.alias())) invalid();
            text(asset.purpose(), MAX_PURPOSE_LENGTH, false);
            boolean library = asset.libraryEntryId() != null && asset.expectedLibraryVersion() != null
                    && asset.expectedLibraryVersion() >= 0 && asset.sourceVersionId() == null && asset.sourceAlias() == null;
            boolean version = asset.libraryEntryId() == null && asset.expectedLibraryVersion() == null
                    && asset.sourceVersionId() != null && asset.sourceAlias() != null;
            if (!library && !version) invalid();
            if (version) alias(asset.sourceAlias());
            if (asset.contentHash() != null && !asset.contentHash().matches("[0-9a-f]{64}")) invalid();
        }
        return new SkillContent.DraftContent(SkillContent.SCHEMA_VERSION, content.skillMd(), List.copyOf(outputs),
                List.copyOf(inputs), List.copyOf(resources), List.copyOf(assets));
    }

    /** Draft editing may retain incomplete frontmatter; publishing validates the authoritative file. */
    public Frontmatter publishedFrontmatter(String source) {
        text(source, MAX_SKILL_LENGTH, true);
        if (HTML_TAG.matcher(source).find()) invalid();
        String[] lines = source.split("\\r?\\n", -1);
        if (lines.length < MIN_FRONTMATTER_LINES || !lines[0].equals("---")) invalid();
        int end = -1;
        for (int index = 1; index < lines.length; index++) if (lines[index].equals("---")) { end = index; break; }
        if (end < 0) invalid();
        String yamlSource = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, end));
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_YAML_DEPTH);
        options.setCodePointLimit(MAX_SKILL_LENGTH);
        Object parsed;
        try { parsed = new Yaml(new SafeConstructor(options)).load(yamlSource); }
        catch (RuntimeException badYaml) { throw problem("SKILL_INVALID_FRONTMATTER", ApiMessage.of("api.skill.invalid-frontmatter")); }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("name") instanceof String)
                || !(map.get("description") instanceof String))
            throw problem("SKILL_INVALID_FRONTMATTER", ApiMessage.of("api.skill.invalid-frontmatter"));
        String name = (String) ((Map<?, ?>) parsed).get("name");
        String description = (String) ((Map<?, ?>) parsed).get("description");
        alias(name);
        text(description, MAX_DESCRIPTION_LENGTH, true);
        // All remaining frontmatter is inert metadata, including allowed-tools.
        return new Frontmatter(name, description);
    }
    public void alias(String value) {
        if (value == null || value.length() > 64 || !ALIAS.matcher(value).matches()) invalid();
    }
    public void text(String value, int max, boolean nonblank) {
        if (value == null || value.length() > max || value.indexOf('\0') >= 0 || (nonblank && value.isBlank())) invalid();
        // JSON strings can contain isolated UTF-16 units; PostgreSQL JSONB correctly rejects them.
        // Reject at the public boundary so users get a stable validation error instead of a DB failure.
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) invalid();
                index++;
            } else if (Character.isLowSurrogate(unit)) invalid();
        }
    }
    private <T> List<T> requiredList(List<T> value, int max) {
        if (value == null || value.size() > max) invalid();
        return value;
    }
    private void invalid() { throw problem("SKILL_INVALID_CONTENT", ApiMessage.of("api.skill.invalid-content")); }
    private ApiProblemException problem(String code, ApiMessage detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                ApiMessage.of("api.skill.operation-failed"), detail, false);
    }
}

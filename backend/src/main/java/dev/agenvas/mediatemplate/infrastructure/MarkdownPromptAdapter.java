package dev.agenvas.mediatemplate.infrastructure;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptAdapter;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import dev.agenvas.shared.crypto.Sha256;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parses the three documented README layouts without treating headings inside code as entries. */
@Component
public class MarkdownPromptAdapter implements ThirdPartyPromptAdapter {
    private static final Pattern HEADING = Pattern.compile("^### (.+)$");
    private static final Pattern IMAGE = Pattern.compile("<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']|!\\[[^]]*]\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\((https://[^)]+)\\)");
    private static final Pattern GALLERY_ID = Pattern.compile("https://youmind\\.com/[^)\\s]+[?&]id=(\\d+)");
    private static final Pattern INLINE = Pattern.compile("(?ms)^[^\\n]*\\*\\*Prompt Text:\\*\\*\\s*`(.*?)`[ \\t]*(?=\\n|$)");
    private static final Pattern SOURCE = Pattern.compile("(?im)^.*(?:\\*\\*Source:|\\*Source:|\\*\\*Author:).*$");
    private static final Pattern DESCRIPTION = Pattern.compile("(?s)#### [^\\n]*Description\\s*\\n(.*?)(?=\\n#### |$)");
    private static final Pattern PUBLISHED = Pattern.compile("(?m)^- \\*\\*Published:\\*\\* (.+)$");
    private static final Pattern FENCE = Pattern.compile("(?ms)^```[^\\n]*\\n(.*?)^```[ \\t]*$");
    private static final Pattern PROMPT_SECTION = Pattern.compile("(?im)^(?:[^\\n]*\\*\\*Prompt(?: Text)?:\\*\\*|####[^\\n]*Prompt)");
    @Override public Format format() { return Format.GITHUB_MARKDOWN; }

    @Override public List<ThirdPartyPrompt> parse(ThirdPartyPromptSource source, String body) {
        LinkedHashMap<String, ThirdPartyPrompt> results = new LinkedHashMap<>();
        String category = ""; String title = null; StringBuilder section = new StringBuilder(); boolean fenced = false;
        for (String line : (body + "\n### __end__").split("\n", -1)) {
            if (line.startsWith("```")) fenced = !fenced;
            var heading = HEADING.matcher(line);
            if (!fenced && (heading.matches() || line.startsWith("## "))) {
                if (title != null) add(source, title, category, section.toString(), results);
                title = heading.matches() ? heading.group(1).replaceFirst("^No\\. \\d+:\\s*", "").trim() : null;
                section.setLength(0);
                if (line.startsWith("## ")) category = line.substring(3).trim();
            } else if (title != null) section.append(line).append('\n');
        }
        return List.copyOf(results.values());
    }
    private void add(ThirdPartyPromptSource source, String title, String category, String section, LinkedHashMap<String, ThirdPartyPrompt> results) {
        if (!PROMPT_SECTION.matcher(section).find()) return;
        var blocks = FENCE.matcher(section); List<String> prompts = new ArrayList<>();
        while (blocks.find()) prompts.add(blocks.group(1).strip());
        var inline = INLINE.matcher(section);
        if (prompts.isEmpty() && inline.find()) prompts.add(inline.group(1).strip());
        if (prompts.isEmpty()) return;
        String prompt = String.join("\n\n", prompts);
        String metadata = FENCE.matcher(section).replaceAll("");
        String author = ""; String original = "";
        var sourceLine = SOURCE.matcher(metadata);
        while (sourceLine.find()) {
            var links = LINK.matcher(sourceLine.group());
            if (links.find()) {
                if (sourceLine.group().contains("Author:") || author.isEmpty()) author = links.group(1);
                if (sourceLine.group().contains("Source:") || original.isEmpty()) original = links.group(2);
            }
        }
        String sourceUrl = original.isBlank() ? repositoryUrl(source.url()) + "#" + title.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-') : original;
        var gallery = GALLERY_ID.matcher(metadata);
        // A single source post can publish multiple prompts; include the entry heading to avoid merging them.
        String stableId = gallery.find() ? gallery.group(1) : Sha256.hex(original + "\n" + title).substring(0, 24);
        List<String> covers = imageUrls(source, metadata);
        // Only explicitly labelled input images are references; generated examples are covers.
        List<String> references = new ArrayList<>();
        StringBuilder inputs = new StringBuilder(); boolean inputSection = false;
        for (String line : metadata.split("\n")) {
            if (line.startsWith("#### ") || line.matches(".*\\*\\*(?:Reference|Input|Original|Generated|Example) Image.*"))
                inputSection = line.matches("(?i).*(Reference|Input|Original) Images?.*");
            if (inputSection) inputs.append(line).append('\n');
        }
        references.addAll(imageUrls(source, inputs.toString()));
        var description = DESCRIPTION.matcher(metadata);
        var published = PUBLISHED.matcher(metadata);
        String created = published.find() ? published.group(1).trim() : "";
        try { if (!created.isBlank()) created = java.time.LocalDate.parse(created,
                java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.ENGLISH)).toString(); }
        catch (java.time.format.DateTimeParseException ignored) { /* Preserve an upstream date when its format is unknown. */ }
        String id = source.id() + ":" + stableId;
        results.putIfAbsent(id, new Image(id, source.id(), title, prompt,
                description.find() ? description.group(1).strip() : "", covers.stream().filter(url -> !references.contains(url)).findFirst().orElse(""),
                List.copyOf(references), category.isBlank() ? List.of() : List.of(category), author, sourceUrl, created,
                references.isEmpty() ? ImageMode.generate : ImageMode.edit, source.model()));
    }
    private List<String> imageUrls(ThirdPartyPromptSource source, String text) {
        List<String> urls = new ArrayList<>(); var matcher = IMAGE.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String url = URI.create(source.url()).resolve(raw.replace("&amp;", "&")).toString();
            if (!url.contains("img.shields.io") && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }
    static String repositoryUrl(String rawUrl) {
        URI uri = URI.create(rawUrl);
        if (!"raw.githubusercontent.com".equals(uri.getHost())) return rawUrl;
        String[] path = uri.getPath().split("/");
        return "https://github.com/" + path[1] + "/" + path[2];
    }
}

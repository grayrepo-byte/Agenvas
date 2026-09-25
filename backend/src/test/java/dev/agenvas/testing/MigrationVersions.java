package dev.agenvas.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads Flyway migration files off the classpath so tests can assert the expected schema version.
 * A version pinned in each test breaks on every new migration; deriving it keeps them honest.
 */
public final class MigrationVersions {

    /** {@code V44__media_card_display.sql} captures {@code 44}. */
    private static final Pattern FILENAME = Pattern.compile("V(\\d+(?:\\.\\d+)*)__.*\\.sql");

    private MigrationVersions() {}

    /** The highest migration version on the classpath; fails loudly when none is visible. */
    public static String latest() {
        List<String> versions = sorted();
        if (versions.isEmpty()) {
            throw new IllegalStateException("No Flyway migration found under db/migration");
        }
        return versions.get(versions.size() - 1);
    }

    /** Every classpath migration version, ordered by numeric value rather than lexically. */
    public static List<String> sorted() {
        List<String> versions = new ArrayList<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*__*.sql")) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                Matcher matcher = FILENAME.matcher(filename);
                if (matcher.matches()) {
                    versions.add(matcher.group(1));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not list migrations under db/migration", failure);
        }
        versions.sort(Comparator.comparing(BigDecimal::new));
        return versions;
    }
}

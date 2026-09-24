package dev.agenvas.shared.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

/** A manually supplied historical example password is unsafe even when Compose is bypassed. */
class DatabasePasswordGuardTest {

    @Test
    void rejectsPublishedDatabasePasswordsWithoutEchoingThem() {
        for (String example : new String[] {"local-development-only",
                "replace-with-a-random-local-password"}) {
            DataSourceProperties source = new DataSourceProperties();
            source.setPassword(example);
            assertThatThrownBy(() -> new DatabasePasswordGuard(source))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(example);
        }
        DataSourceProperties unique = new DataSourceProperties();
        unique.setPassword("unique-database-password-for-test");
        assertThatCode(() -> new DatabasePasswordGuard(unique)).doesNotThrowAnyException();
    }
}

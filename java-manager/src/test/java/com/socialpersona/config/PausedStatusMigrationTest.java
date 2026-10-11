package com.socialpersona.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PausedStatusMigrationTest {
    @TempDir Path directory;

    @Test
    void existingDatabaseGainsPausedStatusWithoutLosingRowsIndexesOrForeignKeys() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("existing.db"));
        String schema;
        try (var input = new ClassPathResource("schema.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        schema = schema.replace("'sleeping', 'paused', 'archived'", "'sleeping', 'archived'");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new ByteArrayResource(schema.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("INSERT INTO personas (id, name, big_five_json, owner_qq, ai_qq) VALUES ('p1', 'Original', '{}', '12345', '67890')");
            statement.execute("INSERT INTO character_life_archives (persona_id, archive_json) VALUES ('p1', '{\"life\":true}')");
            statement.execute("CREATE INDEX idx_personas_custom_demo ON personas(name)");
        }

        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test", Map.of("spring.datasource.schema-enabled", "true")));
        context.registerBean(javax.sql.DataSource.class, () -> source);
        context.register(DataSourceConfig.class);
        try {
            context.refresh();
            ApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);
            for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
                runner.run(arguments);
            }
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE personas SET status='paused' WHERE id='p1'");
                try (var rows = statement.executeQuery("SELECT name,owner_qq,ai_qq,status FROM personas WHERE id='p1'")) {
                    assertTrue(rows.next());
                    assertEquals("Original", rows.getString("name"));
                    assertEquals("12345", rows.getString("owner_qq"));
                    assertEquals("67890", rows.getString("ai_qq"));
                    assertEquals("paused", rows.getString("status"));
                }
                try (var rows = statement.executeQuery("SELECT archive_json FROM character_life_archives WHERE persona_id='p1'")) {
                    assertTrue(rows.next());
                    assertEquals("{\"life\":true}", rows.getString(1));
                }
                try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_personas_custom_demo'")) {
                    assertTrue(rows.next());
                }
                try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
            }
        } finally {
            context.close();
        }
    }
}

package com.socialpersona.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite Schema 自动初始化
 *
 * ★ SQLite JDBC 不支持 ; 分隔多语句。
 *   schema.sql 中的 DDL 需手动执行（首次部署时）。
 *   启动时跳过——避免 data/ 目录不存在导致启动失败。
 *
 * ★ 手动建表：
 *   sqlite3 data/social_persona.db < src/main/resources/schema.sql
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${spring.datasource.schema-enabled:false}")
    private boolean schemaEnabled;

    @Bean
    public DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);

        if (schemaEnabled) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema.sql"));
            populator.setContinueOnError(true);
            initializer.setDatabasePopulator(populator);
        }

        initializer.setEnabled(schemaEnabled);
        log.info("Schema 自动建表: {}", schemaEnabled ? "启用" : "禁用（手动执行 schema.sql）");
        return initializer;
    }

    /** Rebuilds the legacy personas table whose status CHECK predates paused. */
    @Bean
    public ApplicationRunner migratePersonaStatusConstraint(DataSource dataSource) {
        return args -> migratePersonaStatusConstraintNow(dataSource);
    }

    static void migratePersonaStatusConstraintNow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String tableSql = null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='personas'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) tableSql = rs.getString(1);
            }
            if (tableSql == null) return;
            Matcher statusCheck = Pattern.compile("(?is)CHECK\\s*\\(\\s*(?:\"status\"|status)\\s+IN\\s*\\(([^)]*)\\)\\s*\\)").matcher(tableSql);
            if (!statusCheck.find() || statusCheck.group(1).contains("'paused'")) return;
            String updatedSql = statusCheck.replaceFirst(Matcher.quoteReplacement(
                    "CHECK (status IN (" + statusCheck.group(1) + ", 'paused'))"));
            Matcher header = Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\"personas\"|`personas`|\\[personas\\]|personas)\\s*\\(").matcher(updatedSql);
            if (!header.find()) throw new SQLException("Unsupported personas schema for paused migration");
            updatedSql = header.replaceFirst("CREATE TABLE personas_status_migration (");

            List<String> columns = new ArrayList<>();
            List<String> schemaObjects = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_xinfo(personas)")) {
                while (rs.next()) {
                    if (rs.getInt("hidden") == 0) columns.add("\"" + rs.getString("name").replace("\"", "\"\"") + "\"");
                }
            }
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE tbl_name='personas' AND type IN ('index','trigger') AND sql IS NOT NULL")) {
                while (rs.next()) schemaObjects.add(rs.getString(1));
            }
            boolean oldForeignKeys = false;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA foreign_keys")) {
                if (rs.next()) oldForeignKeys = rs.getInt(1) != 0;
            }
            boolean oldAutoCommit = connection.getAutoCommit();
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(updatedSql);
                    String fields = String.join(",", columns);
                    statement.execute("INSERT INTO personas_status_migration (" + fields + ") SELECT " + fields + " FROM personas");
                    statement.execute("DROP TABLE personas");
                    statement.execute("ALTER TABLE personas_status_migration RENAME TO personas");
                    for (String objectSql : schemaObjects) statement.execute(objectSql);
                    try (ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                        if (violations.next()) throw new SQLException("Foreign key check failed during paused migration");
                    }
                }
                connection.commit();
                log.info("Persona status constraint migrated to support paused");
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys=" + (oldForeignKeys ? "ON" : "OFF"));
                }
            }
        }
    }
}

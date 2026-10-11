package com.socialpersona.event.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.socialpersona.event.entity.DailyEvent;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EventMapperLifecycleTest {
    @TempDir Path databaseDir;

    @Test
    void consumedEventsStayInDayHistoryButLeaveDueQueue() throws Exception {
        var dataSource = new UnpooledDataSource("org.sqlite.JDBC", "jdbc:sqlite:" + databaseDir.resolve("events.db"), null);
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE daily_events (id TEXT PRIMARY KEY, persona_id TEXT, event_date TEXT, event_time TEXT, "
                    + "event_type TEXT, description TEXT, is_active INTEGER, created_at TEXT, updated_at TEXT)");
            sql.execute("INSERT INTO daily_events VALUES ('due', 'p1', '2026-10-10', '08:00:00', 'routine', 'Breakfast', 1, NULL, NULL)");
            sql.execute("INSERT INTO daily_events VALUES ('future', 'p1', '2026-10-10', '23:00:00', 'sleep', 'Sleep', 1, NULL, NULL)");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(EventMapper.class);
        try (var xml = getClass().getResourceAsStream("/mapper/EventMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/EventMapper.xml", configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(true)) {
            EventMapper mapper = session.getMapper(EventMapper.class);
            assertEquals(1, mapper.findDue("p1", "2026-10-10", "09:00:00").size());
            DailyEvent event = mapper.selectById("due");
            event.setIsActive(0);
            mapper.updateById(event);
            assertTrue(mapper.findDue("p1", "2026-10-10", "09:00:00").isEmpty());
            assertEquals(2, mapper.findAllToday("p1", "2026-10-10").size());
            assertEquals(2, mapper.countAllForDay("p1", "2026-10-10"), "Day existence must survive consuming events");
            assertEquals("future", mapper.findNextActive("p1", "2026-10-10", "09:00:00").getId());
        }
    }
}

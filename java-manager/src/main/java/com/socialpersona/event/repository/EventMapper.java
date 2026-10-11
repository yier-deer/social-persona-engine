package com.socialpersona.event.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.socialpersona.event.entity.DailyEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 事件 Mapper —— 含 3 条自定义 SQL（XML 实现）
 *
 * ★ 为什么不用 BaseMapper 自带方法：
 *   findDue 和 findNextActive 的 WHERE 条件涉及多列组合排序，
 *   BaseMapper 的 LambdaQueryWrapper 需要写多行条件链。
 *   XML 写 SQL 更直观，且查询计划由 SQLite 直接优化。
 */
@Mapper
public interface EventMapper extends BaseMapper<DailyEvent> {

    /**
     * 查找今天到期且未失效的事件
     *
     * ★ 调用者：EventTriggerScheduler.scanEvents() 每分钟 1 次
     * ★ 使用索引：idx_daily_events_scan(persona_id, event_date, is_active, event_time)
     *
     * @param personaId Persona UUID
     * @param today     今天日期（yyyy-MM-dd）
     * @param nowTime   当前时间（HH:mm:ss）
     * @return 到期事件列表（按 event_time 升序）
     */
    List<DailyEvent> findDue(@Param("personaId") String personaId,
                             @Param("today") String today,
                             @Param("nowTime") String nowTime);

    /**
     * 查找指定时间之后的下一个活跃事件
     *
     * ★ 用途：传给 LLM 做间隔预判（知道"下个事件是什么、还有多久"）
     *
     * @param personaId   Persona UUID
     * @param today       今天日期
     * @param currentTime 当前事件的时间（HH:mm:ss）
     * @return 下一个事件（仅 1 条），无则 null
     */
    DailyEvent findNextActive(@Param("personaId") String personaId,
                              @Param("today") String today,
                              @Param("currentTime") String currentTime);

    /**
     * 作废指定时间之后的所有活跃事件
     *
     * ★ 用途：AI 修改事件线时批量作废（如 sleep 延后先作废旧 sleep）
     *
     * @param personaId Persona UUID
     * @param today     今天日期
     * @param afterTime 在此之后的事件全部作废（HH:mm:ss）
     * @return 影响行数
     */
    int invalidateAfter(@Param("personaId") String personaId,
                        @Param("today") String today,
                        @Param("afterTime") String afterTime);

    @Select("SELECT * FROM daily_events WHERE persona_id = #{personaId} " +
            "AND event_date = #{today} AND is_active = 1 " +
            "AND event_time > #{nowTime} ORDER BY event_time ASC LIMIT 1")
    DailyEvent findNextActiveAfter(@Param("personaId") String personaId,
                                   @Param("today") String today,
                                   @Param("nowTime") String nowTime);

    @Select("SELECT COUNT(*) FROM daily_events WHERE persona_id = #{personaId} " +
            "AND event_date = #{today} AND is_active = 1 " +
            "AND event_time BETWEEN #{from} AND #{to}")
    int countBetween(@Param("personaId") String personaId,
                     @Param("today") String today,
                     @Param("from") String from,
                     @Param("to") String to);

    @Select("SELECT COUNT(*) FROM daily_events WHERE persona_id = #{personaId} AND event_date = #{today}")
    int countAllForDay(@Param("personaId") String personaId, @Param("today") String today);

    /** 查今天仍在事件线上的活跃事件（历史存在性由 countAllForDay 提供）。 */
    List<DailyEvent> findAllToday(@Param("personaId") String personaId,
                                  @Param("today") String today);
}

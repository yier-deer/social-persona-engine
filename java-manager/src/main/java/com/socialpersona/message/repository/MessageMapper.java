package com.socialpersona.message.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.socialpersona.message.entity.ScheduledMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息 Mapper —— 含 3 条三级扫描自定义 SQL
 *
 * ★ 三级扫描查询都需要高频执行（分钟级/秒级），使用复合索引：
 *   idx_scheduled_msgs_scan(persona_id, is_sent, scheduled_time)
 */
@Mapper
public interface MessageMapper extends BaseMapper<ScheduledMessage> {

    /**
     * 统计下一小时内待发的消息数
     *
     * ★ 调用者：MessageScanScheduler.hourlyScan()（IDLE 级别）
     *
     * @param personaId    Persona UUID
     * @param nowTime      当前时间（HH:mm:ss）
     * @param oneHourLater 一小时后（HH:mm:ss）
     * @return 消息数量
     */
    int countInNextHour(@Param("personaId") String personaId,
                        @Param("nowTime") String nowTime,
                        @Param("oneHourLater") String oneHourLater);

    /**
     * 统计当前分钟内待发的消息数
     *
     * ★ 调用者：MessageScanScheduler.minuteScan()
     *
     * @param personaId   Persona UUID
     * @param minuteStart 当前分钟起始（HH:mm:ss）
     * @param minuteEnd   当前分钟结束（HH:mm:ss）
     * @return 消息数量
     */
    int countInCurrentMinute(@Param("personaId") String personaId,
                             @Param("minuteStart") String minuteStart,
                             @Param("minuteEnd") String minuteEnd);

    /**
     * 查找当前秒到期的消息
     *
     * ★ 调用者：MessageScanScheduler.secondScan()（SECOND_WATCH 级别）
     * ★ 返回：到期消息列表（按 scheduled_time 升序）
     *
     * @param personaId     Persona UUID
     * @param nowTime       当前时间
     * @param oneSecondAgo  一秒前的时间（兜底窗口）
     * @return 到期消息列表
     */
    List<ScheduledMessage> findDueThisSecond(@Param("personaId") String personaId,
                                              @Param("nowTime") String nowTime,
                                              @Param("oneSecondAgo") String oneSecondAgo);

    List<ScheduledMessage> findDue(@Param("personaId") String personaId,
                                   @Param("nowInstant") String nowInstant,
                                   @Param("localTime") String localTime,
                                   @Param("limit") int limit);
}

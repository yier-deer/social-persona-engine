-- ============================================================
-- AI网友模拟器（Social Persona Engine）数据库 Schema
-- 数据库：SQLite（轻量嵌入式，零配置）
-- 设计哲学：惰性衰减 + JSON柔性字段 + 索引覆盖高频查询
-- ============================================================

-- ============================================================
-- 表 1：人格表 —— 每个 AI 网友占一行
-- ============================================================
CREATE TABLE IF NOT EXISTS personas (
    id                          TEXT PRIMARY KEY,
    name                        TEXT,
    -- 人格核心配置
    big_five_json               TEXT NOT NULL,
    attachment_anxiety          REAL DEFAULT 0.5,
    attachment_avoidance        REAL DEFAULT 0.3,
    self_esteem_stability       REAL DEFAULT 0.7,
    social_rhythm               TEXT DEFAULT 'slow_warm',
    conflict_style              TEXT DEFAULT 'cold_shoulder',
    initiative_tendency         REAL DEFAULT 0.5,
    -- 打字风格（速度由牵线人按人设设定，非固定枚举）
    input_method                TEXT DEFAULT 'phone_thumb',
    typing_style_json           TEXT,
    typing_speed                REAL DEFAULT 2.5,
    -- 能力插件 + 图片风格
    image_style_prompt          TEXT,
    character_appearance        TEXT,
    image_enabled               INTEGER DEFAULT 1
                                CHECK (image_enabled IN (0, 1)),
    -- 安全
    api_key_encrypted           TEXT,
    -- 牵线人产出
    sample_chats_json           TEXT,
    -- 角色人生系统
    character_initial_world_time TEXT,
    birthday                    TEXT,
    character_current_context   TEXT,
    -- 角色生命阶段系统
    life_stage                  TEXT,
    life_stage_detail           TEXT,
    current_location            TEXT,
    owner_qq                    TEXT,
    relationship_phase          TEXT DEFAULT 'stranger'
                                CHECK (relationship_phase IN ('stranger','acquaintance','friend','close_friend')),
    ai_qq                       TEXT,
    matchmaker_raw_data         TEXT,
    status                      TEXT DEFAULT 'active'
                                CHECK (status IN ('active', 'sleeping', 'paused', 'archived', 'request_pending')),
    last_user_message_time       TEXT,
    created_at                  TEXT DEFAULT (datetime('now')),
    updated_at                  TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_personas_owner_qq ON personas(owner_qq);
CREATE INDEX IF NOT EXISTS idx_personas_ai_qq ON personas(ai_qq);
CREATE INDEX IF NOT EXISTS idx_personas_status ON personas(status);

-- ============================================================
-- 表 2：角色人生档案 —— 大字段低频读写，独立存储避免拖慢 Persona 主查询
-- ============================================================
CREATE TABLE IF NOT EXISTS character_life_archives (
    persona_id   TEXT PRIMARY KEY,
    archive_json TEXT NOT NULL,
    created_at   TEXT DEFAULT (datetime('now')),
    updated_at   TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (persona_id) REFERENCES personas(id)
);

-- ============================================================
-- 表 3：关系状态 —— 惰性心跳衰减的数值载体（与 Persona 一一对应）
-- ============================================================
CREATE TABLE IF NOT EXISTS relationship_state (
    persona_id         TEXT PRIMARY KEY,
    trust              REAL DEFAULT 50   CHECK (trust >= 0 AND trust <= 100),
    closeness          REAL DEFAULT 20   CHECK (closeness >= 0 AND closeness <= 100),
    tension            REAL DEFAULT 0    CHECK (tension >= 0),
    emotional_energy   REAL DEFAULT 30   CHECK (emotional_energy >= 0 AND emotional_energy <= 100),
    tension_pressure   REAL DEFAULT 0,
    contact_urge       REAL DEFAULT 0,
    last_heartbeat_at  TEXT,
    updated_at         TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (persona_id) REFERENCES personas(id)
);

-- ============================================================
-- 表 4：事件线 —— AI 的一天时间轴，每天凌晨由 LLM 生成
-- ============================================================
CREATE TABLE IF NOT EXISTS daily_events (
    id          TEXT PRIMARY KEY,
    persona_id  TEXT NOT NULL,
    event_date  TEXT NOT NULL,
    event_time  TEXT NOT NULL,
    event_type  TEXT NOT NULL
                CHECK (event_type IN ('routine', 'moment', 'sleep')),
    description TEXT,
    is_active   INTEGER DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at  TEXT DEFAULT (datetime('now')),
    updated_at  TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (persona_id) REFERENCES personas(id)
);

CREATE INDEX IF NOT EXISTS idx_daily_events_scan
    ON daily_events(persona_id, event_date, is_active, event_time);

-- ============================================================
-- 表 5：消息队列 —— 三级扫描调度 + 连发组
-- ============================================================
CREATE TABLE IF NOT EXISTS scheduled_messages (
    id                 TEXT PRIMARY KEY,
    persona_id         TEXT NOT NULL,
    scheduled_time     TEXT NOT NULL,
    actual_send_time   TEXT,
    burst_group_id     TEXT,
    burst_order        INTEGER DEFAULT 0,
    items_json         TEXT NOT NULL,
    inner_thought_json TEXT,
    mood               TEXT,
    is_sent            INTEGER DEFAULT 0 CHECK (is_sent IN (0, 1)),
    created_at         TEXT DEFAULT (datetime('now')),
    updated_at         TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (persona_id) REFERENCES personas(id)
);

CREATE INDEX IF NOT EXISTS idx_scheduled_msgs_scan
    ON scheduled_messages(persona_id, is_sent, scheduled_time);

-- ============================================================
-- 表 6：事件日志 —— 系统记忆日记本
-- ============================================================
CREATE TABLE IF NOT EXISTS event_log (
    id                  TEXT PRIMARY KEY,
    persona_id          TEXT NOT NULL,
    event_type          TEXT,
    log_type            TEXT DEFAULT 'event'
                        CHECK (log_type IN ('event','inner_thought','reflection','state_snapshot','conversation_turn')),
    description         TEXT,
    detail_json         TEXT,
    state_snapshot_json TEXT,
    occurred_at         TEXT,
    created_at          TEXT DEFAULT (datetime('now')),
    FOREIGN KEY (persona_id) REFERENCES personas(id)
);

CREATE INDEX IF NOT EXISTS idx_event_log_query
    ON event_log(persona_id, log_type, created_at);

-- ============================================================
-- 表 7：牵线人访谈会话 —— 7 阶段多轮对话的临时数据
-- ============================================================
CREATE TABLE IF NOT EXISTS matchmaker_sessions (
    session_id          TEXT PRIMARY KEY,
    current_stage       TEXT NOT NULL DEFAULT 'basic_profile'
                        CHECK (current_stage IN (
                            'basic_profile','style_anchor','boundary_probe',
                            'attachment_explore','system_detail','sample_confirm'
                        )),
    collected_data_json TEXT DEFAULT '{}',
    history_json        TEXT DEFAULT '[]',
    persona_id          TEXT,
    status              TEXT DEFAULT 'in_progress'
                        CHECK (status IN ('in_progress', 'completed', 'abandoned')),
    created_at          TEXT DEFAULT (datetime('now')),
    updated_at          TEXT DEFAULT (datetime('now'))
);

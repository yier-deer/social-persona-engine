"""
Pydantic 请求/响应 Schema 定义

★ 为什么用 Pydantic 而非手写字典：
  1. FastAPI 自动校验请求体类型（str 不能传 int）
  2. FastAPI 自动生成 OpenAPI JSON（Day 5 最关键产出）
  3. Swagger UI 自动显示字段文档和示例

Naming convention:
  XxxRequest  = Java → Python request body
  XxxResponse = Python → Java response body
"""
from typing import Optional, List, Any, Dict
from pydantic import BaseModel, Field


# ==================== /api/message ====================

class MessageRequest(BaseModel):
    """用户消息请求"""
    api_config: dict = Field(..., description="API配置：{provider, api_key, base_url, model}")
    persona_config: dict = Field(..., description="完整人格配置")
    relationship_state: dict = Field(..., description="当前关系状态：{trust, closeness, tension, emotional_energy, tension_pressure, contact_urge}")
    recent_memories: list = Field(default_factory=list, description="近期记忆列表")
    today_events_so_far: list = Field(default_factory=list, description="今日已发生活跃事件")
    user_message: str = Field(..., description="用户发送的原始文本")
    timestamp: int = Field(default=0, description="消息时间戳")
    image_config: Optional[dict] = Field(default=None, description="图片生成配置：{provider, api_key, base_url, model}，None=不启用图片")


class ReplyItem(BaseModel):
    """发送序列项"""
    type: str = Field(..., description="text | image")
    content: Optional[str] = None
    content_prompt: Optional[str] = None
    generation_mode: Optional[str] = None
    delay_ms: Optional[int] = None


class Reply(BaseModel):
    """回复内容对象"""
    raw_text: str = ""
    items: List[ReplyItem] = Field(default_factory=list)
    mood: str = ""


class InnerThought(BaseModel):
    """内心独白"""
    raw_thought: str = ""
    attitude: str = "neutral"
    should_remember: bool = False
    memorable_point: Optional[str] = None


class RelationshipDeltas(BaseModel):
    """关系变化增量"""
    trust_delta: float = 0
    closeness_delta: float = 0
    tension_delta: float = 0
    emotional_energy_delta: float = 0
    contact_urge_delta: float = 0
    is_qualitative_leap: bool = False


class MessageResponse(BaseModel):
    """用户消息响应 —— 三层判断模型"""
    should_reply: bool = False
    reply: Optional[Reply] = None
    inner_thought: Optional[InnerThought] = None
    relationship_deltas: Optional[RelationshipDeltas] = None
    conversation_ended: bool = False
    interval_pre_scheduling: Optional[dict] = None
    event_changed: bool = False
    cancelled_scheduled_messages: List[str] = Field(default_factory=list)
    invalidated_events: List[str] = Field(default_factory=list)
    new_events: List[dict] = Field(default_factory=list)


# ==================== /api/event/trigger ====================

class EventTriggerRequest(BaseModel):
    """事件触发请求"""
    api_config: dict
    persona_config: dict
    relationship_state: dict
    recent_memories: list = Field(default_factory=list)
    today_events_so_far: list = Field(default_factory=list)
    current_event: dict = Field(default_factory=dict)
    next_event_type: str = ""
    next_event_time: str = ""
    now: int = 0
    image_config: Optional[dict] = Field(default=None, description="图片生成配置，None=不启用图片")


class EventTriggerResponse(BaseModel):
    """事件触发响应"""
    should_contact_user: bool = False
    reply: Optional[Reply] = None
    inner_thought: Optional[InnerThought] = None
    relationship_deltas: Optional[RelationshipDeltas] = None
    conversation_ended: bool = True
    interval_pre_scheduling: Optional[dict] = None
    event_changed: bool = False
    cancelled_scheduled_messages: List[str] = Field(default_factory=list)
    invalidated_events: List[str] = Field(default_factory=list)
    new_events: List[dict] = Field(default_factory=list)


# ==================== /api/event/generate ====================

class EventGenerateRequest(BaseModel):
    """事件线生成请求"""
    api_config: dict
    persona_config: dict
    relationship_state: dict
    today_date: str = ""
    day_of_week: str = ""
    yesterday_events: list = Field(default_factory=list)
    today_inner_thoughts: list = Field(default_factory=list)


class LifeStageTransition(BaseModel):
    """生命阶段切换声明"""
    should_transition: bool = False
    new_life_stage: str = ""
    new_life_stage_detail: str = ""
    new_location: str = ""
    transition_reason: str = ""


class TodayReflection(BaseModel):
    """今日反思"""
    raw_text: str = ""
    key_memories: List[dict] = Field(default_factory=list)
    relationship_summary: str = ""
    life_stage_transition: Optional[LifeStageTransition] = None


class EventGenerateResponse(BaseModel):
    """事件线生成响应"""
    today_reflection: Optional[TodayReflection] = None
    events: List[dict] = Field(default_factory=list)


# ==================== /api/matchmaker ====================

class MatchmakerRequest(BaseModel):
    """牵线人请求"""
    api_config: dict
    session_id: str = ""
    current_stage: str = "basic_profile"
    history: list = Field(default_factory=list)
    user_message: str = ""
    collected_data: dict = Field(default_factory=dict)
    language_hint: str = ""


class MatchmakerResponse(BaseModel):
    """牵线人响应"""
    reply: str = ""
    next_stage: str = "basic_profile"
    extracted_data: dict = Field(default_factory=dict)
    is_complete: bool = False
    persona_config: Optional[dict] = None
    sample_chats: Optional[list] = None
    life_archive_json: Optional[dict] = None


# ==================== /api/health ====================

class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str = "ok"
    llm_connected: Optional[bool] = None
    memory_connected: bool = True
    uptime_seconds: int = 0


# ==================== /api/image/generate ====================

class ImageGenerateRequest(BaseModel):
    """图片生成请求"""
    image_config: dict = Field(..., description="图片生成配置：{provider, api_key, base_url, model}")
    prompt: str = Field(..., description="图片内容描述")
    persona_id: str = Field(default="", description="Persona ID，用于组织文件目录")
    persona_dir_name: str = Field(default="", description="图片文件夹名（name-uuid8格式），优先级高于 persona_id")
    context: str = Field(default="reply", description="业务上下文：reply/事件类型")


class ImageGenerateResponse(BaseModel):
    """图片生成响应"""
    success: bool = False
    local_path: str = ""
    width: int = 0
    height: int = 0
    error: str = ""

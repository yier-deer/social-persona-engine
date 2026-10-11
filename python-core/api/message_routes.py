"""
用户消息处理端点 —— POST /api/message

三层判断模型：
  Layer 1: should_reply — 要不要回复？
  Layer 2: reply + inner_thought + relationship_deltas — 怎么回复？
  Layer 3: conversation_ended — 对话结束了吗？

Day 6 新增：Mem0 记忆检索注入 + 回复后记忆写入
"""
from fastapi import APIRouter, BackgroundTasks
from starlette.concurrency import run_in_threadpool
from api.errors import upstream_error
from api.normalization import _safe_dict, _safe_list, _safe_bool, _safe_reply, _safe_inner_thought, _safe_deltas
from api.models import MessageRequest, MessageResponse, Reply, InnerThought, RelationshipDeltas, ReplyItem
from engine.llm.factory import create_provider
from engine.prompt_templates import build_message_system_prompt
from engine.memory import memory_service

router = APIRouter()


@router.post("/api/message", response_model=MessageResponse)
async def handle_message(request: MessageRequest, background_tasks: BackgroundTasks = None):
    """
    ★ 用户发消息 → AI 回复

    完整流程（Day 6 增强）：
      1. 组装 System Prompt（人设 + 关系 + 事件）
      2. ★ 调 Mem0 语义检索相关记忆 → 注入 User Prompt
      3. 调用 LLM
      4. 解析 LLM 返回的三层判断 JSON
      5. ★ 如果 should_remember=true → 调 Mem0 写入记忆
    """
    try:
        provider = create_provider(request.api_config)

        persona_id = request.persona_config.get("id", "")
        current_context = request.persona_config.get("character_current_context", "")

        # ★ Day 6 新增：Mem0 记忆检索
        # 用用户消息 + 当前处境作为搜索 query → 召回最相关的历史记忆
        mem_query = f"用户说: {request.user_message} | 当前处境: {current_context}"
        mem_results = await run_in_threadpool(memory_service.search, mem_query, persona_id, limit=5, api_config=request.api_config)

        elapsed = request.persona_config.get("last_user_message_elapsed", None)
        system_prompt = build_message_system_prompt(
            request.persona_config,
            request.relationship_state,
            request.today_events_so_far,
            current_context,
            image_config=request.image_config,
            recent_conversations=request.persona_config.get("recent_conversations", []),
            elapsed=elapsed
        )

        # 组装 User Prompt（含 Java 传入的近期记忆 + Python Mem0 远程记忆）
        user_prompt = f"用户刚才说：'{request.user_message}'"
        all_memories = list(request.recent_memories or [])
        for m in mem_results:
            all_memories.append({"content": m})
        if all_memories:
            memory_text = "\n".join(
                f"- {m.get('content', '') if isinstance(m, dict) else m}" for m in all_memories[:5]
            )
            user_prompt += f"\n\n【相关记忆】\n{memory_text}"

        result = await provider.chat(
            system_prompt, user_prompt,
            response_format={"type": "json_object"}
        )

        # ★ Day 6 新增：如果 LLM 认为值得记住 → 写入 Mem0
        inner_thought = _safe_inner_thought(result.get("inner_thought")) or {}
        if inner_thought.get("should_remember"):
            memorable = inner_thought.get("memorable_point", "")
            if memorable:
                if background_tasks is not None:
                    background_tasks.add_task(memory_service.add, memorable, persona_id, api_config=request.api_config)
                else:
                    await run_in_threadpool(memory_service.add, memorable, persona_id, api_config=request.api_config)

        return MessageResponse(
            should_reply=_safe_bool(result.get("should_reply")),
            reply=_safe_reply(result.get("reply")),
            inner_thought=_safe_inner_thought(result.get("inner_thought")),
            relationship_deltas=_safe_deltas(result.get("relationship_deltas")),
            conversation_ended=result.get("conversation_ended", False),
            interval_pre_scheduling=_safe_dict(result.get("interval_pre_scheduling")),
            event_changed=result.get("event_changed", False),
            cancelled_scheduled_messages=_safe_list(result.get("cancelled_scheduled_messages")),
            invalidated_events=_safe_list(result.get("invalidated_events")),
            new_events=_safe_list(result.get("new_events")),
        )

    except Exception as e:
        raise upstream_error("message", e) from None

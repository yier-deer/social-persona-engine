"""
牵线人端点 —— POST /api/matchmaker

7 阶段渐进式访谈：basic_profile → style_anchor → boundary_probe →
  attachment_explore → system_detail → capability_config → sample_confirm
"""
from fastapi import APIRouter
from api.errors import upstream_error
from api.models import MatchmakerRequest, MatchmakerResponse
from engine.llm.factory import create_provider
from engine.prompt_templates import build_matchmaker_prompt

router = APIRouter()


@router.post("/api/matchmaker", response_model=MatchmakerResponse)
async def matchmaker_chat(request: MatchmakerRequest):
    """牵线人访谈多轮对话"""
    try:
        provider = create_provider(request.api_config)

        system_prompt = build_matchmaker_prompt(
            request.current_stage,
            request.collected_data,
            request.history,
            language_hint=request.language_hint
        )

        user_prompt = request.user_message

        result = await provider.chat(
            system_prompt, user_prompt,
            response_format={"type": "json_object"}
        )

        return MatchmakerResponse(
            reply=result.get("reply", ""),
            next_stage=result.get("next_stage", request.current_stage),
            extracted_data=result.get("extracted_data", {}),
            is_complete=result.get("is_complete", False),
            persona_config=result.get("persona_config"),
            sample_chats=result.get("sample_chats"),
            life_archive_json=result.get("life_archive_json"),
        )

    except Exception as e:
        raise upstream_error("matchmaker", e) from None

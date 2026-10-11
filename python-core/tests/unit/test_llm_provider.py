from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
import httpx
from openai import AsyncOpenAI
from anthropic import AsyncAnthropic

from engine.llm.factory import create_provider
from engine.llm.openai_provider import OpenAICompatibleProvider


def test_custom_provider_uses_configured_endpoint():
    provider = create_provider({
        "provider": "custom", "api_key": "test-only",
        "base_url": "http://127.0.0.1:9900/v1", "model": "local-demo",
    })
    assert isinstance(provider, OpenAICompatibleProvider)
    assert str(provider.client.base_url) == "http://127.0.0.1:9900/v1/"
    assert provider.model == "local-demo"


def test_provider_defaults_belong_to_selected_provider():
    provider = create_provider({"provider": "openai", "api_key": "test-only"})
    assert str(provider.client.base_url) == "https://api.openai.com/v1/"
    assert provider.model == "gpt-4o"


@pytest.mark.parametrize("field", ["api_key", "model", "base_url"])
def test_custom_provider_requires_complete_configuration(field):
    config = {"provider": "custom", "api_key": "test-only",
              "base_url": "http://127.0.0.1:9900/v1", "model": "demo"}
    config[field] = ""
    with pytest.raises(ValueError):
        create_provider(config)


@pytest.mark.parametrize("content", ['```\n{"reply":"你好"}\n```', '```json\n{"reply":"你好"}\n```'])
async def test_model_json_fences_are_parsed(content):
    provider = OpenAICompatibleProvider("http://127.0.0.1:9900/v1", "test-only", "demo")
    provider.client.chat.completions.create = AsyncMock(return_value=SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))]))
    assert await provider.chat("Return JSON", "hello") == {"reply": "你好"}


@pytest.mark.parametrize("content", ["[]", "null", None])
async def test_model_response_must_be_a_json_object(content):
    provider = OpenAICompatibleProvider("http://127.0.0.1:9900/v1", "test-only", "demo")
    provider.client.chat.completions.create = AsyncMock(return_value=SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))]))
    with pytest.raises(ValueError):
        await provider.chat("Return JSON", "hello")


def test_anthropic_uses_native_messages_provider():
    provider = create_provider({"provider": "anthropic", "api_key": "test-only"})
    assert type(provider).__name__ == "AnthropicProvider"


@pytest.mark.parametrize("provider_name", ["openai", "anthropic"])
async def test_same_provider_can_make_two_requests_without_reusing_a_closed_client(monkeypatch, provider_name):
    from engine.llm import openai_provider, anthropic_provider

    calls = []

    def respond(request):
        calls.append(request.url.path)
        if provider_name == "openai":
            body = {"id": "test", "object": "chat.completion", "created": 0, "model": "demo",
                    "choices": [{"index": 0, "finish_reason": "stop",
                                 "message": {"role": "assistant", "content": '{"reply":"ok"}'}}]}
        else:
            body = {"id": "test", "type": "message", "role": "assistant", "model": "demo",
                    "content": [{"type": "text", "text": '{"reply":"ok"}'}],
                    "stop_reason": "end_turn", "usage": {"input_tokens": 1, "output_tokens": 1}}
        return httpx.Response(200, json=body)

    sdk_class = AsyncOpenAI if provider_name == "openai" else AsyncAnthropic

    def build_client(**kwargs):
        return sdk_class(**kwargs, http_client=httpx.AsyncClient(transport=httpx.MockTransport(respond)))

    module = openai_provider if provider_name == "openai" else anthropic_provider
    monkeypatch.setattr(module, "AsyncOpenAI" if provider_name == "openai" else "AsyncAnthropic", build_client)
    provider = create_provider({"provider": provider_name, "api_key": "test-only",
                                "base_url": "http://model.test/v1", "model": "demo"})
    try:
        assert await provider.chat("Return JSON", "first") == {"reply": "ok"}
        assert await provider.chat("Return JSON", "retry") == {"reply": "ok"}
        assert len(calls) == 2
    finally:
        await provider.client.close()

import asyncio
import logging
import time
import threading
from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest
from fastapi.testclient import TestClient

from main import app
from api import message_routes, event_routes, matchmaker_routes
from engine.memory import memory_service


MESSAGE = {"api_config": {"provider": "custom", "api_key": "test-only",
                         "base_url": "http://127.0.0.1:9900/v1", "model": "demo"},
           "persona_config": {"id": "demo-persona"}, "relationship_state": {},
           "user_message": "hello"}


@pytest.fixture
def model(monkeypatch):
    provider = MagicMock()
    provider.chat = AsyncMock(return_value={"should_reply": True,
                                            "reply": {"raw_text": "你好", "items": []},
                                            "inner_thought": None})
    for module in (message_routes, event_routes, matchmaker_routes):
        monkeypatch.setattr(module, "create_provider", lambda _: provider)
    monkeypatch.setattr(memory_service, "search", lambda *args, **kwargs: [])
    monkeypatch.setattr(memory_service, "add", lambda *args, **kwargs: None)
    return provider


def test_null_optional_thought_does_not_discard_reply(model):
    response = TestClient(app).post("/api/message", json=MESSAGE)
    assert response.status_code == 200
    assert response.json()["should_reply"] is True
    assert response.json()["reply"]["raw_text"] == "你好"


@pytest.mark.parametrize("path,payload", [
    ("/api/message", MESSAGE),
    ("/api/matchmaker", {"api_config": MESSAGE["api_config"], "user_message": "hello"}),
    ("/api/event/trigger", {**MESSAGE, "current_event": {"type": "routine"}}),
    ("/api/event/generate", MESSAGE),
])
def test_upstream_failure_is_observable_and_sanitized(path, payload, model, caplog):
    model.chat.side_effect = RuntimeError("Authorization: secret-marker-test")
    response = TestClient(app).post(path, json=payload)
    assert response.status_code == 502
    assert "secret-marker-test" not in response.text
    assert "secret-marker-test" not in caplog.text


def test_request_validation_does_not_echo_credentials():
    response = TestClient(app).post("/api/message", json={"api_config": {"api_key": "secret-marker-test"}})
    assert response.status_code == 422
    assert "secret-marker-test" not in response.text


def test_health_does_not_claim_a_model_connection():
    response = TestClient(app).get("/api/health")
    assert response.status_code == 200
    assert response.json()["llm_connected"] is None


def test_model_sdks_do_not_log_prompts_or_credentials_at_debug():
    assert logging.getLogger("openai").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("anthropic").getEffectiveLevel() >= logging.WARNING


async def test_blocking_memory_cannot_stall_other_api_requests(model, monkeypatch):
    def slow_search(*args, **kwargs):
        time.sleep(0.5)
        return []

    monkeypatch.setattr(memory_service, "search", slow_search)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        started = time.monotonic()
        pending = asyncio.create_task(client.post("/api/message", json=MESSAGE))
        await asyncio.sleep(0.02)
        health = await client.get("/api/health")
        health_elapsed = time.monotonic() - started
        await pending
    assert health.status_code == 200
    assert health_elapsed < 0.25


async def test_event_memory_write_runs_outside_the_event_loop(model, monkeypatch):
    model.chat.return_value = {"should_contact_user": False,
                              "inner_thought": {"should_remember": True, "memorable_point": "test fact"}}
    caller_thread = threading.get_ident()
    write_threads = []
    monkeypatch.setattr(memory_service, "add", lambda *args, **kwargs: write_threads.append(threading.get_ident()))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/api/event/trigger", json=MESSAGE)
    assert response.status_code == 200
    assert write_threads and write_threads[0] != caller_thread

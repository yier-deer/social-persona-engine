from unittest.mock import MagicMock, patch

import pytest

from engine.memory import MemoryService


CONFIG = {"provider": "custom", "api_key": "test-only",
          "base_url": "http://127.0.0.1:9900/v1", "model": "demo"}


def test_memory_preserves_openai_endpoint_prefix():
    with patch("engine.memory.Memory") as backend:
        assert MemoryService().initialize(CONFIG)
        config = backend.from_config.call_args.args[0]
        assert config["llm"]["config"]["openai_base_url"] == CONFIG["base_url"]


@pytest.mark.parametrize("results", [[{"memory": "记得你喜欢咖啡"}],
                                    {"results": [{"memory": "记得你喜欢咖啡"}]}])
def test_memory_accepts_legacy_and_modern_search_results(results):
    service = MemoryService()
    service._memory = MagicMock()
    service._memory.search.return_value = results
    assert service.search("喜欢什么", "persona-a") == ["记得你喜欢咖啡"]
    service._memory.search.assert_called_once_with("喜欢什么", user_id="persona-a", limit=5)


def test_changed_api_configuration_reinitializes_memory():
    with patch("engine.memory.Memory") as backend:
        backend.from_config.return_value.search.return_value = {"results": []}
        service = MemoryService()
        service.search("one", "persona-a", api_config=CONFIG)
        service.search("two", "persona-a", api_config={**CONFIG, "api_key": "rotated-test-only"})
        assert backend.from_config.call_count == 2
        assert backend.from_config.call_args.args[0]["llm"]["config"]["api_key"] == "rotated-test-only"


def test_memory_refuses_unscoped_reads_and_writes():
    service = MemoryService()
    service._memory = MagicMock()
    assert service.search("query", "") == []
    service.add("fact", "")
    service.add_batch([{"content": "fact", "importance": 9}], "")
    service._memory.search.assert_not_called()
    service._memory.add.assert_not_called()


def test_memory_failure_does_not_log_provider_secrets(caplog):
    with patch("engine.memory.Memory") as backend:
        backend.from_config.side_effect = RuntimeError("Authorization: secret-marker-test")
        assert not MemoryService().initialize(CONFIG)
        assert "secret-marker-test" not in caplog.text


def test_batch_accepts_malformed_optional_memory_items():
    service = MemoryService()
    service._memory = MagicMock()
    service.add_batch([None, {"content": "valid fact", "importance": 9}], "persona-a")
    service._memory.add.assert_called_once_with("valid fact", user_id="persona-a")

"""Persona-scoped Mem0 storage. Call blocking operations from worker threads."""
import hashlib
import json
import logging
import os
import threading
from typing import Optional

# Disable optional analytics for this local persona store.
os.environ.setdefault("MEM0_TELEMETRY", "False")
os.environ.setdefault("ANONYMIZED_TELEMETRY", "False")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

from mem0 import Memory
from engine.llm.factory import resolve_api_config

logger = logging.getLogger(__name__)
CORE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHROMA_PERSIST_DIR = os.path.join(CORE_DIR, "data", "chromadb")
LOCAL_MODEL_PATH = os.path.join(CORE_DIR, "data", "models", "BAAI--bge-small-zh-v1.5")


class MemoryService:
    def __init__(self):
        self._memory: Optional[Memory] = None
        self._initialized = False
        self._config_fingerprint = None
        self._lock = threading.RLock()
        self.last_error = None

    def initialize(self, api_config: dict) -> bool:
        with self._lock:
            fingerprint = hashlib.sha256(json.dumps(api_config, sort_keys=True).encode()).hexdigest()
            if self._memory is not None and fingerprint == self._config_fingerprint:
                return True
            try:
                selected = resolve_api_config(api_config)
                api_key, base_url, model = (selected[key] for key in ("api_key", "base_url", "model"))
                native = selected["provider"] == "anthropic"
                llm_url = base_url[:-3] if native and base_url.endswith("/v1") else base_url
                config = {
                    "version": "v1.1",
                    "llm": {"provider": "anthropic" if native else "openai", "config": {
                        "api_key": api_key, "model": model,
                        "anthropic_base_url" if native else "openai_base_url": llm_url,
                    }},
                    "embedder": {"provider": "huggingface", "config": {"model": LOCAL_MODEL_PATH}},
                    "vector_store": {"provider": "chroma", "config": {
                        "collection_name": "social_persona_memories", "path": CHROMA_PERSIST_DIR,
                    }},
                    "history_db_path": os.path.join(CORE_DIR, "data", "mem0_history.db"),
                }
                embedder_url = str(api_config.get("embedder_base_url") or "").strip()
                if embedder_url:
                    config["embedder"] = {"provider": "openai", "config": {
                        "api_key": api_config.get("embedder_api_key") or api_key,
                        "model": api_config.get("embedder_model") or "BAAI/bge-large-zh-v1.5",
                        "openai_base_url": embedder_url.rstrip("/"),
                    }}
                memory = Memory.from_config(config)
                # Some Mem0 versions ignore Anthropic's base URL, or honor ambient
                # OpenRouter credentials. Bind the SDK explicitly to this request.
                memory.llm.client.api_key = api_key
                memory.llm.client.base_url = llm_url
                memory.llm.client.timeout = 60.0
                memory.llm.client.max_retries = 1
                self._memory = memory
                self._initialized = True
                self._config_fingerprint = fingerprint
                self.last_error = None
                logger.info("Mem0 ready: provider=%s model=%s", selected["provider"], model)
                return True
            except Exception as error:
                self._memory = None
                self._initialized = False
                self._config_fingerprint = None
                self.last_error = type(error).__name__
                logger.warning("Mem0 initialization unavailable (%s)", self.last_error)
                return False

    def _ensure_memory(self, api_config):
        if api_config:
            self.initialize(api_config)
        return self._memory is not None

    def search(self, query: str, persona_id: str, limit: int = 5, api_config=None) -> list[str]:
        if not persona_id or not query:
            return []
        with self._lock:
            if not self._ensure_memory(api_config):
                return []
            try:
                results = self._memory.search(query, user_id=persona_id, limit=limit)
                if isinstance(results, dict):
                    results = results.get("results", [])
                if not isinstance(results, list):
                    return []
                memories = []
                for item in results:
                    text = item.get("memory") if isinstance(item, dict) else item
                    if isinstance(text, str) and text.strip():
                        memories.append(text)
                return memories[:limit]
            except Exception as error:
                logger.warning("Mem0 search unavailable (%s)", type(error).__name__)
                return []

    def add(self, content: str, persona_id: str, api_config=None):
        if not persona_id or not isinstance(content, str) or not content.strip():
            return
        with self._lock:
            if not self._ensure_memory(api_config):
                return
            try:
                self._memory.add(content, user_id=persona_id)
                logger.debug("Mem0 fact stored for persona=%s", persona_id)
            except Exception as error:
                logger.warning("Mem0 write unavailable (%s)", type(error).__name__)

    def add_batch(self, items: list, persona_id: str, api_config=None):
        if not persona_id or not isinstance(items, list):
            return
        for item in items:
            if not isinstance(item, dict):
                continue
            try:
                important = float(item.get("importance") or 0) >= 7
            except (TypeError, ValueError):
                continue
            if important:
                self.add(item.get("content"), persona_id, api_config=api_config)


memory_service = MemoryService()

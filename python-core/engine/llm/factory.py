"""
LLM Provider 工厂

职责：根据请求中的 api_config 创建对应的 LLMProvider 实例。

为什么用工厂而非每个端点自己写 if/else：
  6 个端点都需要创建 Provider，统一工厂避免重复代码。
  未来加新 Provider（如 Claude 原生 SDK）也只需改工厂。
"""
from .provider import LLMProvider
from .openai_provider import OpenAICompatibleProvider
from urllib.parse import urlsplit


DEFAULTS = {
    "deepseek": ("https://api.deepseek.com/v1", "deepseek-chat"),
    "openai": ("https://api.openai.com/v1", "gpt-4o"),
    "anthropic": ("https://api.anthropic.com/v1", "claude-sonnet-4-20250514"),
    "custom": ("", ""),
}


def resolve_api_config(api_config: dict) -> dict:
    provider = str(api_config.get("provider") or "deepseek").strip().lower()
    if provider not in DEFAULTS:
        raise ValueError("Unsupported LLM provider")
    default_url, default_model = DEFAULTS[provider]
    config = {
        "provider": provider,
        "api_key": str(api_config.get("api_key") or "").strip(),
        "base_url": str(api_config.get("base_url") or default_url).strip().rstrip("/"),
        "model": str(api_config.get("model") or default_model).strip(),
    }
    if not config["api_key"] or not config["model"] or not config["base_url"]:
        raise ValueError("API key, model and endpoint are required")
    url = urlsplit(config["base_url"])
    if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
        raise ValueError("Endpoint must be an HTTP(S) URL without embedded credentials")
    return config


def create_provider(api_config: dict) -> LLMProvider:
    """
    根据 API 配置创建 LLM Provider

    Args:
        api_config: Java 传来的 api_config 对象
            {
                "provider": "deepseek" | "openai" | "anthropic",
                "api_key": "sk-xxx",       ← Java 已解密，明文
                "base_url": "https://api.deepseek.com/v1",
                "model": "deepseek-chat"
            }

    Returns:
        LLMProvider 实例

    ★ 为什么返回抽象类型 LLMProvider：
      调用方不关心具体是 DeepSeek 还是 OpenAI，只调用 provider.chat()。
    """
    config = resolve_api_config(api_config)
    args = {key: config[key] for key in ("base_url", "api_key", "model")}
    if config["provider"] == "anthropic":
        from .anthropic_provider import AnthropicProvider
        return AnthropicProvider(**args)
    return OpenAICompatibleProvider(**args)


def create_image_generator(image_config: dict):
    from engine.image.generator import create_image_generator as _create
    return _create(image_config)

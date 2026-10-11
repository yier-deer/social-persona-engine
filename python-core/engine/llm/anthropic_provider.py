"""Claude uses the native Messages API."""
from anthropic import AsyncAnthropic

from .provider import LLMProvider
from .json_response import parse_json_object


class AnthropicProvider(LLMProvider):
    def __init__(self, base_url: str, api_key: str, model: str):
        endpoint = base_url.rstrip("/")
        if endpoint.endswith("/v1"):
            endpoint = endpoint[:-3]
        self._client_options = dict(base_url=endpoint, api_key=api_key, timeout=60.0, max_retries=1)
        self.client = AsyncAnthropic(**self._client_options)
        self.model = model

    async def chat(self, system_prompt, user_prompt, response_format=None) -> dict:
        if response_format:
            system_prompt += "\nReturn one JSON object only, without markdown or surrounding text."
        if self.client.is_closed():
            self.client = AsyncAnthropic(**self._client_options)
        async with self.client:
            response = await self.client.messages.create(
                model=self.model, max_tokens=4096, system=system_prompt,
                messages=[{"role": "user", "content": user_prompt}],
            )
        content = "".join(block.text for block in response.content if block.type == "text")
        return parse_json_object(content)

"""Parse structured model output without accepting non-object JSON."""
import json
import re


def parse_json_object(content: str) -> dict:
    if not isinstance(content, str) or not content.strip():
        raise ValueError("Model returned an empty response")
    text = content.strip()
    fenced = re.fullmatch(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.DOTALL | re.IGNORECASE)
    if fenced:
        text = fenced.group(1).strip()
    result = json.loads(text)
    if not isinstance(result, dict):
        raise ValueError("Model response must be a JSON object")
    return result

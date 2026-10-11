"""Normalize optional fields which models sometimes emit as null or empty strings."""
import math


def _safe_dict(raw):
    return raw if isinstance(raw, dict) else None


def _safe_list(raw):
    return raw if isinstance(raw, list) else []


def _safe_bool(raw):
    if isinstance(raw, str):
        return raw.strip().lower() in ("true", "1", "yes")
    return bool(raw)


def _safe_float(raw):
    try:
        value = float(raw or 0)
        return value if math.isfinite(value) else 0.0
    except (TypeError, ValueError):
        return 0.0


def _safe_inner_thought(raw):
    if not isinstance(raw, dict):
        return None
    return {
        "raw_thought": str(raw.get("raw_thought") or ""),
        "attitude": str(raw.get("attitude") or "neutral"),
        "should_remember": _safe_bool(raw.get("should_remember")),
        "memorable_point": raw.get("memorable_point") if isinstance(raw.get("memorable_point"), str) else None,
    }


def _safe_reply(raw):
    if not isinstance(raw, dict):
        return None
    items = []
    for item in _safe_list(raw.get("items")):
        if not isinstance(item, dict):
            continue
        clean = {"type": str(item.get("type") or "text")}
        for field in ("content", "content_prompt", "generation_mode"):
            clean[field] = item.get(field) if isinstance(item.get(field), str) else None
        delay = item.get("delay_ms")
        clean["delay_ms"] = max(0, delay) if isinstance(delay, int) else None
        items.append(clean)
    return {"raw_text": str(raw.get("raw_text") or ""), "items": items, "mood": str(raw.get("mood") or "")}


def _safe_deltas(raw):
    if not isinstance(raw, dict):
        return None
    result = {field: _safe_float(raw.get(field)) for field in (
        "trust_delta", "closeness_delta", "tension_delta", "emotional_energy_delta", "contact_urge_delta")}
    result["is_qualitative_leap"] = _safe_bool(raw.get("is_qualitative_leap"))
    return result

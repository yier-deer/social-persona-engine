"""Safe diagnostics: upstream response bodies can contain keys and private prompts."""
import logging
from fastapi import HTTPException

logger = logging.getLogger(__name__)


def upstream_error(operation: str, error: Exception) -> HTTPException:
    logger.error("Model operation %s failed (%s)", operation, type(error).__name__)
    return HTTPException(status_code=502, detail="模型请求或回复解析失败，请检查模型配置和服务状态后重试。")

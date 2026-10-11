"""
健康检查路由 —— GET /api/health

设计依据：规格书 二、IPC契约 → 端点⑥

职责：
  告诉 Java 侧 Python 核心是否存活、LLM 和记忆系统是否可用。
  这是 Java 启动后第一个调用的端点，也是定期心跳检测的目标。
"""
import time
from fastapi import APIRouter
from engine.memory import memory_service

# APIRouter 允许将路由定义拆分到不同文件，最后在 main.py 中统一注册
router = APIRouter()

_start_time = time.time()


@router.get("/api/health")
async def health():
    """
    健康检查端点 —— 返回 Python 核心及依赖状态

    ★ 为什么是 async def：
       FastAPI 中 async 函数在异步事件循环中运行，不阻塞其他请求。
       Day1 虽然只是返回常量，Day5 加入 LLM 连接检测后也不需要改函数签名。

    响应格式：
      {
        "status": "ok" | "degraded",
        "llm_connected": true,         # LLM 使用 api_config 每次传入，无法在此检测
        "memory_connected": true,      # 检测 Mem0 是否已初始化
        "uptime_seconds": 0
      }
    """
    # 检测记忆系统
    memory_ok = memory_service._memory is not None

    # LLM 使用 api_config 每次传入，无法在此做实际调用
    llm_ok = None

    status = "degraded" if memory_service.last_error else "ok"
    uptime = int(time.time() - _start_time)

    return {
        "status": status,
        "llm_connected": llm_ok,
        "memory_connected": memory_ok,
        "llm_status": "unverified",
        "memory_status": "degraded" if memory_service.last_error else "ready" if memory_ok else "not_initialized",
        "uptime_seconds": uptime
    }

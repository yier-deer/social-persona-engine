import logging
import os
from logging.handlers import RotatingFileHandler

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from api.health_routes import router as health_router
from api.message_routes import router as message_router
from api.event_routes import router as event_router
from api.matchmaker_routes import router as matchmaker_router
from api.image_routes import router as image_router


def setup_logging():
    log_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "logs")
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(log_dir, "python.log")

    root = logging.getLogger()
    root.setLevel(logging.INFO)

    fmt = logging.Formatter(
        "%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )

    fh = RotatingFileHandler(log_file, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
    fh.setLevel(logging.INFO)
    fh.setFormatter(fmt)
    root.addHandler(fh)

    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    ch.setFormatter(fmt)
    root.addHandler(ch)

    logging.getLogger("uvicorn").setLevel(logging.INFO)
    logging.getLogger("uvicorn.access").setLevel(logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("openai").setLevel(logging.WARNING)
    logging.getLogger("anthropic").setLevel(logging.WARNING)

    logging.info("日志系统初始化完成: %s", log_file)


setup_logging()

docs_enabled = os.environ.get("FASTAPI_DOCS", "false").lower() == "true"

app = FastAPI(
    title="Social Persona Engine",
    version="1.0.0",
    docs_url="/docs" if docs_enabled else None,
    redoc_url=None,
    openapi_url="/openapi.json" if docs_enabled else None,
)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request, error):
    # Pydantic's default error includes the complete input, including API keys.
    details = [{key: item[key] for key in ("loc", "msg", "type")} for item in error.errors()]
    return JSONResponse(status_code=422, content={"detail": details})

app.include_router(health_router)
app.include_router(message_router)
app.include_router(event_router)
app.include_router(matchmaker_router)
app.include_router(image_router)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8000)

"""
YIBCCC LangChain Agent 主入口

启动 FastAPI 服务器
"""

import logging
import uvicorn
from src.api.main import app
from src.config import settings


def main():
    """启动服务器"""
    # 配置应用日志
    logging.basicConfig(
        level=logging.INFO,
        format='%(levelname)s - %(name)s - %(message)s'
    )

    uvicorn.run(
        "src.api.main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.debug,
        log_level="info"
    )


if __name__ == "__main__":
    main()

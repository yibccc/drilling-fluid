"""
内置工具集合

提供 Agent 可用的基础工具函数
"""

from datetime import datetime
from typing import Annotated

from langchain.tools import tool


@tool
def get_current_time(
    timezone: Annotated[str, "时区，例如 'Asia/Shanghai' 或 'UTC'"] = "Asia/Shanghai"
) -> str:
    """获取当前时间。

    用于查询当前的日期和时间信息。

    Args:
        timezone: 时区名称，默认为 Asia/Shanghai

    Returns:
        当前时间的字符串表示
    """
    try:
        from zoneinfo import ZoneInfo

        tz = ZoneInfo(timezone)
        now = datetime.now(tz)
        return now.strftime("%Y-%m-%d %H:%M:%S %Z (%z)")
    except Exception:
        # 如果时区无效，返回 UTC 时间
        now = datetime.utcnow()
        return now.strftime("%Y-%m-%d %H:%M:%S UTC") + " (时区参数无效，使用 UTC)"


# 导出所有工具
__all__ = ["get_current_time"]

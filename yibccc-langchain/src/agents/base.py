"""
基础 Agent 创建模块

提供通用的 LLM 创建函数
"""

from langchain_openai import ChatOpenAI
from src.config import get_llm_config


def create_base_llm(temperature: float = 0.7, **kwargs):
    """
    创建基础 LLM 实例

    Args:
        temperature: 温度参数，控制随机性
        **kwargs: 其他参数

    Returns:
        ChatOpenAI 实例
    """
    config = get_llm_config()
    config.update(kwargs)
    config["temperature"] = temperature

    return ChatOpenAI(**config)

"""
配置管理模块

加载和管理环境变量配置
"""

import os
from dotenv import load_dotenv
from pydantic import Field, ConfigDict
from pydantic_settings import BaseSettings

# 加载环境变量
load_dotenv()


class Settings(BaseSettings):
    """应用配置"""

    # DeepSeek API 配置
    deepseek_api_key: str = Field(default="", alias="DEEPSEEK_API_KEY")
    deepseek_base_url: str = Field(
        default="https://api.deepseek.com/v1",
        alias="DEEPSEEK_BASE_URL"
    )
    deepseek_model: str = Field(default="deepseek-chat", alias="DEEPSEEK_MODEL")

    # Redis 配置
    redis_url: str = Field(default="redis://localhost:6379", alias="REDIS_URL")

    # PostgreSQL 配置
    pg_dsn: str = Field(default="", alias="PG_DSN")
    pg_host: str = Field(default="localhost", alias="PG_HOST")
    pg_port: int = Field(default=5432, alias="PG_PORT")
    pg_database: str = Field(default="yibccc_chat", alias="PG_DATABASE")
    pg_user: str = Field(default="", alias="PG_USER")
    pg_password: str = Field(default="", alias="PG_PASSWORD")

    # API 认证配置 - 使用字符串存储，逗号分隔
    api_keys_str: str = Field(default="", alias="API_KEYS")

    # 应用配置
    app_name: str = "yibccc-langchain"
    app_version: str = "0.1.0"
    debug: bool = Field(default=False, alias="DEBUG")

    # Redis Stream 同步配置
    redis_stream_sync_enabled: bool = Field(default=True, alias="REDIS_STREAM_SYNC_ENABLED")
    redis_stream_workers: int = Field(default=2, alias="REDIS_STREAM_WORKERS")
    redis_stream_name: str = Field(default="sync:checkpoints", alias="REDIS_STREAM_NAME")
    redis_consumer_group: str = Field(default="sync_workers", alias="REDIS_CONSUMER_GROUP")

    # 诊断系统配置 - Embedding 模型 (通义千问 DashScope)
    embedding_model: str = Field(default="text-embedding-v3", alias="EMBEDDING_MODEL")
    dashscope_api_key: str = Field(default="", alias="DASHSCOPE_API_KEY")

    # LangChain PGVector 配置
    LANGCHAIN_CONNECTION_STRING: str = Field(default="", alias="LANGCHAIN_CONNECTION_STRING")

    # SpringBoot 回调配置
    springboot_callback_timeout: int = Field(default=30, alias="SPRINGBOOT_CALLBACK_TIMEOUT")
    springboot_callback_retry_max: int = Field(default=3, alias="SPRINGBOOT_CALLBACK_RETRY_MAX")

    model_config = ConfigDict(env_file=".env", case_sensitive=False, extra="ignore")

    @property
    def api_keys(self) -> list[str]:
        """获取 API Keys 列表"""
        if self.api_keys_str.strip():
            return [k.strip() for k in self.api_keys_str.split(",")]
        return []

    def get_pg_dsn(self) -> str:
        """获取 PostgreSQL DSN"""
        if self.pg_dsn:
            return self.pg_dsn
        return f"postgresql://{self.pg_user}:{self.pg_password}@{self.pg_host}:{self.pg_port}/{self.pg_database}"

    def get_langchain_connection_string(self) -> str:
        """获取 LangChain 连接字符串 (使用 psycopg 连接器)"""
        if self.LANGCHAIN_CONNECTION_STRING:
            return self.LANGCHAIN_CONNECTION_STRING
        # 默认使用现有数据库连接，格式为 postgresql+psycopg://
        return f"postgresql+psycopg://{self.pg_user}:{self.pg_password}@{self.pg_host}:{self.pg_port}/{self.pg_database}"

    def validate_api_key(self, api_key: str) -> bool:
        """验证 API Key"""
        return api_key in self.api_keys


# 全局配置实例
settings = Settings()


def get_llm_config() -> dict:
    """获取 LLM 配置字典"""

    return {
        "model": settings.deepseek_model,
        "api_key": settings.deepseek_api_key,
        "base_url": settings.deepseek_base_url,
    }

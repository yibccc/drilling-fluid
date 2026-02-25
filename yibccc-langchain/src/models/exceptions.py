"""
应用异常定义

定义应用使用的异常层次结构
"""


class AppException(Exception):
    """应用基础异常"""

    def __init__(self, message: str, code: str = "APP_ERROR"):
        self.message = message
        self.code = code
        super().__init__(self.message)


class AuthenticationError(AppException):
    """API Key 认证失败"""

    def __init__(self, message: str = "Invalid API Key"):
        super().__init__(message, "AUTH_FAILED")


class SessionNotFoundError(AppException):
    """会话不存在"""

    def __init__(self, session_id: str):
        super().__init__(f"Session {session_id} not found", "SESSION_NOT_FOUND")


class LLMError(AppException):
    """LLM 调用失败"""

    def __init__(self, message: str):
        super().__init__(message, "LLM_ERROR")


class ToolExecutionError(AppException):
    """工具执行失败"""

    def __init__(self, tool_name: str, reason: str):
        super().__init__(f"Tool {tool_name} failed: {reason}", "TOOL_ERROR")


class RedisCheckpointError(AppException):
    """Redis Checkpoint 连接失败"""

    def __init__(self, reason: str):
        super().__init__(f"Redis Checkpoint 初始化失败: {reason}，无法启动服务", "REDIS_CHECKPOINT_ERROR")


class DiagnosisError(AppException):
    """诊断分析失败"""

    def __init__(self, message: str):
        super().__init__(message, "DIAGNOSIS_ERROR")


class RAGError(AppException):
    """RAG 检索失败"""

    def __init__(self, message: str):
        super().__init__(message, "RAG_ERROR")


class CallbackError(AppException):
    """回调失败"""

    def __init__(self, message: str):
        super().__init__(message, "CALLBACK_ERROR")


class KnowledgeBaseError(AppException):
    """知识库操作失败"""

    def __init__(self, message: str):
        super().__init__(message, "KNOWLEDGE_BASE_ERROR")

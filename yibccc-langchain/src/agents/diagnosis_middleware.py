# src/agents/diagnosis_middleware.py

import asyncio
import logging
from typing import Any, List
from langchain_core.messages import SystemMessage, HumanMessage

logger = logging.getLogger(__name__)


class RetrievalMiddleware:
    """检索中间件 - 自动注入知识库上下文"""

    def __init__(self, vector_store_service):
        self.vector_store = vector_store_service

    async def before_model(self, state: dict) -> dict | None:
        """在模型调用前执行：检索并注入上下文"""
        try:
            # 1. 提取查询
            messages = state.get("messages", [])
            if not messages:
                return None

            last_message = messages[-1]
            user_query = getattr(last_message, 'content', str(last_message))

            if not user_query:
                return self._handle_error("查询为空", state)

            # 2. 执行检索
            retrieved_docs = await self._safe_search(
                query=user_query,
                filter=self._extract_filter_from_state(state),
                state=state
            )

            # 3. 检查检索结果
            if not retrieved_docs:
                return self._handle_empty_search(state)

            # 4. 格式化并注入上下文
            context = self._format_context(retrieved_docs)
            system_message = f"""你是一位钻井液性能诊断专家。

请基于以下知识库内容回答用户的问题：

【知识库内容】
{context}

请根据上述专业知识，提供准确的诊断和处置建议。"""

            return {
                "messages": [
                    SystemMessage(content=system_message),
                    *messages
                ]
            }

        except Exception as e:
            logger.error(f"中间件错误: {e}")
            return self._handle_error(f"检索失败: {str(e)}", state)

    def _extract_filter_from_state(self, state: dict) -> dict | None:
        """从 state 中提取检索过滤条件"""
        metadata = state.get("metadata", {})
        category = metadata.get("category")
        return {"category": category} if category else None

    def _format_context(self, docs: List) -> str:
        """格式化检索结果"""
        return "\n\n".join([
            f"【{doc.metadata.get('title', '资料')}】\n{doc.page_content}"
            for doc in docs
        ])

    async def _safe_search(self, query: str, filter: dict, state: dict) -> List:
        """带重试的安全检索"""
        max_retries = 2
        for attempt in range(max_retries):
            try:
                return await self.vector_store.similarity_search(
                    query=query, k=5, filter=filter
                )
            except Exception as e:
                logger.warning(f"检索失败，重试 {attempt + 1}/{max_retries}: {e}")
                if attempt < max_retries - 1:
                    await asyncio.sleep(1 * (attempt + 1))

        logger.error("检索重试失败，返回空结果")
        return []

    def _handle_empty_search(self, state: dict) -> dict:
        """处理检索结果为空"""
        system_message = """你是一位钻井液性能诊断专家。

由于未能检索到相关知识库内容，请基于你的专业知识进行分析和诊断。

如果问题超出专业范围，请明确告知用户。"""

        logger.warning("检索结果为空，使用降级模式")

        return {
            "messages": [
                SystemMessage(content=system_message),
                *state["messages"]
            ],
            "_retrieval_empty": True
        }

    def _handle_error(self, error_msg: str, state: dict) -> dict:
        """处理错误"""
        system_message = """你是一位钻井液性能诊断专家。

当前检索系统遇到问题，请基于你的专业知识进行分析。
如需专业知识库支持，请建议用户稍后重试。"""

        return {
            "messages": [
                SystemMessage(content=system_message),
                *state["messages"]
            ],
            "_retrieval_error": True
        }

# src/agents/diagnosis_agent.py
"""
钻井液诊断 Agent

基于 LangChain create_agent 构建诊断分析 Agent
"""

import logging
from typing import AsyncIterator, Dict, Any, Optional, List, Literal

from pydantic import BaseModel, Field

from langchain_openai import ChatOpenAI
from langchain.messages import HumanMessage
from langchain.agents import create_agent
from langchain.agents.structured_output import ToolStrategy

from src.models.diagnosis_schemas import (
    DiagnosisRequest,
    DiagnosisEvent,
    DiagnosisResult,
)
from src.models.exceptions import DiagnosisError, LLMError
from src.config import settings, get_llm_config
from src.agents.diagnosis_middleware import RetrievalMiddleware

logger = logging.getLogger(__name__)


# ========== LLM 结构化输出 Schema ==========


class LLMTrendAnalysis(BaseModel):
    """LLM 输出的趋势分析"""
    field: str = Field(..., description="参数名称，如 density（密度）、plastic_viscosity（塑性黏度）等")
    trend: str = Field(..., description="趋势方向: rising（上升）、falling（下降）、stable（稳定）、fluctuating（波动）")
    from_value: float = Field(..., description="起始值")
    to_value: float = Field(..., description="结束值")
    rate: float = Field(..., description="变化率（单位/小时）")
    duration: str = Field(..., description="持续时间，如 2h、30min")
    acceleration: Optional[Literal["increasing", "decreasing", "constant"]] = Field(None, description="加速度: increasing（加速）、decreasing（减速）、constant（匀速）")


class LLMTreatmentMeasure(BaseModel):
    """LLM 输出的处置措施"""
    step: int = Field(..., description="步骤序号")
    action: str = Field(..., description="具体的行动描述")
    duration: Optional[str] = Field(None, description="执行持续时间")
    amount: Optional[str] = Field(None, description="用量或添加量")
    priority: str = Field("MEDIUM", description="优先级: LOW、MEDIUM、HIGH")
    notes: Optional[str] = Field(None, description="备注说明")


class LLMPrescription(BaseModel):
    """LLM 输出的配药方案"""
    dilution_water: Optional[str] = Field(None, description="稀释水量，如 系统总体积的8-10%")
    viscosity_reducer: Optional[str] = Field(None, description="降黏剂添加量和方式")
    mixing_time: Optional[str] = Field(None, description="搅拌时间要求")
    other_agents: Optional[Dict[str, str]] = Field(None, description="其他药剂的添加说明")


class LLMDiagnosisOutput(BaseModel):
    """LLM 结构化输出的完整诊断结果"""
    summary: str = Field(..., description="诊断总结，简明扼要地描述主要问题")
    cause: str = Field(..., description="问题原因分析")
    risk_level: str = Field(..., description="风险等级: LOW、MEDIUM、HIGH、CRITICAL")
    trend_outlook: Optional[str] = Field(None, description="趋势展望和预测")
    trend_analysis: List[LLMTrendAnalysis] = Field(default_factory=list, description="参数趋势分析列表")
    measures: List[LLMTreatmentMeasure] = Field(default_factory=list, description="处置措施列表")
    prescription: LLMPrescription = Field(default_factory=LLMPrescription, description="配药方案")


class DiagnosisAgent:
    """钻井液诊断 Agent"""

    def __init__(self, checkpointer=None, vector_store_service=None):
        self.checkpointer = checkpointer
        self.vector_store_service = vector_store_service
        self.model = ChatOpenAI(
            **get_llm_config(),
            streaming=True,
            temperature=0.3  # 诊断分析需要更稳定的输出
        )
        # 使用 ToolStrategy 处理结构化输出
        self.response_format = ToolStrategy(LLMDiagnosisOutput)
        self.agent = None
        self.retrieval_middleware = None  # 检索中间件

    async def initialize(self):
        """初始化 Agent"""
        try:
            await self.checkpointer.setup() if self.checkpointer else None
            self._build_agent()
            logger.info("DiagnosisAgent initialized")
        except Exception as e:
            logger.error(f"Failed to initialize DiagnosisAgent: {e}")
            raise DiagnosisError(f"Agent 初始化失败: {str(e)}")

    async def cleanup(self):
        """清理资源"""
        if self.checkpointer:
            await self.checkpointer.close()
            logger.info("DiagnosisAgent cleaned up")

    def _build_agent(self):
        """构建诊断 Agent - Two-step Chain 方式"""
        # 定义诊断专用工具（移除 search_knowledge，由中间件处理）
        from src.tools.diagnosis_tools import (
            analyze_trend,
            format_prescription
        )

        # 创建检索中间件
        if self.vector_store_service:
            self.retrieval_middleware = RetrievalMiddleware(self.vector_store_service)

        tools = [
            analyze_trend,
            format_prescription
        ]

        # 简化系统提示词（上下文由中间件注入）
        system_prompt = """你是一位钻井液性能诊断专家。

你的职责是：
1. 分析钻井液采样数据，识别异常趋势
2. 基于知识库内容诊断问题原因
3. 提供具体的处置措施和配药方案
4. 评估风险等级并提供趋势预测

分析完成后，返回结构化的诊断结果。"""

        self.agent = create_agent(
            model=self.model,
            tools=tools,
            system_prompt=system_prompt,
            checkpointer=self.checkpointer,
            response_format=self.response_format,
            middleware=[self.retrieval_middleware] if self.retrieval_middleware else [],
        )

    async def analyze(
        self,
        request: DiagnosisRequest
    ) -> AsyncIterator[DiagnosisEvent]:
        """执行诊断分析（流式输出）"""
        task_id = request.task_id

        try:
            # 发送开始事件
            yield DiagnosisEvent.start(
                task_id=task_id,
                well_id=request.well_id,
                samples_count=len(request.samples)
            )

            # 构建分析提示
            prompt = self._build_analysis_prompt(request)

            # 发送思考事件
            yield DiagnosisEvent.thinking(
                task_id=task_id,
                content=f"正在分析 {len(request.samples)} 条采样数据...",
                step="data_analysis"
            )

            # 调用 Agent 获取分析结果（流式）
            config = {
                "configurable": {
                    "thread_id": task_id
                }
            }

            # 发送思考开始事件
            yield DiagnosisEvent.thinking(
                task_id=task_id,
                content="正在分析数据并生成诊断结果...",
                step="analyzing"
            )

            # 使用 astream 获取流式更新，并从 values 模式获取最终状态
            final_state = None

            async for stream_mode, data in self.agent.astream(
                {"messages": [HumanMessage(content=prompt)]},
                config=config,
                stream_mode=["messages", "updates", "values"],
            ):
                if stream_mode == "messages":
                    # messages 模式: (token_chunk, metadata)
                    token, metadata = data

                    # 处理 AI 消息块（LLM 的思考文本）
                    if hasattr(token, "text") and token.text:
                        yield DiagnosisEvent.thinking(
                            task_id=task_id,
                            content=token.text[:100] if len(token.text) > 100 else token.text,
                            step="reasoning"
                        )

                    # 处理工具调用块
                    elif hasattr(token, "tool_call_chunks") and token.tool_call_chunks:
                        for chunk in token.tool_call_chunks:
                            if chunk.get("name"):
                                yield DiagnosisEvent.thinking(
                                    task_id=task_id,
                                    content=f"调用工具: {chunk.get('name')}",
                                    step="tool_call"
                                )

                elif stream_mode == "updates":
                    # updates 模式: {node_name: update_dict}
                    for node_name, update in data.items():
                        if "messages" in update:
                            for msg in update["messages"]:
                                # 处理完整的 AI 消息（包含工具调用）
                                if hasattr(msg, "tool_calls") and msg.tool_calls:
                                    for tool_call in msg.tool_calls:
                                        tool_name = tool_call.get("name", "unknown")
                                        tool_args = tool_call.get("args", {})
                                        if tool_name != "LLMDiagnosisOutput":  # 跳过结构化输出工具
                                            yield DiagnosisEvent.thinking(
                                                task_id=task_id,
                                                content=f"调用工具: {tool_name}({str(tool_args)[:100]})",
                                                step="tool_call"
                                            )

                                # 处理工具返回消息
                                elif hasattr(msg, "type") and msg.type == "tool":
                                    tool_name = hasattr(msg, "name") and msg.name or "unknown"
                                    content = str(msg.content) if hasattr(msg, "content") else ""
                                    if tool_name != "LLMDiagnosisOutput":
                                        yield DiagnosisEvent.thinking(
                                            task_id=task_id,
                                            content=f"工具 {tool_name} 返回: {content[:200]}",
                                            step="tool_result"
                                        )

                elif stream_mode == "values":
                    final_state = data

            # 从流式输出中获取最终状态，避免重复调用模型
            if final_state is None:
                raise LLMError("流式执行结束后未能获取最终状态")

            # 从状态中获取结构化输出
            structured_output: LLMDiagnosisOutput = final_state.get("structured_response")

            if not structured_output:
                raise LLMError("未能获取结构化诊断结果")

            # 转换为 DiagnosisResult
            result = self._convert_to_diagnosis_result(structured_output)
            yield DiagnosisEvent(
                type="result",
                task_id=task_id,
                result=result
            )

            # 完成
            yield DiagnosisEvent(
                type="done",
                task_id=task_id,
                status="SUCCESS"
            )

        except Exception as e:
            logger.error(f"Analysis failed: {e}")
            yield DiagnosisEvent.error(
                task_id=task_id,
                error_code="ANALYSIS_FAILED",
                message=str(e)
            )

    def _build_analysis_prompt(self, request: DiagnosisRequest) -> str:
        """构建分析提示词"""
        # 格式化采样数据
        samples_text = "\n".join([
            f"- {s.sample_time.strftime('%H:%M')}: 密度={s.density}, PV={s.plastic_viscosity}, YP={s.yield_point}"
            for s in request.samples[:5]  # 只显示前5条
        ])

        context = request.context
        current_depth = f"{context.current_depth}m" if context and context.current_depth is not None else "未提供"
        formation_type = context.formation_type if context and context.formation_type else "未提供"
        drilling_phase = context.drilling_phase if context and context.drilling_phase else "未提供"

        prompt = f"""请分析以下钻井液数据：

**井号**: {request.well_id}
**预警类型**: {request.alert_type}
**当前值**: {request.alert_threshold.current_value}
**阈值**: {request.alert_threshold.threshold}

**采样数据**（最近{len(request.samples)}条）:
{samples_text}

**上下文**:
- 当前深度: {current_depth}
- 岩性: {formation_type}
- 钻井阶段: {drilling_phase}

请执行以下分析：
1. 趋势分析：使用 analyze_trend 工具分析主要参数趋势
2. 知识增强：结合系统自动注入的知识库上下文分析原因和处置措施
3. 生成配药方案：使用 format_prescription 工具生成具体方案

最后给出诊断结论和风险等级评估。"""

        return prompt

    def _convert_to_diagnosis_result(self, output: LLMDiagnosisOutput) -> DiagnosisResult:
        """将 LLM 结构化输出转换为 DiagnosisResult"""
        from src.models.diagnosis_schemas import (
            DiagnosisConclusion,
            TreatmentMeasure,
            Prescription,
            TrendAnalysis
        )

        # 转换趋势分析
        trend_analysis = [
            TrendAnalysis(
                field=t.field,
                trend=t.trend if t.trend in ["rising", "falling", "stable", "fluctuating"] else "stable",
                from_value=t.from_value,
                to_value=t.to_value,
                rate=t.rate,
                duration=t.duration,
                acceleration=t.acceleration
            )
            for t in output.trend_analysis
        ]

        # 转换处置措施
        measures = [
            TreatmentMeasure(
                step=m.step,
                action=m.action,
                duration=m.duration,
                amount=m.amount,
                priority=m.priority if m.priority in ["LOW", "MEDIUM", "HIGH"] else "MEDIUM",
                notes=m.notes
            )
            for m in output.measures
        ]

        # 转换配药方案
        prescription = Prescription(
            dilution_water=output.prescription.dilution_water,
            viscosity_reducer=output.prescription.viscosity_reducer,
            mixing_time=output.prescription.mixing_time,
            other_agents=output.prescription.other_agents
        )

        # 转换诊断结论
        diagnosis = DiagnosisConclusion(
            summary=output.summary,
            cause=output.cause,
            risk_level=output.risk_level if output.risk_level in ["LOW", "MEDIUM", "HIGH", "CRITICAL"] else "MEDIUM",
            trend_outlook=output.trend_outlook
        )

        return DiagnosisResult(
            diagnosis=diagnosis,
            trend_analysis=trend_analysis if trend_analysis else None,
            measures=measures if measures else [],
            prescription=prescription
        )

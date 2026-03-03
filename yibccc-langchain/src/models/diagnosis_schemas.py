# src/models/diagnosis_schemas.py
"""
钻井液诊断系统数据模型

定义诊断相关的请求/响应模型
"""

from pydantic import BaseModel, Field, field_validator
from typing import Literal, Optional, List, Dict, Any
from datetime import datetime
from uuid import uuid4


# ========== 基础模型 ==========

class AlertThreshold(BaseModel):
    """预警阈值配置"""
    field: str = Field(..., description="阈值字段名")
    condition: Literal["greater_than", "less_than", "equal", "between"] = Field(
        ..., description="阈值条件"
    )
    threshold: float = Field(..., description="阈值")
    current_value: float = Field(..., description="当前值")


class DrillingFluidSample(BaseModel):
    """钻井液采样数据"""
    id: str
    well_id: str
    sample_time: datetime
    formation: str
    outlet_temp: float
    density: float
    gel_10s: float
    gel_10m: float
    rpm_3: float
    rpm_6: float
    rpm_100: float
    rpm_200: float
    rpm_300: float
    rpm_600: float
    plastic_viscosity: float
    yield_point: float
    flow_behavior_index: float
    consistency_coefficient: float
    apparent_viscosity: float
    yield_plastic_ratio: float


class DiagnosisContext(BaseModel):
    """诊断上下文信息"""
    current_depth: Optional[float] = None
    formation_type: Optional[str] = None
    drilling_phase: Optional[str] = None
    recent_operations: Optional[List[str]] = None


# ========== 请求模型 ==========

class DiagnosisRequest(BaseModel):
    """诊断分析请求"""
    task_id: str = Field(default_factory=lambda: f"TASK-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid4().hex[:6].upper()}")
    well_id: str = Field(..., description="井号")
    alert_type: str = Field(..., description="预警类型")
    alert_triggered_at: datetime = Field(..., description="预警触发时间")
    alert_threshold: AlertThreshold = Field(..., description="阈值配置")
    samples: List[DrillingFluidSample] = Field(..., min_length=1, max_length=20, description="采样数据")
    context: Optional[DiagnosisContext] = Field(default_factory=DiagnosisContext, description="上下文信息")
    callback_url: Optional[str] = Field(None, description="回调URL")
    stream: bool = Field(True, description="是否流式返回")

    @field_validator("samples")
    @classmethod
    def validate_samples(cls, v):
        """验证采样数据"""
        if not v:
            raise ValueError("至少需要一条采样数据")
        # 按时间排序（最新的在前）
        return sorted(v, key=lambda x: x.sample_time, reverse=True)


# ========== 响应模型 ==========

class TrendAnalysis(BaseModel):
    """趋势分析结果"""
    field: str
    trend: Literal["rising", "falling", "stable", "fluctuating"]
    from_value: float
    to_value: float
    rate: float
    duration: str
    acceleration: Optional[Literal["increasing", "decreasing", "constant"]] = None


class DiagnosisConclusion(BaseModel):
    """诊断结论"""
    summary: str
    cause: str
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    trend_outlook: Optional[str] = None


class TreatmentMeasure(BaseModel):
    """处置措施"""
    step: int
    action: str
    duration: Optional[str] = None
    amount: Optional[str] = None
    priority: Literal["LOW", "MEDIUM", "HIGH"] = "MEDIUM"
    notes: Optional[str] = None


class Prescription(BaseModel):
    """配药方案"""
    dilution_water: Optional[str] = None
    viscosity_reducer: Optional[str] = None
    mixing_time: Optional[str] = None
    other_agents: Optional[Dict[str, str]] = None


class DiagnosisResult(BaseModel):
    """完整诊断结果"""
    diagnosis: DiagnosisConclusion
    trend_analysis: Optional[List[TrendAnalysis]] = None
    measures: List[TreatmentMeasure]
    prescription: Prescription
    references: Optional[List[str]] = None


# ========== SSE 事件模型 ==========

class DiagnosisEvent(BaseModel):
    """诊断 SSE 事件"""
    type: Literal[
        "start", "thinking", "trend_analysis", "retrieval",
        "diagnosis", "prescription", "result", "done", "error"
    ] = Field(..., description="事件类型")

    # 通用字段
    task_id: Optional[str] = None
    well_id: Optional[str] = None

    # 特定事件字段
    content: Optional[str] = None
    step: Optional[str] = None

    # trend_analysis 事件字段
    field: Optional[str] = None
    analysis: Optional[Dict[str, Any]] = None

    # retrieval 事件字段
    docs_found: Optional[int] = None
    sources: Optional[List[str]] = None

    # diagnosis 事件字段
    summary: Optional[str] = None
    cause: Optional[str] = None
    risk_level: Optional[str] = None
    trend_outlook: Optional[str] = None

    # prescription 事件字段
    action: Optional[str] = None
    prescription: Optional[Dict[str, Any]] = None

    # result 事件字段
    result: Optional[DiagnosisResult] = None

    # done 事件字段
    completed_at: Optional[datetime] = None
    status: Optional[str] = None
    tokens_used: Optional[int] = None

    # error 事件字段
    error_code: Optional[str] = None

    def to_sse(self) -> str:
        """转换为 SSE 格式"""
        return f"data: {self.model_dump_json(exclude_none=True)}\n\n"

    @classmethod
    def start(cls, task_id: str, well_id: str, samples_count: int) -> "DiagnosisEvent":
        """创建开始事件"""
        return cls(
            type="start",
            task_id=task_id,
            well_id=well_id,
            content=f"开始分析井号 {well_id} 的 {samples_count} 条采样数据"
        )

    @classmethod
    def thinking(cls, task_id: str, content: str, step: str) -> "DiagnosisEvent":
        """创建思考事件"""
        return cls(type="thinking", task_id=task_id, content=content, step=step)

    @classmethod
    def error(cls, task_id: str, error_code: str, message: str) -> "DiagnosisEvent":
        """创建错误事件"""
        return cls(type="error", task_id=task_id, error_code=error_code, content=message)


# ========== 回调模型 ==========

class CallbackRequest(BaseModel):
    """回调请求（Agent 发送给 SpringBoot）"""
    task_id: str
    well_id: str
    status: Literal["SUCCESS", "FAILED", "PARTIAL"]
    completed_at: datetime
    result: Optional[DiagnosisResult] = None
    error: Optional[str] = None


# ========== 知识库模型 ==========

class KnowledgeDocumentCreate(BaseModel):
    """创建知识文档请求"""
    doc_id: str
    title: str
    category: str
    subcategory: Optional[str] = None
    content: str
    metadata: Optional[Dict[str, Any]] = None


class KnowledgeDocumentResponse(BaseModel):
    """知识文档响应"""
    id: str
    doc_id: str
    title: str
    category: str
    subcategory: Optional[str] = None
    content: str
    metadata: Optional[Dict[str, Any]] = None
    chunk_count: int
    created_at: datetime


class KnowledgeSearchRequest(BaseModel):
    """知识检索请求"""
    query: str
    category: Optional[str] = None
    top_k: int = Field(default=5, ge=1, le=20)

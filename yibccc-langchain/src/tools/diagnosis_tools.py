# src/tools/diagnosis_tools.py
"""
钻井液诊断专用工具集

提供给 Agent 使用的诊断分析工具
"""

from typing import Annotated, List, Dict, Any
from datetime import datetime, timedelta

from langchain.tools import tool


@tool
def analyze_trend(
    samples: Annotated[List[Dict[str, Any]], "采样数据列表，包含 field 和 value"],
    field: Annotated[str, "要分析的字段名，如 'density', 'plastic_viscosity'"] = "density"
) -> str:
    """分析钻井液参数趋势。

    计算指定字段的变化趋势、变化率和加速度。

    Args:
        samples: 采样数据列表，每个元素包含 sample_time 和指定字段的值
        field: 要分析的字段名

    Returns:
        趋势分析结果的字符串描述
    """
    if not samples or len(samples) < 2:
        return "数据不足，无法分析趋势"

    # 按时间排序（最早的在前）
    sorted_samples = sorted(samples, key=lambda x: x.get("sample_time", ""))

    # 提取字段值
    values = []
    for s in sorted_samples:
        if field in s:
            val = s[field]
            if isinstance(val, (int, float)):
                values.append(val)

    if len(values) < 2:
        return f"字段 {field} 数据不足"

    # 计算趋势
    first_val = values[0]
    last_val = values[-1]
    change = last_val - first_val
    change_rate = abs(change / first_val) if first_val != 0 else 0

    # 判断趋势方向
    if abs(change) < 0.01:
        trend = "stable"
        trend_cn = "稳定"
    elif change > 0:
        trend = "rising"
        trend_cn = "上升"
    else:
        trend = "falling"
        trend_cn = "下降"

    # 计算时间跨度
    if "sample_time" in sorted_samples[0] and "sample_time" in sorted_samples[-1]:
        time_span = sorted_samples[-1]["sample_time"] - sorted_samples[0]["sample_time"]
        duration_str = f"{time_span.total_seconds() / 60:.0f}分钟"
    else:
        duration_str = "未知"

    result = f"""趋势分析结果（{field}）:
- 趋势: {trend_cn}
- 起始值: {first_val:.3f}
- 结束值: {last_val:.3f}
- 变化量: {change:+.3f}
- 变化率: {change_rate*100:.1f}%
- 时间跨度: {duration_str}"""

    return result


@tool
def format_prescription(
    measures: Annotated[str, "处置措施描述"],
    density: Annotated[float, "当前钻井液密度"] = 1.2,
    plastic_viscosity: Annotated[float, "当前塑性黏度"] = 20
) -> str:
    """生成配药方案。

    根据处置措施和当前参数生成具体的配药方案。

    Args:
        measures: 处置措施描述
        density: 当前密度
        plastic_viscosity: 当前塑性黏度

    Returns:
        配药方案详情
    """
    # 简化规则引擎
    prescription = {
        "稀释水": "0%",
        "降黏剂": "0%",
        "加重剂": "0%",
        "搅拌时间": "30分钟"
    }

    if "密度高" in measures or "密度偏高" in measures:
        prescription["稀释水"] = "8%"
        prescription["搅拌时间"] = "45分钟"

    if "黏度高" in measures or "塑性黏度高" in measures:
        prescription["降黏剂"] = "0.3%"
        prescription["稀释水"] = "5%"

    if "密度低" in measures:
        prescription["加重剂"] = "重晶石 2%"

    output = "配药方案:\n"
    for k, v in prescription.items():
        output += f"- {k}: {v}\n"

    return output


# 导出所有工具（search_knowledge 已由中间件替代）
__all__ = ["analyze_trend", "format_prescription"]

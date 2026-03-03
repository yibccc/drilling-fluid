"""
诊断工具测试

测试钻井液诊断专用工具集
"""

import pytest
from datetime import datetime, timedelta

from src.tools.diagnosis_tools import analyze_trend, format_prescription


def test_available_tools():
    """验证可用的工具"""
    tools = [analyze_trend, format_prescription]
    assert len(tools) == 2
    assert all(hasattr(tool, 'name') for tool in tools)

    # 验证 search_knowledge 已移除
    from src.tools.diagnosis_tools import __all__
    assert 'search_knowledge' not in __all__


class TestAnalyzeTrend:
    """analyze_trend 工具测试"""

    def test_analyze_rising_trend(self):
        """测试分析上升趋势"""
        now = datetime.now()
        samples = [
            {
                "sample_time": now - timedelta(hours=2),
                "density": 1.200
            },
            {
                "sample_time": now - timedelta(hours=1),
                "density": 1.250
            },
            {
                "sample_time": now,
                "density": 1.300
            }
        ]

        # LangChain @tool 返回 StructuredTool，使用 invoke 调用
        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "上升" in result
        assert "1.200" in result
        assert "1.300" in result
        assert "+0.100" in result

    def test_analyze_falling_trend(self):
        """测试分析下降趋势"""
        now = datetime.now()
        samples = [
            {"sample_time": now - timedelta(hours=1), "density": 1.300},
            {"sample_time": now, "density": 1.250}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "下降" in result
        assert "1.300" in result
        assert "1.250" in result

    def test_analyze_stable_trend(self):
        """测试分析稳定趋势"""
        now = datetime.now()
        samples = [
            {"sample_time": now - timedelta(hours=1), "density": 1.200},
            {"sample_time": now, "density": 1.201}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "稳定" in result

    def test_analyze_insufficient_data(self):
        """测试数据不足"""
        samples = [
            {"sample_time": datetime.now(), "density": 1.200}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "数据不足" in result

    def test_analyze_empty_samples(self):
        """测试空样本"""
        result = analyze_trend.invoke({"samples": [], "field": "density"})

        assert "数据不足" in result

    def test_analyze_field_not_found(self):
        """测试字段不存在"""
        now = datetime.now()
        samples = [
            {"sample_time": now, "viscosity": 20},
            {"sample_time": now + timedelta(hours=1), "viscosity": 25}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "数据不足" in result

    def test_analyze_time_span_calculation(self):
        """测试时间跨度计算"""
        now = datetime.now()
        samples = [
            {"sample_time": now - timedelta(minutes=120), "density": 1.200},
            {"sample_time": now, "density": 1.300}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "120分钟" in result

    def test_analyze_change_rate(self):
        """测试变化率计算"""
        now = datetime.now()
        samples = [
            {"sample_time": now - timedelta(hours=1), "density": 1.000},
            {"sample_time": now, "density": 1.100}  # 10% 变化
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "density"})

        assert "10.0%" in result

    def test_analyze_plastic_viscosity(self):
        """测试分析塑性黏度"""
        now = datetime.now()
        samples = [
            {"sample_time": now - timedelta(hours=1), "plastic_viscosity": 20},
            {"sample_time": now, "plastic_viscosity": 30}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "plastic_viscosity"})

        assert "plastic_viscosity" in result.lower()
        assert "上升" in result

    def test_analyze_yield_point(self):
        """测试分析动切力"""
        now = datetime.now()
        samples = [
            {"sample_time": now - timedelta(hours=1), "yield_point": 15},
            {"sample_time": now, "yield_point": 12}
        ]

        result = analyze_trend.invoke({"samples": samples, "field": "yield_point"})

        assert "yield_point" in result.lower()
        assert "下降" in result


class TestFormatPrescription:
    """format_prescription 工具测试"""

    def test_prescription_density_high(self):
        """测试密度偏高处方"""
        result = format_prescription.invoke({
            "measures": "密度偏高，需要稀释",
            "density": 1.35,
            "plastic_viscosity": 20
        })

        assert "稀释水: 8%" in result
        assert "搅拌时间: 45分钟" in result

    def test_prescription_density_low(self):
        """测试密度偏低处方"""
        result = format_prescription.invoke({
            "measures": "密度低，需要加重",
            "density": 1.15,
            "plastic_viscosity": 20
        })

        assert "加重剂: 重晶石 2%" in result

    def test_prescription_viscosity_high(self):
        """测试黏度偏高处方"""
        result = format_prescription.invoke({
            "measures": "塑性黏度高",
            "density": 1.20,
            "plastic_viscosity": 35
        })

        assert "降黏剂: 0.3%" in result
        assert "稀释水: 5%" in result

    def test_prescription_combined_issues(self):
        """测试组合问题处方"""
        result = format_prescription.invoke({
            "measures": "密度高且黏度高",
            "density": 1.35,
            "plastic_viscosity": 35
        })

        # 应该包含密度和黏度处理
        assert "降黏剂" in result or "稀释水" in result

    def test_prescription_default(self):
        """测试默认处方"""
        result = format_prescription.invoke({
            "measures": "常规维护",
            "density": 1.20,
            "plastic_viscosity": 20
        })

        assert "稀释水: 0%" in result
        assert "降黏剂: 0%" in result
        assert "搅拌时间: 30分钟" in result

    def test_prescription_format(self):
        """测试处方格式"""
        result = format_prescription.invoke({
            "measures": "测试",
            "density": 1.20,
            "plastic_viscosity": 20
        })

        # 验证每个项目都有前缀
        assert "- 稀释水:" in result
        assert "- 降黏剂:" in result
        assert "- 加重剂:" in result
        assert "- 搅拌时间:" in result

    def test_prescription_density_and_viscosity_high(self):
        """测试密度和黏度都偏高"""
        # 使用工具实际匹配的关键词
        result = format_prescription.invoke({
            "measures": "密度偏高且塑性黏度高",  # 使用"高"而不是"偏高"
            "density": 1.40,
            "plastic_viscosity": 40
        })

        # 塑性黏度高会覆盖稀释水设置
        assert "稀释水: 5%" in result
        # 降黏剂会被添加
        assert "降黏剂: 0.3%" in result
        # 搅拌时间会设置为45分钟
        assert "搅拌时间: 45分钟" in result

    def test_prescription_all_agents_zero_by_default(self):
        """测试默认所有药剂为 0"""
        result = format_prescription.invoke({
            "measures": "不需要处理",
            "density": 1.20,
            "plastic_viscosity": 20
        })

        assert "稀释水: 0%" in result
        assert "降黏剂: 0%" in result
        assert "加重剂: 0%" in result

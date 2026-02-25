# 项目初始化文档

## 项目概述

**项目名称**: yibccc-langchain
**项目描述**: 基于 LangChain 1.0.0 (create_agent) 的agent服务
**整体架构**: 前端vue3 -> 后端springboot3 -> agent服务（当前项目）

**主要技术栈**:
- Web 框架: FastAPI 0.115+
- LLM 编排: LangChain 1.0.0 (`create_agent`)
- Checkpoint 存储: LangGraph AsyncRedisSaver
- 异步同步: Redis Stream + Consumer Group
- 数据库: PostgreSQL (asyncpg) Redis
- 异步运行时: asyncio + uvloop

## 项目结构

```
yibccc-langchain/
├── docs/                    # 项目文档
├── src/                       # 源代码
│   ├── api/                  # API 层
│   │   ├── main.py          # FastAPI 应用入口
│   │   └── routes/          # 路由定义
│       ├── services/             # 服务层
│       │   ├── chat_service.py  # 对话服务 (基于 create_agent)
│       │   └── sync_service.py  # 同步服务
│       ├── repositories/       # 数据访问层
│       │   └── pg_repo.py       # PostgreSQL 仓储
│       ├── models/              # 数据模型
│       │   ├── schemas.py        # 请求/响应模型
│       │   └── exceptions.py    # 异常定义
│       └── tools/               # 工具函数
│           └── builtin.py     # 内置工具 (时间查询等)
├── tests/                   # 测试
├── sql/                      # 数据库脚本
│   └── schema.sql           # 数据库表结构
└── .env.example             # 环境变量模板
```

## 开发规范

### Python 版本
- Python 3.11
- 使用 `uv` 包管理
- 代码风格：遵循 PEP 8
- 类型注解：使用 Python type hints

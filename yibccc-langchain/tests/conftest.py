"""pytest 配置和 fixtures"""
import pytest
import asyncio
import asyncpg


@pytest.fixture(scope="session")
def event_loop():
    """创建事件循环"""
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
async def pg_repo():
    """PostgreSQL 仓储 fixture"""
    from src.config import settings
    from src.repositories.pg_repo import PostgreSQLRepository

    # 保存原始配置并设置测试配置
    original_user = settings.pg_user
    original_password = settings.pg_password
    original_db = settings.pg_database
    test_db = f"{original_db}_test"

    # 设置正确的用户名和密码
    settings.pg_user = "root"
    settings.pg_password = "root"
    settings.pg_database = test_db

    # 连接到默认数据库创建测试数据库
    temp_dsn = "postgresql://root:root@localhost:5432/postgres"
    conn = await asyncpg.connect(temp_dsn)
    await conn.execute(f"DROP DATABASE IF EXISTS {test_db}")
    await conn.execute(f"CREATE DATABASE {test_db}")
    await conn.close()

    repo = PostgreSQLRepository()
    await repo.connect()

    # 运行迁移
    migration_path = "src/repositories/migrations/002_langgraph_checkpoint.sql"
    with open(migration_path) as f:
        migration_sql = f.read()
    async with repo.pool.acquire() as conn:
        await conn.execute(migration_sql)

    yield repo

    # 清理
    await repo.disconnect()
    settings.pg_user = original_user
    settings.pg_password = original_password
    settings.pg_database = original_db

    # 删除测试数据库
    conn = await asyncpg.connect(temp_dsn)
    await conn.execute(f"DROP DATABASE IF EXISTS {test_db}")
    await conn.close()


@pytest.fixture(autouse=True)
async def setup_test_environment():
    """设置测试环境 - API Key 配置"""
    from src.config import settings

    # 设置测试 API Key
    original_keys_str = settings.api_keys_str
    object.__setattr__(settings, 'api_keys_str', 'test-key')

    yield

    object.__setattr__(settings, 'api_keys_str', original_keys_str)


@pytest.fixture
async def sync_service(pg_repo):
    """同步服务 fixture"""
    from src.services.sync_service import SyncService
    return SyncService(pg_repo=pg_repo)


# ========== 诊断模块 fixtures ==========

@pytest.fixture
async def diagnosis_pg_pool():
    """诊断系统测试用的数据库连接池"""
    from src.config import settings
    import asyncpg

    # 设置测试数据库
    original_db = settings.pg_database
    test_db = f"{original_db}_test"

    # 使用 root 用户
    temp_dsn = "postgresql://root:root@localhost:5432/postgres"
    conn = await asyncpg.connect(temp_dsn)
    await conn.execute(f"DROP DATABASE IF EXISTS {test_db}")
    await conn.execute(f"CREATE DATABASE {test_db}")

    # 启用 pgvector 扩展
    await conn.execute(f"ALTER DATABASE {test_db} SET timezone = 'UTC'")
    await conn.close()

    # 创建连接池
    pool = await asyncpg.create_pool(
        host=settings.pg_host,
        port=settings.pg_port,
        user="root",
        password="root",
        database=test_db,
        min_size=2,
        max_size=10
    )

    # 创建表结构
    async with pool.acquire() as conn:
        # 读取 schema 文件
        import os
        schema_path = os.path.join(os.path.dirname(__file__), "../docs/sql/diagnosis_schema.sql")
        if os.path.exists(schema_path):
            with open(schema_path) as f:
                schema_sql = f.read()
            await conn.execute(schema_sql)
        else:
            # 如果文件不存在，手动创建表
            await conn.execute("CREATE EXTENSION IF NOT EXISTS vector;")
            await conn.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_documents (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    doc_id VARCHAR(100) UNIQUE NOT NULL,
                    title VARCHAR(500) NOT NULL,
                    category VARCHAR(50) NOT NULL,
                    subcategory VARCHAR(100),
                    content TEXT NOT NULL,
                    metadata JSONB,
                    chunk_count INT DEFAULT 0,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    parent_doc_id VARCHAR(100) NOT NULL,
                    chunk_index INT NOT NULL,
                    content TEXT NOT NULL,
                    embedding vector(1024),
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );

                CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
                ON knowledge_chunks USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64);

                CREATE TABLE IF NOT EXISTS diagnosis_tasks (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    task_id VARCHAR(100) UNIQUE NOT NULL,
                    well_id VARCHAR(50) NOT NULL,
                    alert_type VARCHAR(50) NOT NULL,
                    alert_triggered_at TIMESTAMPTZ NOT NULL,
                    alert_threshold JSONB,
                    samples JSONB NOT NULL,
                    context JSONB,
                    callback_url TEXT,
                    status VARCHAR(20) DEFAULT 'PENDING',
                    started_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS diagnosis_results (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    task_id VARCHAR(100) UNIQUE NOT NULL,
                    diagnosis JSONB NOT NULL,
                    trend_analysis JSONB,
                    measures JSONB,
                    prescription JSONB,
                    "references" JSONB,
                    rag_metadata JSONB,
                    llm_metadata JSONB,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS diagnosis_events (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    task_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(20) NOT NULL,
                    event_data JSONB NOT NULL,
                    sequence_num INT NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT NOW()
                );
            """)

    yield pool

    # 清理
    await pool.close()
    conn = await asyncpg.connect(temp_dsn)
    await conn.execute(f"DROP DATABASE IF EXISTS {test_db}")
    await conn.close()


@pytest.fixture
async def diagnosis_repo(diagnosis_pg_pool):
    """诊断仓储 fixture"""
    from src.repositories.diagnosis_repo import DiagnosisRepository
    return DiagnosisRepository(diagnosis_pg_pool)


@pytest.fixture
async def knowledge_repo(diagnosis_pg_pool, mock_embeddings):
    """知识库仓储 fixture"""
    from src.repositories.knowledge_repo import KnowledgeRepository
    return KnowledgeRepository(diagnosis_pg_pool, embedding_client=mock_embeddings)


@pytest.fixture
def mock_embeddings():
    """模拟 Embedding 客户端"""
    from unittest.mock import AsyncMock
    mock_client = AsyncMock()
    mock_client.embed_query = AsyncMock(return_value=[0.1] * 1024)
    return mock_client


@pytest.fixture
def mock_diagnosis_agent():
    """模拟诊断 Agent"""
    from unittest.mock import AsyncMock
    agent = AsyncMock()
    agent.initialize = AsyncMock()
    agent.cleanup = AsyncMock()
    return agent

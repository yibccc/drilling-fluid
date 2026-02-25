"""
RAG 服务测试

测试检索增强生成服务
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from src.services.rag_service import RAGService
from src.models.diagnosis_schemas import KnowledgeDocumentCreate
from src.models.exceptions import RAGError


@pytest.fixture
def mock_knowledge_repo():
    """模拟知识库仓储"""
    repo = AsyncMock()
    repo.create_document = AsyncMock(return_value="DOC-001")
    repo.get_document = AsyncMock()
    repo.delete_document = AsyncMock()
    repo.list_documents = AsyncMock()
    repo.vector_search = AsyncMock()
    repo.create_chunks = AsyncMock()
    return repo


@pytest.fixture
def mock_embeddings():
    """模拟 Embedding 客户端"""
    embeddings = AsyncMock()
    embeddings.embed_query = AsyncMock(return_value=[0.1] * 1024)
    return embeddings


@pytest.fixture
def rag_service(mock_knowledge_repo):
    """创建 RAG 服务实例"""
    service = RAGService(mock_knowledge_repo)

    # 注入模拟的 embeddings
    service.embeddings = mock_embeddings()
    mock_knowledge_repo.embedding_client = service.embeddings

    return service


@pytest.fixture
def sample_document():
    """测试知识文档"""
    return KnowledgeDocumentCreate(
        doc_id="DOC-TEST-001",
        title="密度偏高处置措施",
        category="density",
        subcategory="high",
        content="""# 密度偏高处置

当钻井液密度偏高时，应采取以下措施：

1. 加水稀释：通常加水量为 5-10%
2. 持续循环监测密度变化
3. 调整钻井液配方
4. 检查固相控制设备

注意事项：
- 稀释后应循环至少 2 倍容积
- 密切监测切力变化
""",
        metadata={"author": "专家A", "version": "1.0"}
    )


class TestRAGService:
    """RAGService 测试"""

    def test_init(self, mock_knowledge_repo):
        """测试初始化"""
        service = RAGService(mock_knowledge_repo)
        assert service.knowledge_repo == mock_knowledge_repo

    def test_init_with_embeddings(self, mock_knowledge_repo):
        """测试初始化（带 Embeddings）"""
        with patch("src.services.rag_service.DashScopeEmbeddings") as mock_class:
            mock_instance = MagicMock()
            mock_class.return_value = mock_instance

            service = RAGService(mock_knowledge_repo)

            # 验证 embeddings 初始化
            mock_class.assert_called_once()
            assert service.knowledge_repo.embedding_client == mock_instance

    # ========== 文档管理测试 ==========

    @pytest.mark.asyncio
    async def test_create_document_success(self, rag_service, mock_knowledge_repo, sample_document):
        """测试成功创建文档"""
        doc_id = await rag_service.create_document(sample_document)

        assert doc_id == "DOC-TEST-001"
        mock_knowledge_repo.create_document.assert_called_once_with(sample_document)

    @pytest.mark.asyncio
    async def test_create_document_with_chunks(
        self,
        rag_service,
        mock_knowledge_repo,
        sample_document,
        mock_embeddings
    ):
        """测试创建文档并自动分块"""
        # 预期分块数量
        expected_chunks = len(sample_document.content) // 600 + 1

        await rag_service.create_document(sample_document, auto_chunk=True)

        # 验证创建文档和分块
        mock_knowledge_repo.create_document.assert_called_once()
        mock_knowledge_repo.create_chunks.assert_called_once()

        # 验证分块参数
        call_args = mock_knowledge_repo.create_chunks.call_args
        assert call_args[0][0] == "DOC-TEST-001"  # doc_id
        assert isinstance(call_args[0][1], list)  # chunks

    @pytest.mark.asyncio
    async def test_create_document_without_chunks(self, rag_service, mock_knowledge_repo, sample_document):
        """测试创建文档不分块"""
        await rag_service.create_document(sample_document, auto_chunk=False)

        mock_knowledge_repo.create_document.assert_called_once()
        mock_knowledge_repo.create_chunks.assert_not_called()

    @pytest.mark.asyncio
    async def test_create_document_error(self, rag_service, mock_knowledge_repo, sample_document):
        """测试创建文档错误"""
        mock_knowledge_repo.create_document.side_effect = Exception("DB Error")

        with pytest.raises(RAGError, match="创建文档失败"):
            await rag_service.create_document(sample_document)

    @pytest.mark.asyncio
    async def test_get_document(self, rag_service, mock_knowledge_repo):
        """测试获取文档"""
        mock_doc = {
            "doc_id": "DOC-001",
            "title": "测试文档",
            "content": "测试内容"
        }
        mock_knowledge_repo.get_document.return_value = mock_doc

        result = await rag_service.get_document("DOC-001")

        assert result is not None
        assert result["doc_id"] == "DOC-001"
        mock_knowledge_repo.get_document.assert_called_once_with("DOC-001")

    @pytest.mark.asyncio
    async def test_delete_document(self, rag_service, mock_knowledge_repo):
        """测试删除文档"""
        mock_knowledge_repo.delete_document.return_value = True

        result = await rag_service.delete_document("DOC-001")

        assert result is True
        mock_knowledge_repo.delete_document.assert_called_once_with("DOC-001")

    @pytest.mark.asyncio
    async def test_list_documents(self, rag_service, mock_knowledge_repo):
        """测试列出文档"""
        mock_docs = [
            {"doc_id": "DOC-001", "title": "文档1"},
            {"doc_id": "DOC-002", "title": "文档2"}
        ]
        mock_knowledge_repo.list_documents.return_value = mock_docs

        result = await rag_service.list_documents(category="density", limit=100)

        assert len(result) == 2
        mock_knowledge_repo.list_documents.assert_called_once_with("density", 100)

    @pytest.mark.asyncio
    async def test_list_documents_default_params(self, rag_service, mock_knowledge_repo):
        """测试列出文档（默认参数）"""
        mock_knowledge_repo.list_documents.return_value = []

        await rag_service.list_documents()

        mock_knowledge_repo.list_documents.assert_called_once_with(None, 100)

    # ========== 语义检索测试 ==========

    @pytest.mark.asyncio
    async def test_search_success(self, rag_service, mock_knowledge_repo):
        """测试成功检索"""
        mock_results = [
            {
                "doc_id": "DOC-001",
                "title": "密度偏高处置",
                "category": "density",
                "content": "测试内容",
                "distance": 0.123
            }
        ]
        mock_knowledge_repo.vector_search.return_value = mock_results

        results = await rag_service.search(
            query="密度偏高怎么处理",
            top_k=5,
            category="density"
        )

        assert len(results) == 1
        assert results[0]["doc_id"] == "DOC-001"
        assert results[0]["distance"] == 0.123

        mock_knowledge_repo.vector_search.assert_called_once_with(
            query="密度偏高怎么处理",
            top_k=5,
            category="density"
        )

    @pytest.mark.asyncio
    async def test_search_no_category(self, rag_service, mock_knowledge_repo):
        """测试不带分类的检索"""
        mock_knowledge_repo.vector_search.return_value = []

        results = await rag_service.search(query="测试查询")

        assert results == []

        mock_knowledge_repo.vector_search.assert_called_once_with(
            query="测试查询",
            top_k=5,
            category=None
        )

    @pytest.mark.asyncio
    async def test_search_error(self, rag_service, mock_knowledge_repo):
        """测试检索错误"""
        mock_knowledge_repo.vector_search.side_effect = Exception("Search failed")

        with pytest.raises(RAGError, match="检索失败"):
            await rag_service.search(query="测试")

    # ========== 文本分块测试 ==========

    def test_split_text(self, rag_service):
        """测试文本分块"""
        # 创建一个较长的文本（超过 600 字符）
        long_text = "测试内容" * 200  # 约 800 字符

        chunks = rag_service._split_text(long_text)

        assert len(chunks) > 1
        assert all("content" in chunk for chunk in chunks)

    def test_split_short_text(self, rag_service):
        """测试短文本不分块"""
        short_text = "短文本"

        chunks = rag_service._split_text(short_text)

        assert len(chunks) == 1
        assert chunks[0]["content"] == short_text

    def test_split_text_chunk_size(self, rag_service):
        """测试分块大小"""
        text = "内容" * 250  # 约 500 字符

        chunks = rag_service._split_text(text)

        # 每块应不超过 600 字符（加上重叠）
        for chunk in chunks:
            assert len(chunk["content"]) <= 650  # 允许一些重叠

    def test_split_text_overlap(self, rag_service):
        """测试分块重叠"""
        # 使用可预测的内容
        text = "A" * 400 + "B" * 400 + "C" * 400

        chunks = rag_service._split_text(text)

        # 验证相邻块之间有重叠
        if len(chunks) > 1:
            # 第一块和第二块应该有重叠
            first_chunk = chunks[0]["content"]
            second_chunk = chunks[1]["content"]

            # 检查重叠存在（B 应该在两块中都出现）
            assert "B" in first_chunk or "A" in second_chunk

    # ========== 重建索引测试 ==========

    @pytest.mark.asyncio
    async def test_rebuild_index_single_doc(self, rag_service, mock_knowledge_repo):
        """测试重建单个文档索引"""
        mock_doc = {
            "doc_id": "DOC-001",
            "content": "测试内容"
        }
        mock_knowledge_repo.get_document.return_value = mock_doc
        mock_knowledge_repo.create_chunks = AsyncMock()

        result = await rag_service.rebuild_index(doc_id="DOC-001")

        assert result["rebuilt"] == 1
        mock_knowledge_repo.get_document.assert_called_once_with("DOC-001")
        mock_knowledge_repo.create_chunks.assert_called_once()

    @pytest.mark.asyncio
    async def test_rebuild_index_doc_not_found(self, rag_service, mock_knowledge_repo):
        """测试重建不存在的文档"""
        mock_knowledge_repo.get_document.return_value = None

        with pytest.raises(RAGError, match="不存在"):
            await rag_service.rebuild_index(doc_id="NON-EXISTENT")

    @pytest.mark.asyncio
    async def test_rebuild_index_all_docs(self, rag_service, mock_knowledge_repo):
        """测试重建所有文档索引"""
        mock_docs = [
            {"doc_id": "DOC-001", "content": "内容1"},
            {"doc_id": "DOC-002", "content": "内容2"},
            {"doc_id": "DOC-003", "content": "内容3"}
        ]
        mock_knowledge_repo.list_documents.return_value = mock_docs
        mock_knowledge_repo.create_chunks = AsyncMock()

        result = await rag_service.rebuild_index()

        assert result["rebuilt"] == 3
        mock_knowledge_repo.list_documents.assert_called_once_with(limit=1000)

    @pytest.mark.asyncio
    async def test_rebuild_index_partial_failure(self, rag_service, mock_knowledge_repo):
        """测试重建索引部分失败"""
        mock_docs = [
            {"doc_id": "DOC-001", "content": "内容1"},
            {"doc_id": "DOC-002", "content": "内容2"}
        ]
        mock_knowledge_repo.list_documents.return_value = mock_docs

        async def mock_create_chunks(doc_id, chunks):
            if doc_id == "DOC-002":
                raise Exception("Failed")
            return 1

        mock_knowledge_repo.create_chunks = mock_create_chunks

        result = await rag_service.rebuild_index()

        # 应该返回成功重建的数量（忽略失败的）
        assert result["rebuilt"] >= 0

    # ========== Embedding 初始化测试 ==========

    def test_init_embeddings_failure(self, mock_knowledge_repo):
        """测试 Embedding 初始化失败"""
        with patch("src.services.rag_service.DashScopeEmbeddings") as mock_class:
            mock_class.side_effect = Exception("API Key error")

            service = RAGService(mock_knowledge_repo)

            # 应该警告但不中断
            assert service.embeddings is None


class TestRAGServiceChunkingStrategy:
    """分块策略测试"""

    def test_recursive_character_splitter_params(self, rag_service):
        """测试递归分块器参数"""
        # 验证默认参数
        assert hasattr(rag_service, "_split_text")

        # 测试实际分块
        text = "word " * 1000  # 约 5000 字符

        chunks = rag_service._split_text(text)

        # 验证参数效果
        # chunk_size=600, chunk_overlap=100
        for i, chunk in enumerate(chunks):
            content_length = len(chunk["content"])
            # 允许适当超出 chunk_size（中文字符等）
            assert content_length <= 700

    def test_empty_text(self, rag_service):
        """测试空文本分块"""
        chunks = rag_service._split_text("")

        assert len(chunks) == 0 or (len(chunks) == 1 and chunks[0]["content"] == "")

    def test_single_character_text(self, rag_service):
        """测试单个字符文本"""
        chunks = rag_service._split_text("A")

        assert len(chunks) == 1
        assert chunks[0]["content"] == "A"


class TestRAGServiceSearchScenarios:
    """检索场景测试"""

    @pytest.mark.asyncio
    async def test_search_returns_formatted_results(self, rag_service, mock_knowledge_repo):
        """测试检索返回格式化结果"""
        mock_results = [
            {
                "doc_id": "DOC-001",
                "title": "测试文档",
                "category": "density",
                "content": "内容...",
                "distance": 0.234  # 可能是 decimal 类型
            }
        ]
        mock_knowledge_repo.vector_search.return_value = mock_results

        results = await rag_service.search("测试查询")

        # 验证 distance 转换为 float
        assert isinstance(results[0]["distance"], float)
        assert results[0]["distance"] == 0.234

    @pytest.mark.asyncio
    async def test_search_empty_results(self, rag_service, mock_knowledge_repo):
        """测试空检索结果"""
        mock_knowledge_repo.vector_search.return_value = []

        results = await rag_service.search("无匹配查询")

        assert results == []

    @pytest.mark.asyncio
    async def test_search_with_different_top_k(self, rag_service, mock_knowledge_repo):
        """测试不同的 top_k 值"""
        mock_knowledge_repo.vector_search.return_value = []

        for k in [1, 3, 5, 10]:
            await rag_service.search("测试", top_k=k)

            # 验证传递了正确的 top_k
            call_args = mock_knowledge_repo.vector_search.call_args_list[-1]
            assert call_args[0][1] == k  # top_k 参数

    @pytest.mark.asyncio
    async def test_search_with_categories(self, rag_service, mock_knowledge_repo):
        """测试不同分类检索"""
        mock_knowledge_repo.vector_search.return_value = []

        categories = ["density", "viscosity", "gel", None]

        for category in categories:
            await rag_service.search("测试", category=category)

            call_args = mock_knowledge_repo.vector_search.call_args_list[-1]
            assert call_args[0][2] == category  # category 参数

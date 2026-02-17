-- =====================================================
-- 知识图谱系统 V2.0 数据库升级脚本
-- 功能：父子索引策略 + MinerU解析 + 混合检索
-- =====================================================

-- 1. 给 kg_document_chunk 表增加父子索引相关字段
ALTER TABLE kg_document_chunk
    ADD COLUMN parent_id BIGINT NULL COMMENT '父块ID（child块通过此字段回溯parent块）' AFTER document_id,
    ADD COLUMN chunk_type VARCHAR(20) NOT NULL DEFAULT 'parent' COMMENT '分块类型：parent=完整章节, child=LLM摘要' AFTER parent_id,
    ADD COLUMN section_title VARCHAR(500) NULL COMMENT '章节标题' AFTER chunk_index;

-- 2. 添加索引
ALTER TABLE kg_document_chunk
    ADD INDEX idx_parent_id (parent_id),
    ADD INDEX idx_chunk_type (chunk_type),
    ADD INDEX idx_doc_type (document_id, chunk_type);

-- 3. 将已有数据标记为 parent 类型（兼容旧数据）
UPDATE kg_document_chunk SET chunk_type = 'parent' WHERE chunk_type IS NULL OR chunk_type = '';

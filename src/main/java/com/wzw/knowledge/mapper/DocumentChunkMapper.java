package com.wzw.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzw.knowledge.model.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档分块Mapper接口（支持父子索引策略）
 *
 * @author wzw
 * @version 2.0
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 根据文档ID查询所有分块
     */
    @Select("SELECT * FROM kg_document_chunk WHERE document_id = #{documentId} AND deleted = 0 ORDER BY chunk_index")
    List<DocumentChunk> selectByDocumentId(@Param("documentId") Long documentId);

    /**
     * 根据分块ID查询
     */
    @Select("SELECT * FROM kg_document_chunk WHERE id = #{id} AND deleted = 0")
    DocumentChunk selectByChunkId(@Param("id") Long id);

    /**
     * 根据parentId查询父块
     */
    @Select("SELECT * FROM kg_document_chunk WHERE id = #{parentId} AND chunk_type = 'parent' AND deleted = 0")
    DocumentChunk selectParentById(@Param("parentId") Long parentId);

    /**
     * 根据文档ID查询所有parent块
     */
    @Select("SELECT * FROM kg_document_chunk WHERE document_id = #{documentId} AND chunk_type = 'parent' AND deleted = 0 ORDER BY chunk_index")
    List<DocumentChunk> selectParentsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 根据parentId查询所有child块
     */
    @Select("SELECT * FROM kg_document_chunk WHERE parent_id = #{parentId} AND chunk_type = 'child' AND deleted = 0")
    List<DocumentChunk> selectChildrenByParentId(@Param("parentId") Long parentId);

    /**
     * 删除文档的所有分块
     */
    @org.apache.ibatis.annotations.Update("UPDATE kg_document_chunk SET deleted = 1 WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}

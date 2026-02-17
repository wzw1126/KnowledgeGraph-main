package com.wzw.knowledge.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块实体
 * <p>
 * 支持父子索引策略：
 * - Parent块：按章节存储完整的Markdown内容
 * - Child块：LLM生成的摘要，用于向量化检索
 * 用户提问 → 搜到Child → 通过parentId回溯Parent → 把Parent喂给LLM
 * </p>
 *
 * @author wzw
 * @version 1.0
 */
@Data
@TableName("kg_document_chunk")
public class DocumentChunk {

    /** 分块类型：父块（完整章节） */
    public static final String TYPE_PARENT = "parent";
    /** 分块类型：子块（LLM摘要） */
    public static final String TYPE_CHILD = "child";

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关联的文档ID
     */
    private Long documentId;


    /**
     * 父块ID（child块通过此字段回溯parent块，parent块此字段为null）
     */
    private Long parentId;

    /**
     * 分块类型：parent / child
     */
    private String chunkType;

    /**
     * 页码（从1开始，0表示无页码概念）
     */
    private Integer pageNum;

    /**
     * 分块序号（同一文档内的顺序）
     */
    private Integer chunkIndex;

    /**
     * 章节标题（parent块的标题）
     */
    private String sectionTitle;

    /**
     * 分块内容（parent块存完整章节，child块存LLM摘要）
     */
    private String content;

    /**
     * 向量ID（Milvus中的ID，仅child块有值）
     */
    private String vectorId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 逻辑删除标记
     */
    @TableLogic
    private Integer deleted;
}

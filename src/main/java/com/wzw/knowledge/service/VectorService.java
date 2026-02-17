package com.wzw.knowledge.service;

import java.util.List;

/**
 * 向量服务接口
 * <p>
 * 定义与Milvus向量数据库交互的业务方法。
 * 支持混合检索（BM25全文检索 + 向量余弦相似度），使用BGE-M3模型。
 * 支持父子索引策略，metadata中存储parentId。
 * </p>
 *
 * @author wzw
 * @version 2.0
 */
public interface VectorService {

    /**
     * 初始化向量集合
     * 如果集合不存在则创建（包含全文索引和向量索引）
     */
    void initCollection();

    /**
     * 插入向量数据（带文本内容用于BM25全文检索）
     *
     * @param id       业务ID（chunk ID）
     * @param vector   向量数据
     * @param type     数据类型（child/node）
     * @param parentId 父块ID（仅child类型有效）
     * @param text     原文文本（用于BM25全文索引）
     * @return 向量ID
     */
    String insertVector(Long id, float[] vector, String type, Long parentId, String text);
    String insertVector(Long id, float[] vector, String type);

    /**
     * 插入向量数据（兼容旧接口）
     */
    /**
     * 批量插入向量数据
     *
     * @param ids     业务ID列表
     * @param vectors 向量数据列表
     * @param type    数据类型
     * @return 向量ID列表
     */
    List<String> insertVectors(List<Long> ids, List<float[]> vectors, String type);



    /**
     * 删除向量
     *
     * @param vectorId 向量ID
     * @return 是否成功
     */
    boolean deleteVector(String vectorId);

    /**
     * 混合检索（BM25 + 向量相似度 + RRF融合）
     *
     * @param queryVector 查询向量
     * @param queryText   查询文本（用于BM25）
     * @param topK        返回数量
     * @param type        数据类型过滤
     * @return 搜索结果列表
     */
    List<VectorSearchResult> hybridSearch(float[] queryVector, String queryText, int topK, String type);


    /**
     * 向量相似性搜索
     *
     * @param queryVector 查询向量
     * @param topK        返回数量
     * @param type        数据类型过滤（可选）
     * @return 相似结果列表（包含ID和相似度分数）
     */
    List<VectorSearchResult> search(float[] queryVector, int topK, String type);

    /**
     * 向量搜索结果封装类
     */
    record VectorSearchResult(
            Long id,
            String type,
            float score,
            Long parentId
    ) {}
}

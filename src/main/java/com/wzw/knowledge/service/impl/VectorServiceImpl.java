package com.wzw.knowledge.service.impl;

import com.wzw.knowledge.common.ResultCode;
import com.wzw.knowledge.exception.BusinessException;
import com.wzw.knowledge.service.VectorService;
import com.wzw.knowledge.config.MilvusConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 向量服务实现类（V2.0 - 支持混合检索 + 父子索引）
 * <p>
 * 使用 Milvus v2.5 实现：
 * - Dense向量索引（BGE-M3 1024维）用于语义检索
 * - VarChar全文字段 + BM25索引 用于字面匹配
 * - metadata中存储parentId，支持父子索引回溯
 * </p>
 *
 * @author wzw
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorServiceImpl implements VectorService {

    private final MilvusServiceClient milvusClient;
    private final MilvusConfig milvusConfig;

    // 字段名称常量
    private static final String FIELD_ID = "id";
    private static final String FIELD_BUSINESS_ID = "business_id";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PARENT_ID = "parent_id";
    private static final String FIELD_TEXT = "text_content";
    private static final String FIELD_VECTOR = "vector";

    @PostConstruct
    public void init() {
        try {
            initCollection();
            log.info("Milvus集合初始化成功（V2.0 混合检索模式）");
        } catch (Exception e) {
            log.warn("Milvus集合初始化失败，可能Milvus服务未启动: {}", e.getMessage());
        }
    }

    @Override
    public void initCollection() {
        String collectionName = milvusConfig.getCollectionName();

        // 检查集合是否存在
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );

        if (hasCollection.getData()) {
            log.info("集合 {} 已存在", collectionName);
            loadCollection();
            return;
        }

        // 创建集合字段
        List<FieldType> fieldTypes = new ArrayList<>();

        // 主键字段（自动生成）
        fieldTypes.add(FieldType.newBuilder()
                .withName(FIELD_ID)
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build());

        // 业务ID字段
        fieldTypes.add(FieldType.newBuilder()
                .withName(FIELD_BUSINESS_ID)
                .withDataType(DataType.Int64)
                .build());

        // 类型字段
        fieldTypes.add(FieldType.newBuilder()
                .withName(FIELD_TYPE)
                .withDataType(DataType.VarChar)
                .withMaxLength(50)
                .build());

        // 父块ID字段
        fieldTypes.add(FieldType.newBuilder()
                .withName(FIELD_PARENT_ID)
                .withDataType(DataType.Int64)
                .build());

        // 文本内容字段（用于BM25全文检索）
        fieldTypes.add(FieldType.newBuilder()
                .withName(FIELD_TEXT)
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .addTypeParam("enable_analyzer", "true")
                .addTypeParam("enable_match", "true")
                .build());

        // 向量字段（BGE-M3 1024维）
        fieldTypes.add(FieldType.newBuilder()
                .withName(FIELD_VECTOR)
                .withDataType(DataType.FloatVector)
                .withDimension(milvusConfig.getDimension())
                .build());

        // 创建集合
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("知识图谱向量集合V2 - 混合检索+父子索引")
                .withFieldTypes(fieldTypes)
                .withEnableDynamicField(true)
                .build();

        R<RpcStatus> createResult = milvusClient.createCollection(createParam);
        if (createResult.getStatus() != R.Status.Success.getCode()) {
            throw new BusinessException(ResultCode.MILVUS_ERROR, "创建集合失败: " + createResult.getMessage());
        }

        // 创建向量索引
        createVectorIndex();

        // 创建BM25全文索引
        createBM25Index();

        // 加载集合
        loadCollection();

        log.info("集合 {} 创建成功（含向量索引+BM25全文索引）", collectionName);
    }

    /**
     * 创建向量索引
     */
    private void createVectorIndex() {
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withFieldName(FIELD_VECTOR)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\": 1024}")
                .build();

        R<RpcStatus> result = milvusClient.createIndex(indexParam);
        if (result.getStatus() != R.Status.Success.getCode()) {
            log.warn("创建向量索引失败: {}", result.getMessage());
        }
    }

    /**
     * 创建BM25全文索引
     */
    private void createBM25Index() {
        try {
            CreateIndexParam bm25IndexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withFieldName(FIELD_TEXT)
                    .withIndexType(IndexType.AUTOINDEX)
                    .build();

            R<RpcStatus> result = milvusClient.createIndex(bm25IndexParam);
            if (result.getStatus() != R.Status.Success.getCode()) {
                log.warn("创建BM25索引失败（Milvus版本可能不支持）: {}", result.getMessage());
            } else {
                log.info("BM25全文索引创建成功");
            }
        } catch (Exception e) {
            log.warn("创建BM25索引异常（将退化为纯向量检索）: {}", e.getMessage());
        }
    }

    private void loadCollection() {
        R<RpcStatus> loadResult = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(milvusConfig.getCollectionName())
                        .build()
        );
        if (loadResult.getStatus() != R.Status.Success.getCode()) {
            log.warn("加载集合失败: {}", loadResult.getMessage());
        }
    }

    @Override
    public String insertVector(Long id, float[] vector, String type, Long parentId, String text) {
        List<Long> businessIds = Collections.singletonList(id);
        List<String> types = Collections.singletonList(type);
        List<Long> parentIds = Collections.singletonList(parentId != null ? parentId : 0L);
        List<String> texts = Collections.singletonList(text != null ? truncateText(text) : "");

        List<List<Float>> vectorList = new ArrayList<>();
        List<Float> v = new ArrayList<>();
        for (float f : vector) {
            v.add(f);
        }
        vectorList.add(v);

        // 准备字段数据
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_BUSINESS_ID, businessIds));
        fields.add(new InsertParam.Field(FIELD_TYPE, types));
        fields.add(new InsertParam.Field(FIELD_PARENT_ID, parentIds));
        fields.add(new InsertParam.Field(FIELD_TEXT, texts));
        fields.add(new InsertParam.Field(FIELD_VECTOR, vectorList));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withFields(fields)
                .build();

        R<MutationResult> insertResult = milvusClient.insert(insertParam);
        if (insertResult.getStatus() != R.Status.Success.getCode()) {
            log.error("插入向量失败: {}", insertResult.getMessage());
            throw new BusinessException(ResultCode.MILVUS_ERROR, "插入向量失败");
        }

        MutationResult data = insertResult.getData();
        List<Long> idList = data.getIDs().getIntId().getDataList();
        return idList.isEmpty() ? null : String.valueOf(idList.get(0));
    }

    @Override
    public String insertVector(Long id, float[] vector, String type) {
        return insertVector(id, vector, type, null, null);
    }

    @Override
    public List<String> insertVectors(List<Long> ids, List<float[]> vectors, String type) {
        List<String> types = new ArrayList<>();
        List<Long> parentIds = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            types.add(type);
            parentIds.add(0L);
            texts.add("");
        }

        List<List<Float>> vectorList = new ArrayList<>();
        for (float[] vector : vectors) {
            List<Float> v = new ArrayList<>();
            for (float f : vector) {
                v.add(f);
            }
            vectorList.add(v);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_BUSINESS_ID, ids));
        fields.add(new InsertParam.Field(FIELD_TYPE, types));
        fields.add(new InsertParam.Field(FIELD_PARENT_ID, parentIds));
        fields.add(new InsertParam.Field(FIELD_TEXT, texts));
        fields.add(new InsertParam.Field(FIELD_VECTOR, vectorList));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withFields(fields)
                .build();

        R<MutationResult> insertResult = milvusClient.insert(insertParam);
        if (insertResult.getStatus() != R.Status.Success.getCode()) {
            log.error("批量插入向量失败: {}", insertResult.getMessage());
            throw new BusinessException(ResultCode.MILVUS_ERROR, "批量插入向量失败");
        }

        List<String> vectorIds = new ArrayList<>();
        MutationResult data = insertResult.getData();
        for (Long milvusId : data.getIDs().getIntId().getDataList()) {
            vectorIds.add(String.valueOf(milvusId));
        }
        return vectorIds;
    }

    @Override
    public boolean deleteVector(String vectorId) {
        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withExpr(FIELD_ID + " == " + vectorId)
                    .build();

            R<MutationResult> deleteResult = milvusClient.delete(deleteParam);
            return deleteResult.getStatus() == R.Status.Success.getCode();
        } catch (Exception e) {
            log.error("删除向量失败", e);
            return false;
        }
    }

    /**
     * 混合检索：BM25字面匹配 + 向量语义匹配 + RRF融合排序
     */
    @Override
    public List<VectorSearchResult> hybridSearch(float[] queryVector, String queryText, int topK, String type) {
        // 先做纯向量检索
        List<VectorSearchResult> vectorResults = search(queryVector, topK * 2, type);

        if (queryText == null || queryText.isBlank()) {
            return vectorResults.stream().limit(topK).toList();
        }

        // 尝试基于text_content字段做文本匹配（Milvus v2.5 text_match）
        try {
            List<VectorSearchResult> textMatchResults = searchWithTextMatch(queryVector, queryText, topK, type);
            if (!textMatchResults.isEmpty()) {
                // RRF融合排序
                return rrfFusion(vectorResults, textMatchResults, topK);
            }
        } catch (Exception e) {
            log.debug("文本匹配检索不可用，退化为纯向量检索: {}", e.getMessage());
        }

        return vectorResults.stream().limit(topK).toList();
    }

    /**
     * 带文本匹配的向量搜索
     */
    private List<VectorSearchResult> searchWithTextMatch(float[] queryVector, String queryText, int topK, String type) {
        List<List<Float>> searchVectors = new ArrayList<>();
        List<Float> v = new ArrayList<>();
        for (float f : queryVector) {
            v.add(f);
        }
        searchVectors.add(v);

        // 构建过滤表达式：类型过滤 + 文本匹配
        StringBuilder expr = new StringBuilder();
        if (type != null && !type.isEmpty()) {
            expr.append(FIELD_TYPE).append(" == \"").append(type).append("\"");
        }

        // 添加text_match条件（Milvus v2.5.x 全文检索）
        String sanitizedText = queryText.replace("\"", "\\\"");
        if (!expr.isEmpty()) {
            expr.append(" and ");
        }
        expr.append("text_match(").append(FIELD_TEXT).append(", \"").append(sanitizedText).append("\")");

        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withOutFields(Arrays.asList(FIELD_BUSINESS_ID, FIELD_TYPE, FIELD_PARENT_ID))
                .withTopK(topK)
                .withVectors(searchVectors)
                .withVectorFieldName(FIELD_VECTOR)
                .withParams("{\"nprobe\": 10}")
                .withExpr(expr.toString());

        R<SearchResults> searchResult = milvusClient.search(searchBuilder.build());
        if (searchResult.getStatus() != R.Status.Success.getCode()) {
            log.debug("文本匹配搜索失败: {}", searchResult.getMessage());
            return new ArrayList<>();
        }

        return parseSearchResults(searchResult);
    }

    /**
     * 纯向量相似性搜索
     */
    @Override
    public List<VectorSearchResult> search(float[] queryVector, int topK, String type) {
        List<List<Float>> searchVectors = new ArrayList<>();
        List<Float> v = new ArrayList<>();
        for (float f : queryVector) {
            v.add(f);
        }
        searchVectors.add(v);

        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(milvusConfig.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withOutFields(Arrays.asList(FIELD_BUSINESS_ID, FIELD_TYPE, FIELD_PARENT_ID))
                .withTopK(topK)
                .withVectors(searchVectors)
                .withVectorFieldName(FIELD_VECTOR)
                .withParams("{\"nprobe\": 10}");

        if (type != null && !type.isEmpty()) {
            searchBuilder.withExpr(FIELD_TYPE + " == \"" + type + "\"");
        }

        R<SearchResults> searchResult = milvusClient.search(searchBuilder.build());
        if (searchResult.getStatus() != R.Status.Success.getCode()) {
            log.error("向量搜索失败: {}", searchResult.getMessage());
            return new ArrayList<>();
        }

        return parseSearchResults(searchResult);
    }

    /**
     * 解析Milvus搜索结果
     */
    private List<VectorSearchResult> parseSearchResults(R<SearchResults> searchResult) {
        List<VectorSearchResult> results = new ArrayList<>();
        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResult.getData().getResults());
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

        for (int idx = 0; idx < idScores.size(); idx++) {
            SearchResultsWrapper.IDScore idScore = idScores.get(idx);

            Long businessId = null;
            String resultType = null;
            Long parentId = null;

            Object businessIdObj = wrapper.getFieldData(FIELD_BUSINESS_ID, 0);
            Object typeObj = wrapper.getFieldData(FIELD_TYPE, 0);
            Object parentIdObj = wrapper.getFieldData(FIELD_PARENT_ID, 0);

            if (businessIdObj instanceof List<?> list && idx < list.size()) {
                businessId = ((Number) list.get(idx)).longValue();
            }
            if (typeObj instanceof List<?> list && idx < list.size()) {
                resultType = String.valueOf(list.get(idx));
            }
            if (parentIdObj instanceof List<?> list && idx < list.size()) {
                long pid = ((Number) list.get(idx)).longValue();
                parentId = pid > 0 ? pid : null;
            }

            results.add(new VectorSearchResult(businessId, resultType, idScore.getScore(), parentId));
        }

        return results;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合排序
     * 将两路检索结果合并排序
     */
    private List<VectorSearchResult> rrfFusion(
            List<VectorSearchResult> vectorResults,
            List<VectorSearchResult> textResults,
            int topK) {

        final int K = 60; // RRF常数
        Map<Long, Float> scoreMap = new LinkedHashMap<>();
        Map<Long, VectorSearchResult> resultMap = new HashMap<>();

        // 向量检索结果的RRF分数
        for (int i = 0; i < vectorResults.size(); i++) {
            VectorSearchResult r = vectorResults.get(i);
            if (r.id() != null) {
                float rrfScore = 1.0f / (K + i + 1);
                scoreMap.merge(r.id(), rrfScore, Float::sum);
                resultMap.putIfAbsent(r.id(), r);
            }
        }

        // 文本匹配结果的RRF分数
        for (int i = 0; i < textResults.size(); i++) {
            VectorSearchResult r = textResults.get(i);
            if (r.id() != null) {
                float rrfScore = 1.0f / (K + i + 1);
                scoreMap.merge(r.id(), rrfScore, Float::sum);
                resultMap.putIfAbsent(r.id(), r);
            }
        }

        // 按RRF分数降序排序
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Float>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    VectorSearchResult original = resultMap.get(entry.getKey());
                    return new VectorSearchResult(
                            original.id(), original.type(), entry.getValue(), original.parentId());
                })
                .toList();
    }

    /**
     * 截断文本，避免超过Milvus VarChar最大长度
     */
    private String truncateText(String text) {
        if (text == null) return "";
        // Milvus VarChar最大65535字节，UTF-8中文3字节，保守截取20000字符
        return text.length() > 20000 ? text.substring(0, 20000) : text;
    }
}

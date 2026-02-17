package com.uka.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.wzw.knowledge.mapper.DocumentChunkMapper;
import com.wzw.knowledge.mapper.KnowledgeRelationMapper;
import com.wzw.knowledge.model.entity.Document;
import com.wzw.knowledge.model.entity.DocumentChunk;
import com.wzw.knowledge.model.entity.KnowledgeNode;
import com.wzw.knowledge.model.entity.KnowledgeRelation;
import com.wzw.knowledge.model.vo.RagDocument;
import com.wzw.knowledge.model.vo.RagNode;
import com.wzw.knowledge.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG检索增强服务实现类
 * <p>
 * 实现RAG检索功能，从向量数据库检索相关文档和知识节点
 * </p>
 *
 * @author uka
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final OllamaService ollamaService;
    private final VectorService vectorService;
    private final DocumentService documentService;
    private final KnowledgeNodeService nodeService;
    private final KnowledgeRelationMapper relationMapper;
    private final DocumentChunkMapper documentChunkMapper;

    private static final String PROMPT_TEMPLATE = """
        你是一个专业的知识助手。请根据以下参考内容回答用户的问题。

        【参考内容】
        %s

        【回答要求 - 必须严格遵守】
        1. 仔细阅读参考内容，提取与问题相关的关键信息
        2. 用清晰、准确的语言组织答案
        3. 不要在回答中包含来源信息，系统会自动添加
        4. 如果参考内容确实完全不包含相关信息，才回答："根据已上传的文档，未找到与此问题相关的信息。"
        5. 即使参考内容不完整，也要尽力根据现有信息给出部分答案
        6. 基于参考内容回答，不要编造或猜测内容中没有的信息
        
        【数值和单位的核心规则 - 这是最重要的】
        7. **绝对不得修改参考内容中的任何数值** - 包括不得进行单位转换、数学计算、四舍五入等操作
        8. **必须使用参考内容中的原始单位** - 如果原文是"克(g)"就用"克(g)"，如果是"毫克(mg)"就用"毫克(mg)"，严禁将克转为千克/公斤
        9. **保持数值范围的完整性** - 如原文是"25 g/d～30 g/d"，回答必须是"25 g/d～30 g/d"或"25-30克/天"，不得计算平均值或转换单位
        10. 当涉及营养素、剂量、含量等数值时，必须一字不差地引用原文表述

        请开始回答：""";

    /**
     * 执行RAG检索
     */
    @Override
    public RagResult search(String query, int topK) {
        log.info("执行RAG检索, query={}, topK={}", query, topK);

        // 检索相关文档
        List<RagDocument> documents = searchDocuments(query, topK);
        log.info("文档检索完成, 找到{}个相关文档", documents.size());

        // 检索相关节点
        List<RagNode> nodes = searchNodes(query, topK);
        log.info("节点检索完成, 找到{}个相关节点", nodes.size());

        // 构建上下文提示词
        String contextPrompt = buildContextPrompt(documents, query);

        // 打印完整的 prompt 用于调试（截取前2000字符以确保看到数值内容）
        if (log.isDebugEnabled()) {
            String previewPrompt = contextPrompt.length() > 2000
                    ? contextPrompt.substring(0, 2000) + "..."
                    : contextPrompt;
            log.debug("RAG上下文提示词预览:\n{}", previewPrompt);
        }

        // 检测潜在的数值信息并记录（用于审计）
        if (documents != null && !documents.isEmpty()) {
            for (RagDocument doc : documents) {
                String content = doc.getMatchedContent() != null ? doc.getMatchedContent() : doc.getSummary();
                if (content != null && content.matches(".*\\d+\\s*(g|mg|kg|克|毫克|公斤|千克).*")) {
                    log.info("检测到包含数值单位的文档: docName={}, 内容片段={}",
                            doc.getName(),
                            content.length() > 200 ? content.substring(0, 200) : content);
                }
            }
        }

        return new RagResult(documents, nodes, contextPrompt);
    }

    /**
     * 检索相关文档（V2.0 - 父子索引 + 混合检索）
     * <p>
     * 流程：
     * 1. 混合检索（BM25字面匹配 + 向量语义匹配 + RRF融合）搜索Child块
     * 2. 通过parentId回溯Parent块
     * 3. 用Parent块的完整内容构建RAG上下文
     * </p>
     */
    @Override
    public List<RagDocument> searchDocuments(String query, int topK) {
        List<RagDocument> results = new ArrayList<>();

        try {
            // 生成查询向量
            float[] queryVector = ollamaService.generateEmbedding(query);

            // 混合检索 - 搜索child类型（BM25 + 向量 + RRF融合）
            List<VectorService.VectorSearchResult> searchResults =
                    vectorService.hybridSearch(queryVector, query, topK, "child");

            log.info("混合检索返回{}个Child块结果", searchResults.size());

            // 去重parentId，避免同一个Parent块被重复返回
            java.util.Set<Long> seenParentIds = new java.util.LinkedHashSet<>();

            for (VectorService.VectorSearchResult result : searchResults) {
                if (result.id() == null) continue;

                try {
                    // 查询Child分块信息
                    DocumentChunk childChunk = documentChunkMapper.selectByChunkId(result.id());
                    if (childChunk == null) continue;

                    // 通过parentId回溯Parent块
                    Long parentId = result.parentId();
                    if (parentId == null) {
                        parentId = childChunk.getParentId();
                    }

                    DocumentChunk parentChunk = null;
                    if (parentId != null && parentId > 0) {
                        // 去重：同一个Parent只取一次（取最高分的Child对应的Parent）
                        if (seenParentIds.contains(parentId)) {
                            continue;
                        }
                        seenParentIds.add(parentId);
                        parentChunk = documentChunkMapper.selectParentById(parentId);
                    }

                    // 查询所属文档
                    Long docId = childChunk.getDocumentId();
                    Document document = documentService.getById(docId);
                    if (document == null) continue;

                    RagDocument ragDoc = new RagDocument();
                    ragDoc.setId(document.getId());
                    ragDoc.setChunkId(childChunk.getId());
                    ragDoc.setName(document.getName());
                    ragDoc.setFileType(document.getFileType());
                    ragDoc.setPageNum(childChunk.getPageNum());
                    ragDoc.setSummary(document.getSummary());
                    ragDoc.setScore((double) result.score());

                    // 关键：用Parent块的完整内容作为RAG上下文（"存大搜小"）
                    if (parentChunk != null && StrUtil.isNotBlank(parentChunk.getContent())) {
                        ragDoc.setMatchedContent(parentChunk.getContent());
                        log.debug("父子索引回溯: childId={}, parentId={}, parentTitle={}, parentLen={}",
                                childChunk.getId(), parentId,
                                parentChunk.getSectionTitle(),
                                parentChunk.getContent().length());
                    } else {
                        // 降级：使用Child块内容
                        ragDoc.setMatchedContent(childChunk.getContent());
                        log.debug("未找到Parent块，使用Child内容: childId={}", childChunk.getId());
                    }

                    results.add(ragDoc);
                } catch (Exception e) {
                    log.warn("获取分块详情失败, chunkId={}", result.id(), e);
                }
            }
        } catch (Exception e) {
            log.error("文档检索失败", e);
        }

        log.info("文档检索完成（父子索引+混合检索）, 返回{}个结果", results.size());
        return results;
    }

    /**
     * 检索相关知识节点（使用混合检索）
     */
    @Override
    public List<RagNode> searchNodes(String query, int topK) {
        List<RagNode> results = new ArrayList<>();

        try {
            // 生成查询向量
            float[] queryVector = ollamaService.generateEmbedding(query);

            // 混合检索 - 搜索节点类型
            List<VectorService.VectorSearchResult> searchResults =
                    vectorService.hybridSearch(queryVector, query, topK, "node");

            // 获取节点详情及其关系
            for (VectorService.VectorSearchResult result : searchResults) {
                if (result.id() == null) continue;

                try {
                    KnowledgeNode node = nodeService.getById(result.id());
                    if (node != null) {
                        RagNode ragNode = new RagNode();
                        ragNode.setId(node.getId());
                        ragNode.setName(node.getName());
                        ragNode.setNodeType(node.getNodeType());
                        ragNode.setDescription(node.getDescription());
                        ragNode.setScore((double) result.score());

                        // 解析属性
                        if (StrUtil.isNotBlank(node.getProperties())) {
                            ragNode.setProperties(JSON.parseObject(node.getProperties(), Map.class));
                        }

                        // 获取关联关系
                        List<RagNode.RagRelation> relations = getNodeRelations(node.getId());
                        ragNode.setRelations(relations);

                        results.add(ragNode);
                    }
                } catch (Exception e) {
                    log.warn("获取节点详情失败, id={}", result.id(), e);
                }
            }
        } catch (Exception e) {
            log.error("节点检索失败", e);
        }

        return results;
    }

    /**
     * 获取节点的关联关系
     */
    private List<RagNode.RagRelation> getNodeRelations(Long nodeId) {
        List<RagNode.RagRelation> relations = new ArrayList<>();

        try {
            // 获取以该节点为起点的关系
            List<KnowledgeRelation> outRelations = relationMapper.selectBySourceNodeId(nodeId);
            for (KnowledgeRelation rel : outRelations) {
                KnowledgeNode targetNode = nodeService.getById(rel.getTargetNodeId());
                if (targetNode != null) {
                    RagNode.RagRelation ragRel = new RagNode.RagRelation();
                    ragRel.setName(rel.getName());
                    ragRel.setRelationType(rel.getRelationType());
                    ragRel.setTargetNodeId(targetNode.getId());
                    ragRel.setTargetNodeName(targetNode.getName());
                    relations.add(ragRel);
                }
            }
        } catch (Exception e) {
            log.warn("获取节点关系失败, nodeId={}", nodeId, e);
        }

        return relations;
    }

    /**
     * 构建RAG上下文提示词
     */
    @Override
    public String buildContextPrompt(List<RagDocument> documents, String userQuery) {
        StringBuilder contextBuilder = new StringBuilder();

        // 添加相关文档
        if (documents != null && !documents.isEmpty()) {
            for (int i = 0; i < documents.size(); i++) {
                RagDocument doc = documents.get(i);
                contextBuilder.append(String.format("文档 %d - 《%s》", i + 1, doc.getName()));

                // 添加页码信息
                if (doc.getPageNum() != null && doc.getPageNum() > 0) {
                    contextBuilder.append(String.format("（第 %d 页）", doc.getPageNum()));
                }
                contextBuilder.append(String.format("（相似度：%.0f%%）\n", doc.getScore() * 100));

                // 添加文档内容
                if (StrUtil.isNotBlank(doc.getMatchedContent())) {
                    contextBuilder.append("内容：").append(doc.getMatchedContent().trim());
                } else if (StrUtil.isNotBlank(doc.getSummary())) {
                    contextBuilder.append("摘要：").append(doc.getSummary().trim());
                }
                contextBuilder.append("\n\n");
            }
        }

        // 如果没有找到相关文档，返回空提示
        if (contextBuilder.length() == 0) {
            contextBuilder.append("（未找到相关文档内容）\n");
        }

        return PROMPT_TEMPLATE.formatted(contextBuilder.toString()) + "\n\n【用户问题】\n" + userQuery;
    }
}

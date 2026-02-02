package com.wzw.knowledge.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzw.knowledge.common.ResultCode;
import com.wzw.knowledge.config.FileConfig;
import com.wzw.knowledge.exception.BusinessException;
import com.wzw.knowledge.mapper.ChatAttachmentMapper;
import com.wzw.knowledge.mapper.ChatMessageMapper;
import com.wzw.knowledge.mapper.ChatSessionMapper;
import com.wzw.knowledge.model.dto.ChatSendRequest;
import com.wzw.knowledge.model.entity.ChatAttachment;
import com.wzw.knowledge.model.entity.ChatMessage;
import com.wzw.knowledge.model.entity.ChatSession;
import com.wzw.knowledge.model.vo.*;
import com.wzw.knowledge.service.ChatService;
import com.wzw.knowledge.service.OllamaService;
import com.wzw.knowledge.service.RagService;
import com.wzw.knowledge.util.DocumentParser;
import com.wzw.knowledge.util.ResponseValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天服务实现类
 * <p>
 * 实现AI对话相关的业务逻辑，包括：
 * - 会话管理（创建、列表、删除）
 * - 消息发送（带RAG增强）
 * - 文件上传与解析
 * - 思考链处理
 * </p>
 *
 * @author wzw
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatAttachmentMapper attachmentMapper;
    private final OllamaService ollamaService;
    private final RagService ragService;
    private final DocumentParser documentParser;
    private final FileConfig fileConfig;

    /**
     * 用于SSE流式响应的线程池
     */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * 思考链标签的正则表达式
     * 匹配 <think>...</think> 格式
     */
    private static final Pattern THINKING_PATTERN = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);

    /**
     * 创建新会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO createSession() {
        ChatSession session = new ChatSession();
        session.setTitle("新对话");
        session.setMessageCount(0);

        this.save(session);

        log.info("创建聊天会话成功, sessionId={}", session.getId());
        return convertToSessionVO(session);
    }

    /**
     * 获取会话列表
     */
    @Override
    public List<ChatSessionVO> listSessions() {
        List<ChatSession> sessions = sessionMapper.selectAllOrderByLastMessageTime();
        return sessions.stream().map(this::convertToSessionVO).toList();
    }

    /**
     * 删除会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSession(Long sessionId) {
        ChatSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }

        // 逻辑删除会话（消息会级联删除）
        boolean result = this.removeById(sessionId);

        log.info("删除聊天会话成功, sessionId={}", sessionId);
        return result;
    }

    /**
     * 更新会话标题
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO updateSessionTitle(Long sessionId, String title) {
        ChatSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }

        session.setTitle(title);
        this.updateById(session);

        return convertToSessionVO(session);
    }

    /**
     * 发送消息（带RAG增强）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSendResponse sendMessage(Long sessionId, ChatSendRequest request) {
        // 验证会话
        ChatSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }

        String userMessage = request.getMessage();
        Boolean enableRag = request.getEnableRag() != null ? request.getEnableRag() : true;

        // 处理附件
        String attachmentContext = "";
        List<ChatAttachment> attachments = new ArrayList<>();
        if (request.getAttachmentIds() != null && !request.getAttachmentIds().isEmpty()) {
            attachments = attachmentMapper.selectByIds(request.getAttachmentIds());
            attachmentContext = buildAttachmentContext(attachments);
        }

        // 执行RAG检索
        RagService.RagResult ragResult = null;
        String prompt = userMessage;

        if (enableRag) {
            // 增加检索数量到10，提高找到相关内容的概率
            ragResult = ragService.search(userMessage, 10);

            // 构建带RAG上下文的提示词
            if (ragResult != null && StrUtil.isNotBlank(ragResult.contextPrompt())) {
                prompt = ragResult.contextPrompt();
            }
        }

        // 添加附件上下文
        if (StrUtil.isNotBlank(attachmentContext)) {
            prompt = prompt + "\n\n【附件内容】\n" + attachmentContext;
        }

        // 获取历史消息作为上下文
        List<ChatMessage> history = messageMapper.selectRecentMessages(sessionId, 10);
        String historyContext = buildHistoryContext(history);
        if (StrUtil.isNotBlank(historyContext)) {
            prompt = historyContext + "\n\n" + prompt;
        }

        // 调用大模型
        String aiResponse;
        try {
            aiResponse = ollamaService.chat(prompt);
        } catch (Exception e) {
            log.error("调用大模型失败", e);
            throw new BusinessException(ResultCode.OLLAMA_ERROR, "AI服务暂时不可用，请稍后重试");
        }

        // 解析思考链
        String thinkingContent = null;
        String content = aiResponse;

        Matcher matcher = THINKING_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            thinkingContent = matcher.group(1).trim();
            content = matcher.replaceAll("").trim();
        }

        // 验证AI响应的数值准确性（如果启用了RAG）
        if (enableRag && ragResult != null && !ragResult.documents().isEmpty()) {
            // 收集所有参考文档的内容
            StringBuilder referenceContent = new StringBuilder();
            for (RagDocument doc : ragResult.documents()) {
                if (StrUtil.isNotBlank(doc.getMatchedContent())) {
                    referenceContent.append(doc.getMatchedContent()).append(" ");
                }
            }
            
            // 验证响应
            ResponseValidator.ValidationResult validation =
                ResponseValidator.validate(referenceContent.toString(), content);
            
            if (!validation.isValid()) {
                log.error("AI响应验证失败: {}", validation.getMessage());
                // 在响应中添加警告信息
                content = content + "\n\n⚠️ 系统检测提示：本回答可能存在数值单位转换问题，请以原始文档为准。";
            } else if (validation.isWarning()) {
                log.warn("AI响应验证警告: {}", validation.getMessage());
            }
        }

        // 如果启用了RAG，自动附加文档来源信息
        if (enableRag && ragResult != null && !ragResult.documents().isEmpty()) {
            content = appendSourceInfo(content, ragResult.documents());
        }

        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole(ChatMessage.ROLE_USER);
        userMsg.setContent(userMessage);
        if (!attachments.isEmpty()) {
            userMsg.setAttachments(JSON.toJSONString(attachments.stream()
                    .map(a -> Map.of("id", a.getId(), "fileName", a.getFileName()))
                    .toList()));
        }
        messageMapper.insert(userMsg);

        // 保存AI回复
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole(ChatMessage.ROLE_ASSISTANT);
        assistantMsg.setContent(content);
        assistantMsg.setThinkingContent(thinkingContent);

        // 保存RAG上下文
        if (ragResult != null) {
            Map<String, Object> ragContext = new HashMap<>();
            ragContext.put("documents", ragResult.documents());
            ragContext.put("nodes", ragResult.nodes());
            assistantMsg.setRagContext(JSON.toJSONString(ragContext));
        }
        messageMapper.insert(assistantMsg);

        // 更新会话统计
        sessionMapper.incrementMessageCount(sessionId);
        sessionMapper.incrementMessageCount(sessionId);

        // 如果是第一条消息，自动设置会话标题
        if (session.getMessageCount() == 0) {
            String title = userMessage.length() > 30 ? userMessage.substring(0, 30) + "..." : userMessage;
            session.setTitle(title);
            this.updateById(session);
        }

        // 构建响应
        ChatSendResponse response = new ChatSendResponse();
        response.setUserMessage(convertToMessageVO(userMsg, attachments));

        ChatMessageVO assistantVO = convertToMessageVO(assistantMsg, null);
        if (ragResult != null) {
            assistantVO.setRagDocuments(ragResult.documents());
            assistantVO.setRagNodes(ragResult.nodes());
        }
        response.setAssistantMessage(assistantVO);
        response.setRagDocuments(ragResult != null ? ragResult.documents() : null);
        response.setRagNodes(ragResult != null ? ragResult.nodes() : null);

        log.info("发送消息成功, sessionId={}, userMsgId={}, assistantMsgId={}",
                sessionId, userMsg.getId(), assistantMsg.getId());

        return response;
    }

    /**
     * 流式发送消息（带RAG增强）
     */
    @Override
    public SseEmitter sendMessageStream(Long sessionId, ChatSendRequest request) {
        // 验证会话
        ChatSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }

        String userMessage = request.getMessage();
        Boolean enableRag = request.getEnableRag() != null ? request.getEnableRag() : true;

        // 处理附件
        String attachmentContext = "";
        List<ChatAttachment> attachments = new ArrayList<>();
        if (request.getAttachmentIds() != null && !request.getAttachmentIds().isEmpty()) {
            attachments = attachmentMapper.selectByIds(request.getAttachmentIds());
            attachmentContext = buildAttachmentContext(attachments);
        }

        // 执行RAG检索
        RagService.RagResult ragResult = null;
        String prompt = userMessage;

        if (enableRag) {
            // 增加检索数量到10，提高找到相关内容的概率
            ragResult = ragService.search(userMessage, 10);
            if (ragResult != null && StrUtil.isNotBlank(ragResult.contextPrompt())) {
                prompt = ragResult.contextPrompt();
            }
        }

        // 添加附件上下文
        if (StrUtil.isNotBlank(attachmentContext)) {
            prompt = prompt + "\n\n【附件内容】\n" + attachmentContext;
        }

        // 获取历史消息作为上下文
        List<ChatMessage> history = messageMapper.selectRecentMessages(sessionId, 10);
        String historyContext = buildHistoryContext(history);
        if (StrUtil.isNotBlank(historyContext)) {
            prompt = historyContext + "\n\n" + prompt;
        }

        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole(ChatMessage.ROLE_USER);
        userMsg.setContent(userMessage);
        if (!attachments.isEmpty()) {
            userMsg.setAttachments(JSON.toJSONString(attachments.stream()
                    .map(a -> Map.of("id", a.getId(), "fileName", a.getFileName()))
                    .toList()));
        }
        messageMapper.insert(userMsg);

        // 创建AI消息记录（后续流式更新内容）
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole(ChatMessage.ROLE_ASSISTANT);
        assistantMsg.setContent("");
        if (ragResult != null) {
            Map<String, Object> ragContext = new HashMap<>();
            ragContext.put("documents", ragResult.documents());
            ragContext.put("nodes", ragResult.nodes());
            assistantMsg.setRagContext(JSON.toJSONString(ragContext));
        }
        messageMapper.insert(assistantMsg);

        // 更新会话统计
        sessionMapper.incrementMessageCount(sessionId);
        sessionMapper.incrementMessageCount(sessionId);

        // 如果是第一条消息，自动设置会话标题
        if (session.getMessageCount() == 0) {
            String title = userMessage.length() > 30 ? userMessage.substring(0, 30) + "..." : userMessage;
            session.setTitle(title);
            this.updateById(session);
        }

        // 创建SSE Emitter（超时5分钟）
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        final String finalPrompt = prompt;
        final RagService.RagResult finalRagResult = ragResult;
        final Long assistantMsgId = assistantMsg.getId();
        final Long userMsgId = userMsg.getId();
        final List<ChatAttachment> finalAttachments = attachments;

        // 使用线程池执行流式响应
        sseExecutor.execute(() -> {
            StringBuilder fullContent = new StringBuilder();
            try {
                // 首先发送消息ID和RAG结果
                Map<String, Object> initData = new HashMap<>();
                initData.put("type", "init");
                initData.put("userMessageId", userMsgId);
                initData.put("assistantMessageId", assistantMsgId);
                if (finalRagResult != null) {
                    initData.put("ragDocuments", finalRagResult.documents());
                    initData.put("ragNodes", finalRagResult.nodes());
                }
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(JSON.toJSONString(initData)));

                // 流式调用大模型
                ollamaService.chatStream(finalPrompt)
                        .doOnNext(chunk -> {
                            try {
                                fullContent.append(chunk);
                                Map<String, Object> chunkData = new HashMap<>();
                                chunkData.put("type", "chunk");
                                chunkData.put("content", chunk);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(JSON.toJSONString(chunkData)));
                            } catch (IOException e) {
                                log.error("发送SSE消息失败", e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                // 解析思考链
                                String content = fullContent.toString();
                                String thinkingContent = null;
                                Matcher matcher = THINKING_PATTERN.matcher(content);
                                if (matcher.find()) {
                                    thinkingContent = matcher.group(1).trim();
                                    content = matcher.replaceAll("").trim();
                                }

                                // 验证AI响应的数值准确性（如果启用了RAG）
                                if (enableRag && finalRagResult != null && !finalRagResult.documents().isEmpty()) {
                                    // 收集所有参考文档的内容
                                    StringBuilder referenceContent = new StringBuilder();
                                    for (RagDocument doc : finalRagResult.documents()) {
                                        if (StrUtil.isNotBlank(doc.getMatchedContent())) {
                                            referenceContent.append(doc.getMatchedContent()).append(" ");
                                        }
                                    }
                                    
                                    // 验证响应
                                    ResponseValidator.ValidationResult validation = 
                                        ResponseValidator.validate(referenceContent.toString(), content);
                                    
                                    if (!validation.isValid()) {
                                        log.error("流式AI响应验证失败: {}", validation.getMessage());
                                        // 在响应中添加警告信息
                                        String warningMsg = "\n\n⚠️ 系统检测提示：本回答可能存在数值单位转换问题，请以原始文档为准。";
                                        content = content + warningMsg;
                                        
                                        // 发送警告信息块
                                        Map<String, Object> warningData = new HashMap<>();
                                        warningData.put("type", "chunk");
                                        warningData.put("content", warningMsg);
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(JSON.toJSONString(warningData)));
                                    } else if (validation.isWarning()) {
                                        log.warn("流式AI响应验证警告: {}", validation.getMessage());
                                    }
                                }

                                // 如果启用了RAG，附加文档来源信息
                                if (enableRag && finalRagResult != null && !finalRagResult.documents().isEmpty()) {
                                    String sourceInfo = buildSourceInfo(finalRagResult.documents());
                                    content = content + sourceInfo;
                                    
                                    // 发送来源信息
                                    Map<String, Object> sourceData = new HashMap<>();
                                    sourceData.put("type", "source");
                                    sourceData.put("content", sourceInfo);
                                    emitter.send(SseEmitter.event()
                                            .name("message")
                                            .data(JSON.toJSONString(sourceData)));
                                }

                                // 更新消息内容
                                ChatMessage msgToUpdate = new ChatMessage();
                                msgToUpdate.setId(assistantMsgId);
                                msgToUpdate.setContent(content);
                                msgToUpdate.setThinkingContent(thinkingContent);
                                messageMapper.updateById(msgToUpdate);

                                // 发送完成信号
                                Map<String, Object> doneData = new HashMap<>();
                                doneData.put("type", "done");
                                doneData.put("thinkingContent", thinkingContent);
                                doneData.put("content", content);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(JSON.toJSONString(doneData)));

                                emitter.complete();
                                log.info("流式消息发送完成, sessionId={}, assistantMsgId={}", sessionId, assistantMsgId);
                            } catch (IOException e) {
                                log.error("发送完成信号失败", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.error("流式对话失败", error);
                            try {
                                Map<String, Object> errorData = new HashMap<>();
                                errorData.put("type", "error");
                                errorData.put("message", error.getMessage());
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(JSON.toJSONString(errorData)));
                            } catch (IOException e) {
                                log.error("发送错误信息失败", e);
                            }
                            emitter.completeWithError(error);
                        })
                        .subscribe();

            } catch (Exception e) {
                log.error("流式消息处理失败", e);
                emitter.completeWithError(e);
            }
        });

        // 设置超时和错误回调
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时, sessionId={}", sessionId);
            emitter.complete();
        });
        emitter.onError(error -> {
            log.error("SSE连接错误, sessionId={}", sessionId, error);
        });

        return emitter;
    }

    /**
     * 获取会话消息历史
     */
    @Override
    public List<ChatMessageVO> getMessages(Long sessionId) {
        // 验证会话
        ChatSession session = this.getById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }

        List<ChatMessage> messages = messageMapper.selectBySessionId(sessionId);

        return messages.stream().map(msg -> {
            List<ChatAttachment> attachments = null;
            if (StrUtil.isNotBlank(msg.getAttachments())) {
                // 解析附件ID列表并查询
                try {
                    List<Map> attachmentList = JSON.parseArray(msg.getAttachments(), Map.class);
                    List<Long> ids = attachmentList.stream()
                            .map(m -> Long.valueOf(m.get("id").toString()))
                            .toList();
                    if (!ids.isEmpty()) {
                        attachments = attachmentMapper.selectByIds(ids);
                    }
                } catch (Exception e) {
                    log.warn("解析附件信息失败", e);
                }
            }

            ChatMessageVO vo = convertToMessageVO(msg, attachments);

            // 解析RAG上下文
            if (StrUtil.isNotBlank(msg.getRagContext())) {
                try {
                    Map<String, Object> ragContext = JSON.parseObject(msg.getRagContext(), Map.class);
                    if (ragContext.get("documents") != null) {
                        vo.setRagDocuments(JSON.parseArray(
                                JSON.toJSONString(ragContext.get("documents")), RagDocument.class));
                    }
                    if (ragContext.get("nodes") != null) {
                        vo.setRagNodes(JSON.parseArray(
                                JSON.toJSONString(ragContext.get("nodes")), RagNode.class));
                    }
                } catch (Exception e) {
                    log.warn("解析RAG上下文失败", e);
                }
            }

            return vo;
        }).toList();
    }

    /**
     * 上传聊天附件
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatAttachmentVO uploadAttachment(MultipartFile file) {
        // 验证文件
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        String fileType = FileUtil.getSuffix(originalName);

        if (!fileConfig.isAllowedType(fileType)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORT,
                    "不支持的文件类型: " + fileType);
        }

        // 保存文件
        String filePath = saveFile(file);

        // 解析文件内容
        String parsedContent = null;
        try {
            // 只解析文本类型的文件
            if (isTextFile(fileType)) {
                parsedContent = documentParser.parse(filePath, fileType);
                // 限制解析内容长度
                if (parsedContent != null && parsedContent.length() > 10000) {
                    parsedContent = parsedContent.substring(0, 10000) + "...(内容已截断)";
                }
            }
        } catch (Exception e) {
            log.warn("附件解析失败: {}", originalName, e);
        }

        // 创建附件记录
        ChatAttachment attachment = new ChatAttachment();
        attachment.setFileName(originalName);
        attachment.setFilePath(filePath);
        attachment.setFileType(fileType);
        attachment.setFileSize(file.getSize());
        attachment.setParsedContent(parsedContent);

        attachmentMapper.insert(attachment);

        log.info("上传聊天附件成功, attachmentId={}, fileName={}", attachment.getId(), originalName);

        return convertToAttachmentVO(attachment);
    }

    /**
     * 获取附件详情
     */
    @Override
    public ChatAttachment getAttachment(Long attachmentId) {
        return attachmentMapper.selectById(attachmentId);
    }

    /**
     * 保存文件到本地
     */
    private String saveFile(MultipartFile file) {
        try {
            // 生成存储路径：uploads/chat/2024/01/
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));

            Path basePath = Paths.get(fileConfig.getUploadPath()).toAbsolutePath().normalize();
            Path uploadDir = basePath.resolve("chat").resolve(datePath);
            Files.createDirectories(uploadDir);

            // 生成唯一文件名
            String originalName = file.getOriginalFilename();
            String extension = FileUtil.getSuffix(originalName);
            String newFileName = IdUtil.fastSimpleUUID() + "." + extension;

            // 保存文件
            Path filePath = uploadDir.resolve(newFileName);
            Files.write(filePath, file.getBytes());

            return filePath.toString();

        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 判断是否为可解析的文本文件
     */
    private boolean isTextFile(String fileType) {
        return Set.of("txt", "md", "pdf", "doc", "docx").contains(fileType.toLowerCase());
    }

    /**
     * 构建附件上下文
     */
    private String buildAttachmentContext(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ChatAttachment attachment : attachments) {
            if (StrUtil.isNotBlank(attachment.getParsedContent())) {
                sb.append(String.format("文件「%s」内容:\n%s\n\n",
                        attachment.getFileName(), attachment.getParsedContent()));
            }
        }
        return sb.toString();
    }

    /**
     * 构建历史消息上下文
     */
    private String buildHistoryContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        // 按时间正序排列
        List<ChatMessage> sorted = new ArrayList<>(history);
        Collections.reverse(sorted);

        StringBuilder sb = new StringBuilder();
        sb.append("【历史对话】\n");

        for (ChatMessage msg : sorted) {
            String role = ChatMessage.ROLE_USER.equals(msg.getRole()) ? "用户" : "助手";
            String content = msg.getContent();
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append(role).append(": ").append(content).append("\n");
        }

        return sb.toString();
    }

    /**
     * 会话实体转VO
     */
    private ChatSessionVO convertToSessionVO(ChatSession session) {
        ChatSessionVO vo = new ChatSessionVO();
        BeanUtils.copyProperties(session, vo);
        return vo;
    }

    /**
     * 消息实体转VO
     */
    private ChatMessageVO convertToMessageVO(ChatMessage message, List<ChatAttachment> attachments) {
        ChatMessageVO vo = new ChatMessageVO();
        BeanUtils.copyProperties(message, vo);

        // 转换附件
        if (attachments != null && attachments.isEmpty()) {
            vo.setAttachments(attachments.stream()
                    .map(this::convertToAttachmentVO)
                    .toList());
        }

        return vo;
    }

    /**
     * 附件实体转VO
     */
    private ChatAttachmentVO convertToAttachmentVO(ChatAttachment attachment) {
        ChatAttachmentVO vo = new ChatAttachmentVO();
        BeanUtils.copyProperties(attachment, vo);
        return vo;
    }

    /**
     * 构建文档来源信息
     */
    private String buildSourceInfo(List<RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        StringBuilder sourceInfo = new StringBuilder("\n\n📚 参考来源：\n");
        Set<String> addedSources = new HashSet<>();

        for (int i = 0; i < Math.min(documents.size(), 5); i++) {
            RagDocument doc = documents.get(i);
            String source;

            if (doc.getPageNum() != null && doc.getPageNum() > 0) {
                source = String.format("- 《%s》第 %d 页", doc.getName(), doc.getPageNum());
            } else {
                source = String.format("- 《%s》", doc.getName());
            }

            // 避免重复来源
            if (!addedSources.contains(source)) {
                addedSources.add(source);
                sourceInfo.append(source).append("\n");
            }
        }

        return sourceInfo.toString();
    }

    /**
     * 在AI回答后附加文档来源信息
     */
    private String appendSourceInfo(String content, List<RagDocument> documents) {
        if (content == null || documents == null || documents.isEmpty()) {
            return content;
        }

        // 检查是否是"未找到相关信息"的回答
        if (content.contains("未找到与此问题相关的信息")) {
            return content;
        }

        return content + buildSourceInfo(documents);
    }
}

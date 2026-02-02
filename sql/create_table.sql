-- kg_chat_attachment：聊天附件
CREATE TABLE `kg_chat_attachment` (
                                      `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                      `message_id` BIGINT NULL COMMENT '消息ID',
                                      `file_name` VARCHAR(255) NULL COMMENT '文件名',
                                      `file_path` VARCHAR(500) NULL COMMENT '文件路径',
                                      `file_type` VARCHAR(64) NULL COMMENT '文件类型',
                                      `file_size` BIGINT NULL COMMENT '文件大小（字节）',
                                      `parsed_content` LONGTEXT NULL COMMENT '解析后的内容',
                                      `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                      PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天附件表';

CREATE INDEX `idx_chat_attachment_message_id` ON `kg_chat_attachment` (`message_id`);


-- kg_chat_message：聊天消息
CREATE TABLE `kg_chat_message` (
                                   `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                   `session_id` BIGINT NULL COMMENT '会话ID',
                                   `role` VARCHAR(32) NULL COMMENT '角色（user/assistant/system）',
                                   `content` LONGTEXT NULL COMMENT '消息内容',
                                   `thinking_content` LONGTEXT NULL COMMENT '思考链内容',
                                   `attachments` JSON NULL COMMENT '附件信息（JSON）',
                                   `rag_context` JSON NULL COMMENT 'RAG上下文（JSON）',
                                   `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识（0-未删除,1-已删除）',
                                   PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';

CREATE INDEX `idx_chat_message_session_id` ON `kg_chat_message` (`session_id`);
CREATE INDEX `idx_chat_message_deleted` ON `kg_chat_message` (`deleted`);


-- kg_chat_session：会话
CREATE TABLE `kg_chat_session` (
                                   `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                   `title` VARCHAR(255) NULL COMMENT '会话标题',
                                   `message_count` INT DEFAULT 0 COMMENT '消息数量',
                                   `last_message_time` DATETIME NULL COMMENT '最后消息时间',
                                   `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                   `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                                   PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话表';

CREATE INDEX `idx_chat_session_last_message_time` ON `kg_chat_session` (`last_message_time`);


-- kg_document：文档元数据
CREATE TABLE `kg_document` (
                               `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                               `name` VARCHAR(255) NULL COMMENT '文档名称',
                               `original_name` VARCHAR(255) NULL COMMENT '原始文件名',
                               `file_path` VARCHAR(500) NULL COMMENT '文件路径',
                               `file_type` VARCHAR(64) NULL COMMENT '文件类型',
                               `file_size` BIGINT NULL COMMENT '文件大小（字节）',
                               `content` LONGTEXT NULL COMMENT '文档内容（解析后）',
                               `summary` TEXT NULL COMMENT '内容摘要',
                               `status` TINYINT DEFAULT 0 COMMENT '文档状态（0-待处理,1-处理中,2-已完成,3-失败）',
                               `error_msg` TEXT NULL COMMENT '处理错误信息',
                               `vector_id` VARCHAR(128) NULL COMMENT '向量ID（Milvus）',
                               `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                               `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                               PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

CREATE INDEX `idx_document_vector_id` ON `kg_document` (`vector_id`);
CREATE INDEX `idx_document_status` ON `kg_document` (`status`);


-- kg_document_chunk：文档分块
CREATE TABLE `kg_document_chunk` (
                                     `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                     `document_id` BIGINT NULL COMMENT '关联文档ID',
                                     `page_num` INT NULL COMMENT '页码（从1开始，0表示无页码）',
                                     `chunk_index` INT NULL COMMENT '分块序号（页内顺序）',
                                     `content` LONGTEXT NULL COMMENT '分块内容',
                                     `vector_id` VARCHAR(128) NULL COMMENT '向量ID（Milvus）',
                                     `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                                     PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分块表';

CREATE INDEX `idx_doc_chunk_document_id` ON `kg_document_chunk` (`document_id`);
CREATE INDEX `idx_doc_chunk_vector_id` ON `kg_document_chunk` (`vector_id`);
CREATE INDEX `idx_doc_chunk_page_num` ON `kg_document_chunk` (`page_num`);


-- kg_knowledge_node：知识节点（MySQL元数据）
CREATE TABLE `kg_knowledge_node` (
                                     `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                     `name` VARCHAR(255) NULL COMMENT '节点名称',
                                     `node_type` VARCHAR(64) NULL COMMENT '节点类型',
                                     `description` TEXT NULL COMMENT '节点描述',
                                     `properties` JSON NULL COMMENT '节点属性（JSON）',
                                     `neo4j_id` VARCHAR(128) NULL COMMENT 'Neo4j中的节点ID',
                                     `vector_id` VARCHAR(128) NULL COMMENT '向量ID（Milvus）',
                                     `source_doc_id` BIGINT NULL COMMENT '来源文档ID',
                                     `source_type` VARCHAR(64) NULL COMMENT '来源类型（document/ocr/manual）',
                                     `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                     `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                     `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                                     PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识节点表';

CREATE INDEX `idx_knowledge_node_neo4j_id` ON `kg_knowledge_node` (`neo4j_id`);
CREATE INDEX `idx_knowledge_node_vector_id` ON `kg_knowledge_node` (`vector_id`);
CREATE INDEX `idx_knowledge_node_name` ON `kg_knowledge_node` (`name`);


-- kg_knowledge_relation：知识关系（MySQL元数据）
CREATE TABLE `kg_knowledge_relation` (
                                         `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                         `name` VARCHAR(255) NULL COMMENT '关系名称',
                                         `relation_type` VARCHAR(64) NULL COMMENT '关系类型',
                                         `source_node_id` BIGINT NULL COMMENT '起始节点ID',
                                         `target_node_id` BIGINT NULL COMMENT '目标节点ID',
                                         `weight` DOUBLE NULL COMMENT '关系权重（0-1）',
                                         `properties` JSON NULL COMMENT '关系属性（JSON）',
                                         `neo4j_rel_id` VARCHAR(128) NULL COMMENT 'Neo4j中的关系ID',
                                         `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                         `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                         `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                                         PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识关系表';

CREATE INDEX `idx_knowledge_relation_source_target` ON `kg_knowledge_relation` (`source_node_id`, `target_node_id`);
CREATE INDEX `idx_knowledge_relation_neo4j_rel_id` ON `kg_knowledge_relation` (`neo4j_rel_id`);


-- kg_ocr_record：OCR记录
CREATE TABLE `kg_ocr_record` (
                                 `id` BIGINT NOT NULL COMMENT '主键ID（雪花算法生成）',
                                 `image_name` VARCHAR(255) NULL COMMENT '图片名称',
                                 `original_name` VARCHAR(255) NULL COMMENT '原始文件名',
                                 `image_path` VARCHAR(500) NULL COMMENT '图片路径',
                                 `image_type` VARCHAR(64) NULL COMMENT '图片类型',
                                 `image_size` BIGINT NULL COMMENT '图片大小（字节）',
                                 `ocr_text` LONGTEXT NULL COMMENT 'OCR识别结果文本',
                                 `confidence` DOUBLE NULL COMMENT '识别置信度',
                                 `language` VARCHAR(64) NULL COMMENT '识别语言',
                                 `status` TINYINT DEFAULT 0 COMMENT '处理状态（0-待处理,1-处理中,2-已完成,3-失败）',
                                 `error_msg` TEXT NULL COMMENT '错误信息',
                                 `vector_id` VARCHAR(128) NULL COMMENT '向量ID',
                                 `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                 `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标识',
                                 PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OCR记录表';

CREATE INDEX `idx_ocr_vector_id` ON `kg_ocr_record` (`vector_id`);
CREATE INDEX `idx_ocr_status` ON `kg_ocr_record` (`status`);
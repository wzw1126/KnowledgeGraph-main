
# 知识图谱系统

基于 Spring Boot3 + SpringAI + Milvus + Neo4j + Tesseract + Vue3 + Element Plus 的本地大模型智能知识图谱管理系统，集成 RAG 检索增强、AI 对话、文档解析、OCR 识别等功能。

## 功能特性

### 核心功能

- **知识图谱可视化** - 基于 ECharts 的交互式图谱展示，支持节点拖拽、缩放、关系探索
- **AI 智能对话** - 基于 RAG 的智能问答，支持 SSE 流式响应、思考链展示、Markdown 渲染
- **文档管理** - 支持 PDF、Word、TXT、Markdown 等多格式文档上传与解析
- **OCR 识别** - 基于 Tesseract 的图片文字识别，支持中英文混合
- **知识抽取** - 利用大模型自动从文档中抽取实体和关系，构建知识图谱

### 技术亮点

- **RAG 检索增强** - 基于 Milvus 向量数据库实现语义检索，支持文档分块和页码定位
- **多模型支持** - 通过 Ollama 集成 DeepSeek、Llama、Qwen 等开源大模型
- **图数据库** - 使用 Neo4j 存储和查询复杂的知识关系网络
- **实时流式响应** - SSE 推送 AI 回复，支持思考链（Chain of Thought）展示

## 技术栈

### 后端
| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.2.5 | 基础框架 |
| Spring AI | 1.1.2 | AI 模型集成 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| Neo4j | 5.x | 图数据库 |
| Milvus | 2.3.x | 向量数据库 |
| MySQL | 8.x | 关系型数据库 |
| Tesseract | 5.x | OCR 引擎 |
| Apache PDFBox | 3.0.1 | PDF 解析 |
| Apache POI | 5.2.5 | Office 文档解析 |

### 前端
| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.4.x | 前端框架 |
| Vue Router | 4.3.x | 路由管理 |
| Pinia | 2.1.x | 状态管理 |
| Element Plus | 2.6.x | UI 组件库 |
| ECharts | 5.5.x | 图表可视化 |
| markdown-it | 14.x | Markdown 渲染 |
| highlight.js | 11.x | 代码高亮 |


## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- MySQL 8.0+
- Neo4j 5.x
- Milvus 2.3+
- Ollama
- Tesseract 5.x   

### 快速启动

```bash
# 1. 克隆启动项目
使用idea克隆项目并启动后端
# 2. 启动前端
cd knowledge-ui
pnpm install
pnpm dev

#3. 使用docker创建本地ollam后
手动拉取deepseek-r1:1.5b 和 nomic-embed-text
```

访问地址：
- 前端：http://localhost:5173
- 后端 API：http://localhost:8080
- API 文档：http://localhost:8080/doc.html

## 配置说明

主要配置项 (`application.yml`)：

```yaml
server:
  port: 8080

spring:
  # MySQL 配置
  datasource:
    url: jdbc:mysql://localhost:3306/knowledge_graph
    username: root
    password: 123456

  # Neo4j 配置
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: neo4j123

  # Ollama 配置
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: deepseek-r1:1.5b      # 对话模型
      embedding:
        model: nomic-embed-text       # 向量模型

# Milvus 向量数据库配置
milvus:
  host: localhost
  port: 19530
  collection-name: knowledge_vectors
  dimension: 768

# OCR 配置
ocr:
  data-path: /opt/homebrew/share/tessdata  # tessdata路径
  language: chi_sim+eng                     # 识别语言

# 文件存储配置
file:
  upload-path: ./uploads
  allowed-types: pdf,doc,docx,txt,md,png,jpg,jpeg,gif,bmp
```


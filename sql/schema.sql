-- ============================================================
-- mall-ai-assistant · ai-service 数据库初始化脚本
-- 目标库：mall_ai（与业务库 hm-mall 物理分离）
-- 适用：MySQL 8.0.33+
-- 用法：mysql -u root -p < schema.sql
-- 说明：DDL 与 docs-architecture.md 第 3.1 节逐字对齐，改动请同步文档
-- ============================================================

CREATE DATABASE IF NOT EXISTS `mall_ai`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `mall_ai`;

-- ------------------------------------------------------------
-- ① 知识库文档表
-- content_hash 唯一索引 = 入库幂等的唯一可靠手段（文件名会变、内容不会）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `kb_document` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `title`         VARCHAR(200) NOT NULL                COMMENT '文档标题',
  `source_type`   VARCHAR(20)  NOT NULL                COMMENT 'CATEGORY 商品参数 / POLICY 售后政策 / FAQ 常见问题 / MANUAL 操作手册',
  `source_url`    VARCHAR(500)                         COMMENT '来源地址或文件路径',
  `content_hash`  CHAR(64)     NOT NULL                COMMENT '正文 SHA-256，用于幂等去重',
  `chunk_count`   INT          NOT NULL DEFAULT 0      COMMENT '切片数量',
  `status`        TINYINT      NOT NULL DEFAULT 0      COMMENT '0 待处理 / 1 已入库 / 2 处理失败',
  `error_msg`     VARCHAR(500)                         COMMENT '失败原因',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hash` (`content_hash`),
  KEY `idx_type_status` (`source_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库文档';

-- ------------------------------------------------------------
-- ② 知识切片表（向量库的元数据索引）
-- 向量库只管相似度检索；按来源筛选、按文档删除、原文回显留在这里
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `kb_chunk` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `doc_id`      BIGINT      NOT NULL              COMMENT '所属文档',
  `chunk_index` INT         NOT NULL              COMMENT '文档内序号，从 0 开始',
  `content`     TEXT        NOT NULL              COMMENT '切片正文',
  `token_count` INT                               COMMENT '预估 token 数',
  `vector_id`   VARCHAR(80) NOT NULL              COMMENT '向量库主键',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_index` (`doc_id`, `chunk_index`),
  KEY `idx_vector` (`vector_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识切片';

-- ------------------------------------------------------------
-- ③ 会话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `session_id`  VARCHAR(64) NOT NULL              COMMENT '对外会话标识（UUID）',
  `user_id`     BIGINT                            COMMENT '关联 hmall 用户，匿名会话为空',
  `title`       VARCHAR(100)                      COMMENT '会话标题，取首条提问前 30 字',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`session_id`),
  KEY `idx_user_time` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话';

-- ------------------------------------------------------------
-- ④ 会话消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `session_id`  VARCHAR(64) NOT NULL,
  `role`        VARCHAR(16) NOT NULL              COMMENT 'user / assistant / tool / system',
  `content`     MEDIUMTEXT,
  `token_count` INT,
  `tool_name`   VARCHAR(64)                       COMMENT 'role=tool 时记录工具名',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话消息';

-- ------------------------------------------------------------
-- ⑤ 工具调用日志表（可观测性落点：成功率、平均耗时都从这张表算）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `tool_call_log` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `trace_id`      VARCHAR(64) NOT NULL            COMMENT '贯穿一次请求的链路 ID',
  `session_id`    VARCHAR(64),
  `tool_name`     VARCHAR(64) NOT NULL,
  `arguments`     TEXT                            COMMENT '入参 JSON',
  `result_digest` VARCHAR(500)                    COMMENT '结果摘要（截断），不存全量',
  `success`       TINYINT     NOT NULL DEFAULT 1,
  `error_msg`     VARCHAR(500),
  `cost_ms`       INT,
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trace` (`trace_id`),
  KEY `idx_tool_time` (`tool_name`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具调用日志';

-- ------------------------------------------------------------
-- 自检
-- ------------------------------------------------------------
SELECT TABLE_NAME, TABLE_COMMENT, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall_ai'
ORDER BY TABLE_NAME;

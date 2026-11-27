# SupportForge

SupportForge 是一个基于 Java 21 和 Spring Boot 的企业级智能客服项目，包含多 Agent 路由、混合检索、Grounding、会话记忆、知识管理、反馈评测、多租户权限和 React 管理工作台。

项目默认使用 Mock LLM 和本地存储，可以离线运行。企业模式可接入 OpenAI-compatible LLM、Embedding、Reranker、PostgreSQL、Qdrant、Kafka、Valkey 和 S3 兼容对象存储。

## 核心流程

```text
请求
  -> 可信身份与租户上下文
  -> 会话记忆与意图识别
  -> Agent 路由和业务规则
  -> Embedding + Qdrant 混合召回
  -> ACL + Reranker + 当前版本核验
  -> Grounding 证据准入
  -> LLM 生成
  -> 引用、数字和业务边界校验
  -> 回答、证据与检索轨迹
```

## 技术栈

- Java 21、Spring Boot 4、Maven
- PostgreSQL、Flyway、HikariCP
- Qdrant、Qwen3 Embedding、Qwen3 Reranker
- Kafka、Transactional Outbox
- Caffeine、Valkey
- S3-compatible object storage
- Micrometer、Prometheus、OpenTelemetry、Jaeger
- React、TypeScript、Vite、Nginx

## 主要能力

- 规则、向量和 LLM 融合意图识别
- 多 Agent 路由、并行处理和安全降级
- Dense + BM25 + RRF 混合召回
- 文档 ACL、当前版本核验和 Grounding 拒答
- 引用证据、检索 Trace 和回答 Guard
- PostgreSQL 会话、摘要、情景记忆和用户事实
- Caffeine/Valkey 两级缓存
- 知识上传、分页浏览、全文查看和幂等导入
- 反馈、评测、质量门禁和可观测性
- 多租户、RBAC、资源 ACL 和审计

## 本地运行

环境要求：

- JDK 21
- Maven 3.9+，也可使用仓库中的 Maven Wrapper
- Node.js 20+（仅前端需要）
- Docker Desktop（仅企业模式需要）

启动后端：

```powershell
./mvnw.cmd spring-boot:run
```

默认地址：`http://localhost:7000`。默认 Profile 使用 Mock LLM，不需要 API Key。

调用聊天接口：

```powershell
curl.exe -X POST "http://localhost:7000/chat" `
  -H "Content-Type: application/json" `
  -d '{"message":"订单A12345重复扣款99元，应该怎么办？","user_id":"demo-user"}'
```

## 前端

```powershell
cd frontend
npm ci
npm run dev
```

前端默认通过开发代理访问后端。生产镜像由 `frontend/Dockerfile` 构建。

## 自动化测试

后端：

```powershell
mvn verify
```

前端：

```powershell
cd frontend
npm ci
npm test
npm run build
```

## 企业模式

先创建本地配置：

```powershell
Copy-Item .env.example .env
./scripts/Initialize-LanAccess.ps1
```

在 `.env` 中配置真实模型和基础设施参数，然后启动完整环境：

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.enterprise.yml `
  -f docker-compose.frontend.yml `
  up --build -d
```

企业工作台默认地址：`http://localhost:3000`。

真实接线检查会写入演示数据并可能调用收费模型，请在确认配置后手动执行：

```powershell
./scripts/Test-EnterpriseWiring.ps1
```

## 配置和密钥

- 不要提交 `.env`。
- 不要提交 `data/lan-access/`、运行数据、备份或日志。
- `.env.example` 只包含示例值和空凭据。
- 正式环境应通过 Secret Manager、Kubernetes Secret 或 CI/CD Secret 注入密钥。
- LAN Token 仅用于本地演示，生产应接入 API Gateway、IdP 和 TLS。

## 目录结构

```text
src/main/java       后端源码
src/test/java       后端测试
src/main/resources  配置、Prompt 和 Flyway Migration
frontend            React/TypeScript 工作台
config              Prometheus 与 OpenTelemetry 配置
deploy              Kubernetes 部署模板
scripts             运维、迁移和验收脚本
skills              运行时客服 Skill
```

## 说明

- Qdrant、Valkey 和本地缓存都是可重建数据，PostgreSQL 是企业业务事实源。
- `escalated=true` 表示建议转人工，不代表已经接通真实呼叫中心。
- Compose 和 Kubernetes 文件是部署模板，正式上线前仍需完成 TLS、Secret、高可用、容量和灾备配置。

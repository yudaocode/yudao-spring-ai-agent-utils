# 芋道发布记录

## 0.14.0-jdk8-20261123

- 基于 Spring AI Community Agent Utils v0.14.0，固定上游提交 `1e9fc07e3dfbe7b1f0653c023a5efdee89fa658d`。
- 统一 Maven groupId 为 `cn.iocoder.spring-ai`，制品采用 `yudao-` 前缀，Java 包名沿用上游。
- Spring Boot 2.7.18 / Java 8。Spring AI 核心使用芋道已发布的 `2.1.0-jdk8-preview-20261119`。发布 common、tools 与 Docker CLI。
- Corretto 8 验证 38 项模块测试和 3 项独立消费测试，覆盖真实 Docker 工作区和容器回收。

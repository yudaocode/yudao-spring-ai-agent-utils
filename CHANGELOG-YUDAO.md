# 芋道发布记录

## 0.14.0-20261123

- 基于 Spring AI Community Agent Utils v0.14.0，固定上游提交 `1e9fc07e3dfbe7b1f0653c023a5efdee89fa658d`。
- 统一 Maven groupId 为 `cn.iocoder.spring-ai`，制品采用 `yudao-` 前缀，Java 包名沿用上游。
- Java 17+，基于 Spring AI 2.1.0。发布 common、tools、Docker CLI 与 A2A。
- JDK 17 模块测试 602 项通过，1 项按环境跳过。Boot 4.2.0 的真实 Docker 工具回合在 JDK 17、25 通过。

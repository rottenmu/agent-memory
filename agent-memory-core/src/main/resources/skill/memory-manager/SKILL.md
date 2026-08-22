---
name: memory-manager
description: 智能体记忆管理：通过 MCP 深度接管四层记忆（会话变量/用户长期记忆/全局记忆），支持写入、读取、删除与跨目标检索。当对话中需要记住用户偏好、历史事实、业务规则，或回答前需要查证历史记忆时使用。
version: 1.0.0
triggers:
  - 记忆写入
  - 记住
  - 回忆
  - 查询记忆
  - memory_write
  - memory_read
  - memory_delete
  - memory_search
---

# 记忆管理（MCP 深度接管模式）

本 Skill 将智能体记忆通过 MCP 工具暴露，由平台统一管理（H2 四层持久化 + 敏感脱敏 + 白名单）。

## 工具（经 MCP server `agent-memory` 注册）

| 工具 | 作用 | 关键参数 |
|---|---|---|
| `memory_write` | 写入记忆 | target(session/user/global)、sessionId、userId、category、key、content |
| `memory_read` | 读取记忆 | target、sessionId、userId、category、limit |
| `memory_delete` | 删除记忆 | target、sessionId、userId、key、id（key/id 空=清空，需确认） |
| `memory_search` | 跨目标检索 | query、userId、limit |

## 使用规则

1. **主动写入**：对话中了解到用户的偏好（preference）、画像（persona）、业务历史（history）或重要事实时，用 `memory_write` 主动保存：
   ```json
   {"target": "user", "userId": "admin", "category": "preference", "content": "偏好周报邮件"}
   ```
2. **先查后答**：回答涉及用户历史事实、业务规则前，用 `memory_search` 或 `memory_read` 检索相关记忆，避免凭空作答。
3. **会话级变量**：单次会话内流转的临时状态用 `target=session` + `sessionId` + `key/value` 保存。
4. **全局规则**：跨会话共享的业务规则用 `target=global` + `key/content` 保存。
5. **敏感保护**：内容中的手机号/身份证/银行卡/密钥会被自动脱敏（如 `138****5678`）；不要尝试绕过或回读原值。
6. **删除谨慎**：`memory_delete` 不传 key/id 会清空整个范围，操作前必须向用户二次确认。

## 调用示例（MCP tools/call）

```
工具: memory_write
参数: {"target":"user","userId":"zhangsan","category":"persona","content":"行政部员工，负责采购审批"}
```

```
工具: memory_search
参数: {"query":"周报","userId":"zhangsan","limit":10}
```

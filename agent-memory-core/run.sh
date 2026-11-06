#!/usr/bin/env bash
# ============================================================
# agent-memory 独立后端启动脚本（Windows Git Bash / Linux / macOS）
#
# 说明：
#   1. 显式 unset SERVER__PORT —— 该环境变量会被 Spring relaxed binding
#      映射为 server.port=0（随机端口），覆盖独立运行配置里的 9900。
#   2. --add-opens=java.base/java.nio=ALL-UNNAMED —— Arrow 内存访问所需
#      （arrow-memory-netty 在 classpath，属 unnamed module）。
#   2b. -Darrow.allocation.manager.type=Netty —— 必须显式指定。Arrow 17 未指定类型时
#       会走 CheckAllocator 扫 classpath，Spring Boot fat jar 的嵌套
#       BOOT-INF/lib/*.jar URL 让它算错类名，退化成加载
#       org.apache.arrow.memory.DefaultAllocationManagerFactory（少了 .netty 段）并抛
#       ClassNotFoundException，最终 olapAnalyticsRepository 装配失败、应用启动中止。
#   3. 启动时固定 CWD 到本模块目录，使 yml 里的 ./data/* 稳定落在
#      agent-memory-core/data/（否则数据文件位置取决于调用者所在目录）。
#   4. 必须显式激活 agentmemory profile —— 配置放在 application-agentmemory.yml，
#      避免被依赖 core 的主应用从 classpath 自动加载而污染其配置。
#   5. 可执行产物是带 exec classifier 的 jar（主构件仍是普通库 jar，供依赖方编译）。
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
JAR="$SCRIPT_DIR/target/agent-memory-core-1.0.0-exec.jar"

# 优先使用项目 JDK，其次系统 java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

if [ ! -f "$JAR" ]; then
  echo "[agent-memory] exec jar 不存在，先在 agent_runner 仓库根执行打包："
  echo "  ./mvnw -pl modules/agent-memory/agent-memory-core -am package -DskipTests"
  echo "  （产出 agent-memory-core/target/agent-memory-core-1.0.0-exec.jar）"
  exit 1
fi

PORT="${AGENT_MEMORY_PORT:-9900}"
echo "[agent-memory] 启动独立后端 on :$PORT (profile=agentmemory, jar: $JAR)"
# Git Bash 下 java 需要 Windows 风格路径；env -u 会阻断 MSYS 路径转换，改用显式转换
JAR_WIN="$(cygpath -w "$JAR" 2>/dev/null || echo "$JAR")"
exec env -u SERVER__PORT "$JAVA" \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -Darrow.allocation.manager.type=Netty \
  -jar "$JAR_WIN" \
  --spring.profiles.active=agentmemory \
  --server.port="$PORT" \
  "$@"

#!/usr/bin/env bash
# ============================================================
# agent-memory 独立后端启动脚本（Windows Git Bash / Linux / macOS）
#
# 说明：
#   1. 显式 unset SERVER__PORT —— 该环境变量会被 Spring relaxed binding
#      映射为 server.port=0（随机端口），覆盖 application.yml 的 9900。
#   2. --add-opens=java.base/java.nio=ALL-UNNAMED —— Arrow 内存访问所需
#      （arrow-memory-netty 在 classpath，属 unnamed module）。
#   3. 数据落模块目录 ./data（H2 文件 + Arrow IPC + MEMORY.md 兼容文件）。
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/agent-memory-application-1.0.0.jar"

# 优先使用项目 JDK，其次系统 java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

if [ ! -f "$JAR" ]; then
  echo "[agent-memory] jar 不存在，先执行打包："
  echo "  cd $SCRIPT_DIR/../.. && ./mvnw -pl modules/agent-memory/agent-memory-application -am package -DskipTests"
  exit 1
fi

PORT="${AGENT_MEMORY_PORT:-9900}"
echo "[agent-memory] 启动独立后端 on :$PORT (jar: $JAR)"
# Git Bash 下 java 需要 Windows 风格路径；env -u 会阻断 MSYS 路径转换，改用显式转换
JAR_WIN="$(cygpath -w "$JAR" 2>/dev/null || echo "$JAR")"
exec env -u SERVER__PORT "$JAVA" \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar "$JAR_WIN" \
  --server.port="$PORT" \
  "$@"

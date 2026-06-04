#!/bin/bash
# DeepAgent A2A 服务器 — 编译 + 启动
#
# 前置条件：
#   1. apiconfig.json 已配置 LLM 密钥
#   2. 根据需要启动对应的工作流服务
#
# WORKFLOW SERVICES (可选，按需启动):
#   bash examples/workflow_new/run_server.sh    (转账服务 :8080)
#   bash examples/workflow_a2a/run_server.sh    (理财/余额查询 :8081)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -W)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -W)"

cd "$PROJECT_DIR"

echo "=== 1. 编译项目核心库 ==="
mvn compile -DskipTests -q

echo "=== 2. 保存类路径 ==="
mkdir -p "$SCRIPT_DIR/target"
mvn dependency:build-classpath -Dmdep.outputFile="$SCRIPT_DIR/target/deep_a2a.classpath" -q

echo "=== 3. 编译示例 ==="
rm -rf "$SCRIPT_DIR/target/example-classes"
mkdir -p "$SCRIPT_DIR/target/example-classes"
CP="$PROJECT_DIR/target/classes;$(cat "$SCRIPT_DIR/target/deep_a2a.classpath")"
javac -cp "$CP" \
  -d "$SCRIPT_DIR/target/example-classes" \
  -sourcepath "$PROJECT_DIR/examples" \
  "$PROJECT_DIR/examples/deep_agent/DeepAgentA2AServer.java"

echo "=== 4. 启动 DeepAgent A2A Server ==="
echo ""

# Force UTF-8 locale to prevent garbled Chinese on Windows/WSL
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

JAVA_CP="$SCRIPT_DIR/target/example-classes;$PROJECT_DIR/target/classes;$PROJECT_DIR/examples;$(cat "$SCRIPT_DIR/target/deep_a2a.classpath")"

# Encoding fix: DeepAgentA2AServer's static initializer replaces stdout/stderr
# with raw UTF-8 PrintStreams, bypassing the system code page (GBK). The JVM
# flags below ensure file and console encoding are UTF-8 as well.
exec java \
  -Dfile.encoding=UTF-8 \
  -Dsun.stdout.encoding=UTF-8 \
  -Dsun.stderr.encoding=UTF-8 \
  -cp "$JAVA_CP" \
  examples.deep_agent.DeepAgentA2AServer \
  "$@"

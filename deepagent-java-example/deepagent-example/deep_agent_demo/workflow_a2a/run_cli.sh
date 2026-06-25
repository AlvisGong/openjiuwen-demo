#!/bin/bash
# Run the WorkflowA2A CLI client (auto-compile first)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_DIR"

echo "=== 1. 编译项目核心库 ==="
mvn compile -DskipTests -q

echo "=== 2. 保存类路径 ==="
mvn dependency:build-classpath -Dmdep.outputFile="$PROJECT_DIR/target/workflow_a2a.classpath" -q

echo "=== 3. 编译示例代码 ==="
CP="$PROJECT_DIR/target/classes:$(cat "$PROJECT_DIR/target/workflow_a2a.classpath")"
rm -rf "$PROJECT_DIR/target/example-classes"
mkdir -p "$PROJECT_DIR/target/example-classes"
# 编译 workflow_a2a 及其依赖的公共 util 类
javac -cp "$CP" \
  -d "$PROJECT_DIR/target/example-classes" \
  -sourcepath "$PROJECT_DIR/examples" \
  "$PROJECT_DIR/examples/workflow_a2a/"*.java \
  "$PROJECT_DIR/examples/utils/"*.java

echo "=== 4. 启动 WorkflowA2ACli ==="

# Resolve classpath (include examples/ for apiconfig.json resource loading)
RUN_CP="$PROJECT_DIR/target/classes:$PROJECT_DIR/target/example-classes:$PROJECT_DIR/examples:$(cat "$PROJECT_DIR/target/workflow_a2a.classpath")"

exec java -cp "$RUN_CP" examples.workflow_a2a.WorkflowA2ACli "$@"

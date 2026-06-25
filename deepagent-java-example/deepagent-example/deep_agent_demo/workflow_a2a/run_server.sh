#!/bin/bash
# Run the WorkflowA2A server (with optional compile skip and port specification)

# set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Default values
SKIP_COMPILE=false
PORT=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-compile|-s)
      SKIP_COMPILE=true
      shift
      ;;
    --port|-p)
      PORT="$2"
      shift 2
      ;;
    --help|-h)
      echo "Usage: $0 [OPTIONS]"
      echo "Options:"
      echo "  --skip-compile, -s    Skip compilation step"
      echo "  --port PORT, -p PORT  Specify server port (default: 8081)"
      echo "  --help, -h            Show this help message"
      exit 0
      ;;
    *)
      # If it's a number, treat it as port (backward compatibility)
      if [[ $1 =~ ^[0-9]+$ ]]; then
        PORT="$1"
        shift
      else
        echo "Unknown option: $1"
        echo "Use --help for usage information"
        exit 1
      fi
      ;;
  esac
done

cd "$PROJECT_DIR"

if [[ "$SKIP_COMPILE" == false ]]; then
  echo "=== 1. 编译项目核心库 ==="
  mvn compile -DskipTests -q

  echo "=== 2. 保存类路径 ==="
  mkdir -p "$SCRIPT_DIR/target"
  mvn dependency:build-classpath -Dmdep.outputFile="$SCRIPT_DIR/target/workflow_a2a.classpath" -q

  echo "=== 3. 编译示例代码 ==="
  CP="$PROJECT_DIR/target/classes:$(cat "$SCRIPT_DIR/target/workflow_a2a.classpath")"
  rm -rf "$SCRIPT_DIR/target/example-classes"
  mkdir -p "$SCRIPT_DIR/target/example-classes"
  # 编译 workflow_a2a 及其依赖的公共 util 类
  javac -cp "$CP" \
    -d "$SCRIPT_DIR/target/example-classes" \
    -sourcepath "$PROJECT_DIR/examples" \
    "$PROJECT_DIR/examples/workflow_a2a/"*.java \
    "$PROJECT_DIR/examples/utils/"*.java
else
  echo "=== 跳过编译步骤 ==="
fi

# Force UTF-8 locale to prevent garbled Chinese on Windows/WSL
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

echo "=== 4. 启动 WorkflowA2AServer ==="

# Resolve classpath (include examples/ for apiconfig.json resource loading)
RUN_CP="$PROJECT_DIR/target/classes:$SCRIPT_DIR/target/example-classes:$PROJECT_DIR/examples:$(cat "$SCRIPT_DIR/target/workflow_a2a.classpath")"

# Build Java command with optional port
PORT_ARG="${PORT:-}"
if [[ -n "$PORT_ARG" ]]; then
  echo "启动端口: $PORT_ARG"
else
  echo "使用默认端口: 8081"
fi
echo "类路径: $RUN_CP"
echo ""

# Encoding fix: WorkflowA2AServer's static initializer replaces stdout/stderr
# with raw UTF-8 PrintStreams, bypassing the system code page (GBK). The JVM
# flags below ensure file and console encoding are UTF-8 as well.
exec java \
  -Dfile.encoding=UTF-8 \
  -Dsun.stdout.encoding=UTF-8 \
  -Dsun.stderr.encoding=UTF-8 \
  -cp "$RUN_CP" \
  examples.workflow_a2a.WorkflowA2AServer \
  ${PORT_ARG}

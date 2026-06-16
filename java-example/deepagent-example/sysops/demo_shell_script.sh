#!/bin/bash
# demo_shell_script.sh — 示例脚本，供 ExecuteCmdExample 调用
# 功能：接收参数，输出系统信息、目录列表，并写入结果文件

echo "=== Demo Shell Script ==="
echo "Script path: $0"
echo "Arguments: $@"

# 输出当前工作目录
echo "Current directory: $(pwd)"

# 输出系统信息
echo "OS: $(uname -s 2>/dev/null || echo Windows)"
echo "Date: $(date 2>/dev/null || echo 'date not available')"

# 如果传入了目录参数，列出该目录
if [ -n "$1" ]; then
    echo "Listing directory: $1"
    ls -la "$1" 2>/dev/null || dir "$1" 2>/dev/null || echo "Cannot list: $1"
fi

# 如果传入了输出文件参数，写入结果
if [ -n "$2" ]; then
    echo "Script executed successfully at $(date 2>/dev/null || echo 'unknown time')" > "$2"
    echo "Result written to: $2"
fi

echo "=== Script Done ==="

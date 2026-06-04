#!/usr/bin/env python
"""
DeepAgent A2A CLI — 交互式 A2A JSON-RPC 客户端

用法:
  python examples/deep_agent/a2a_cli.py <command> [options]

命令:
  send      [--query TEXT]                调用 tasks/send (非流式)
  subscribe [--query TEXT] [--timeout N]  调用 tasks/sendSubscribe (流式 SSE)
  get       --task-id ID                  查询任务状态
  card                                    获取 Agent 能力声明
  interactive                              交互式模式 (默认)

选项:
  --server URL     A2A 服务器地址 (默认: http://localhost:8082)
  --task-id ID     任务 ID (自动生成)
  --query TEXT     用户输入文本
  --conv-id ID     会话 ID (多轮对话时使用)
  --timeout N      curl 超时秒数 (默认: 120)
  --help           显示帮助

示例:
  # 交互式模式
  python examples/deep_agent/a2a_cli.py

  # 单次调用
  python examples/deep_agent/a2a_cli.py send --query "我要转账"
  python examples/deep_agent/a2a_cli.py send --query "我想买点理财产品"
  python examples/deep_agent/a2a_cli.py send --query "请把图片缩小到 800x600"

  # 多轮对话
  python examples/deep_agent/a2a_cli.py send --task-id my_task --query "我要转账"
  python examples/deep_agent/a2a_cli.py send --task-id my_task --query "2000元" --conv-id my_task

  # 查询任务状态
  python examples/deep_agent/a2a_cli.py get --task-id my_task

  # 获取 Agent Card
  python examples/deep_agent/a2a_cli.py card
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Optional

# Force UTF-8 output on Windows to avoid garbled Chinese characters.
# Python on Windows defaults to the system code page (e.g., GBK/cp936),
# which cannot encode emoji or box-drawing characters.
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


# ─── 常量 ────────────────────────────────────────────────────────────────────

DEFAULT_SERVER = "http://localhost:8082"
DEFAULT_TIMEOUT = 600
VERSION = "1.0.0"


# ─── 工具函数 ────────────────────────────────────────────────────────────────

def pretty_json(data: Any) -> str:
    """将对象格式化为美观的 JSON 字符串。"""
    if isinstance(data, str):
        try:
            obj = json.loads(data)
        except (json.JSONDecodeError, ValueError):
            return data
        return json.dumps(obj, indent=2, ensure_ascii=False)
    return json.dumps(data, indent=2, ensure_ascii=False)


def print_section(title: str, content: str = "", color: str = "") -> None:
    """打印带分隔线的标题和内容。"""
    line = "─" * 60
    print()
    print(f" {title}")
    print(line)
    if content:
        print(content)


def http_post(url: str, body: dict, timeout: int = DEFAULT_TIMEOUT) -> dict:
    """HTTP POST 请求，返回反序列化的 JSON 响应。"""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"error": {"code": e.code, "message": raw}}
    except urllib.error.URLError as e:
        return {"error": {"code": 0, "message": f"连接失败: {e.reason}"}}


def http_get(url: str, timeout: int = DEFAULT_TIMEOUT) -> dict:
    """HTTP GET 请求，返回反序列化的 JSON 响应。"""
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"error": {"code": e.code, "message": raw}}
    except urllib.error.URLError as e:
        return {"error": {"code": 0, "message": f"连接失败: {e.reason}"}}



# ─── 核心 A2A 客户端 ─────────────────────────────────────────────────────────

class A2AClient:
    """A2A JSON-RPC 客户端。"""

    def __init__(self, server: str = DEFAULT_SERVER, timeout: int = DEFAULT_TIMEOUT):
        self.server = server.rstrip("/")
        self.timeout = timeout

    def _url(self, path: str) -> str:
        return f"{self.server}{path}"

    def send(self, task_id: str, query: str, conv_id: Optional[str] = None) -> dict:
        """调用 tasks/send。"""
        params: dict = {
            "id": task_id,
            "message": {
                "role": "user",
                "parts": [{"type": "text", "text": query}],
            },
        }
        if conv_id:
            params["metadata"] = {"conversation_id": conv_id}

        body = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tasks/send",
            "params": params,
        }

        print_section("Request", pretty_json(body))
        print()

        print(">>> Waiting for response ...")
        sys.stdout.flush()
        result = http_post(self._url("/tasks/send"), body, self.timeout)

        print_section("Response", pretty_json(result))
        return result

    def subscribe(self, task_id: str, query: str, conv_id: Optional[str] = None) -> None:
        """调用 tasks/sendSubscribe — 使用 SSE 流式读取，逐行输出。"""
        params: dict = {
            "id": task_id,
            "message": {
                "role": "user",
                "parts": [{"type": "text", "text": query}],
            },
        }
        if conv_id:
            params["metadata"] = {"conversation_id": conv_id}

        body = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tasks/sendSubscribe",
            "params": params,
        }

        print_section("Request", pretty_json(body))
        print()
        print(">>> SSE stream:")
        sys.stdout.flush()

        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self._url("/tasks/sendSubscribe"), data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                # 按行读取 SSE 流
                buffer = b""
                while True:
                    chunk = resp.read(4096)
                    if not chunk:
                        break
                    buffer += chunk
                    # 按行分割
                    while b"\n" in buffer:
                        line_bytes, buffer = buffer.split(b"\n", 1)
                        line = line_bytes.decode("utf-8", errors="replace").strip()
                        if not line:
                            continue
                        # 处理 event/data 行
                        if line.startswith("event: "):
                            print(f"  {line}")
                        elif line.startswith("data: "):
                            json_str = line[6:]
                            try:
                                obj = json.loads(json_str)
                                # 只打印 data 的内容，不再重复 event 信息
                                print(f"  {pretty_json(obj)}")
                            except (json.JSONDecodeError, ValueError):
                                print(f"  {line}")
                        else:
                            print(f"  {line}")
                        sys.stdout.flush()
                # buffer 剩余内容
                if buffer:
                    tail = buffer.decode("utf-8", errors="replace").strip()
                    if tail:
                        print(f"  {tail}")
                        sys.stdout.flush()
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            print(f"  [HTTP {e.code}] {raw}")
        except urllib.error.URLError as e:
            print(f"  [连接失败] {e.reason}")
        print()

    def get(self, task_id: str) -> dict:
        """调用 tasks/get 查询任务状态。"""
        body = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tasks/get",
            "params": {"id": task_id},
        }
        print(f">>> 查询任务状态: {task_id}")
        result = http_post(self._url("/tasks/get"), body, self.timeout)
        print_section("Response", pretty_json(result))
        return result

    def card(self) -> dict:
        """获取 Agent Card。"""
        url = self._url("/.well-known/agent-card")
        print(f">>> Agent Card: {url}")
        result = http_get(url, self.timeout)
        print_section("Response", pretty_json(result))
        return result


# ─── 交互式模式 ──────────────────────────────────────────────────────────────

def interactive_mode(client: A2AClient) -> None:
    """交互式多轮对话 CLI。"""
    print()
    print("╔══════════════════════════════════════════════════════════╗")
    print("║        DeepAgent A2A Interactive CLI v{}          ║".format(VERSION))
    print("║        Server: {:<36s} ║".format(client.server))
    print("╠══════════════════════════════════════════════════════════╣")
    print("║  输入指令:                                             ║")
    print("║    send <query>       发送消息                          ║")
    print("║    subscribe <query>  发送消息 (SSE 流式)              ║")
    print("║    get <task-id>      查询任务                          ║")
    print("║    card               获取 Agent Card                   ║")
    print("║    new                新建会话                          ║")
    print("║    help               显示帮助                          ║")
    print("║    exit / quit        退出                              ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print()

    # 当前会话状态
    current_task_id: Optional[str] = None
    current_conv_id: Optional[str] = None

    while True:
        # 显示当前会话信息
        if current_task_id:
            # 用 task_id 的简短后缀作为提示
            short_id = current_task_id[-12:] if len(current_task_id) > 12 else current_task_id
            prompt_prefix = f"[{short_id}]"
        else:
            prompt_prefix = "[new]"

        try:
            raw = input(f"\n{prompt_prefix} >>> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            print("再见！")
            break

        if not raw:
            continue

        parts = raw.split(maxsplit=1)
        cmd = parts[0].lower()
        arg = parts[1] if len(parts) > 1 else ""

        if cmd in ("exit", "quit", "q"):
            print("再见！")
            break

        if cmd == "help":
            print("╔══ 命令列表 ═══════════════════════════════════════════╗")
            print("║  send <text>         发送消息到当前会话              ║")
            print("║  subscribe <text>    发送消息 (SSE 流式)             ║")
            print("║  get <task-id>       查询指定任务状态                ║")
            print("║  card                获取 Agent Card                 ║")
            print("║  new                 新建会话 (重置 task_id)         ║")
            print("║  help                显示帮助                        ║")
            print("║  exit / quit / q     退出                            ║")
            print("╚══════════════════════════════════════════════════════╝")
            continue

        if cmd == "new":
            current_task_id = None
            current_conv_id = None
            print(">>> 已新建会话")
            continue

        if cmd == "card":
            client.card()
            continue

        if cmd == "get":
            if not arg:
                if current_task_id:
                    client.get(current_task_id)
                else:
                    print("!! 请指定 task-id: get <task_id>")
            else:
                client.get(arg)
            continue

        if cmd in ("send", "subscribe"):
            if not arg:
                print("!! 请输入消息内容: send <your message>")
                continue

            # 如果没有活跃会话，创建新任务 ID
            if current_task_id is None:
                current_task_id = f"interactive_{int(time.time())}"

            # 自动传递 conversation_id 实现多轮
            result = client.send(current_task_id, arg, current_conv_id)

            # 从响应中提取 conversation_id 和状态
            res = result.get("result", {})
            status = res.get("status", "unknown")
            metadata = res.get("metadata", {})

            # 尝试从响应中提取 conversation_id
            conv_from_resp = metadata.get("conversation_id")
            if conv_from_resp:
                current_conv_id = conv_from_resp
            elif current_conv_id is None:
                # 首次对话，用 task_id 作为 conv_id
                current_conv_id = current_task_id

            # 提取助手消息并打印摘要
            messages = res.get("messages", [])
            if messages:
                last_msg = messages[-1]
                if last_msg.get("role") == "agent":
                    parts_text = last_msg.get("parts", [])
                    for part in parts_text:
                        if part.get("type") == "text":
                            print()
                            print(f"  🤖 {part['text']}")
                            print()

            print(f">>> 状态: {status}  |  会话: {current_conv_id}")
            if status == "input-required":
                print(">>> 需要补充信息，直接输入即可继续对话")
            elif status == "completed":
                print(">>> 任务已完成")
            continue

        # 未知命令 — 当做 send 处理
        print(f"!! 未知命令 '{cmd}'，请使用 send / subscribe / get / card / new / help")
        print(f"   如果想把 '{cmd}' 作为消息发送，请用: send {cmd} {arg}")


# ─── 主入口 ──────────────────────────────────────────────────────────────────

def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="a2a_cli.py",
        description="DeepAgent A2A CLI — 交互式 A2A JSON-RPC 客户端",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 交互式模式
  python examples/deep_agent/a2a_cli.py

  # 单次调用
  python examples/deep_agent/a2a_cli.py send "我要转账"
  python examples/deep_agent/a2a_cli.py send --query "我要转账"
  python examples/deep_agent/a2a_cli.py get --task-id my_task
  python examples/deep_agent/a2a_cli.py card
        """,
    )
    parser.add_argument("command", nargs="?", default="interactive",
                        choices=["send", "subscribe", "get", "card", "interactive"],
                        help="命令: send / subscribe / get / card / interactive")
    parser.add_argument("query_text", nargs="?",
                        help="用户输入文本 (send/subscribe 命令的快捷方式，等价于 --query)")
    parser.add_argument("--server", default=DEFAULT_SERVER,
                        help=f"A2A 服务器地址 (默认: {DEFAULT_SERVER})")
    parser.add_argument("--task-id", default="",
                        help="任务 ID (默认自动生成)")
    parser.add_argument("--query", default="",
                        help="用户输入文本")
    parser.add_argument("--conv-id", default="",
                        help="会话 ID (多轮对话时使用)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help=f"HTTP 超时秒数 (默认: {DEFAULT_TIMEOUT})")
    return parser.parse_args(argv)


def main() -> None:
    args = parse_args(sys.argv[1:])

    client = A2AClient(server=args.server, timeout=args.timeout)

    if args.command == "interactive":
        interactive_mode(client)
        return

    # 单次命令模式
    if args.command == "card":
        client.card()
        return

    if args.command == "get":
        if not args.task_id:
            print("!! 错误: get 命令需要 --task-id 参数")
            sys.exit(1)
        client.get(args.task_id)
        return

    if args.command in ("send", "subscribe"):
        query = args.query or args.query_text
        if not query:
            print("!! 错误: send/subscribe 命令需要 --query 参数或直接提供查询文本")
            sys.exit(1)

        task_id = args.task_id or f"cli_{int(time.time())}"
        conv_id = args.conv_id or None

        if args.command == "send":
            client.send(task_id, query, conv_id)
        else:
            client.subscribe(task_id, query, conv_id)


if __name__ == "__main__":
    main()

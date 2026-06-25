#!/usr/bin/env python
"""余额查询 CLI — 发送单次查询，返回结构化 JSON 供 DeepAgent 决策"""
import argparse, json, urllib.request, urllib.error, sys, time

A2A_SERVER = "http://localhost:8081"
DEFAULT_TIMEOUT = 60


def _a2a_send(server: str, query_text: str, conv_id: str = None) -> dict:
    """发送 A2A tasks/send 请求"""
    task_id = f"bal_{int(time.time() * 1000)}"
    params = {
        "id": task_id,
        "message": {"role": "user", "parts": [{"type": "text", "text": query_text}]},
    }
    if conv_id:
        params["metadata"] = {"conversation_id": conv_id}

    body = {"jsonrpc": "2.0", "id": 1, "method": "tasks/send", "params": params}
    data = json.dumps(body).encode()
    url = f"{server.rstrip('/')}/tasks/send"
    req = urllib.request.Request(url, data, {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        return json.loads(raw) if raw else {"error": str(e)}


def _extract_text(response: dict) -> str:
    """从 A2A 响应中提取 agent 回复文本"""
    try:
        msgs = response.get("result", {}).get("messages", [])
        for m in msgs:
            if m.get("role") == "agent":
                parts = m.get("parts", [])
                for p in parts:
                    if p.get("type") == "text":
                        return p.get("text", "")
    except Exception:
        pass
    return str(response)


def _get_conv_id(response: dict) -> str:
    """从 A2A 响应中提取 conversation_id"""
    meta = response.get("result", {}).get("metadata", {})
    return meta.get("conversation_id", "")


def query_once(query_text: str, server: str = A2A_SERVER,
               conversation_id: str = None) -> dict:
    """发送单次查询，透传 A2A 协议状态和关键参数

    返回格式直接反映 A2A 协议响应中的关键字段：
    { "status": "completed"|"input-required"|"failed"|"working",
      "conversation_id": "...",
      "node_id": "...",        # status=input-required 时存在
      "result": "agent 回复文本" }
    """
    resp = _a2a_send(server, query_text, conversation_id)
    task = resp.get("result", resp)
    status = task.get("status", "failed")
    conv_id = _get_conv_id(resp)
    text = _extract_text(resp)
    meta = task.get("metadata", {})
    node_id = meta.get("node_id", "")

    return {
        "status": status,
        "conversation_id": conv_id or "",
        "node_id": node_id or "",
        "result": text or str(resp),
    }


def main():
    parser = argparse.ArgumentParser(
        description="余额查询 — 发送查询并返回结构化 JSON")
    parser.add_argument("--query", required=True, help="查询内容")
    parser.add_argument("--conversation_id", default=None,
                        help="多轮会话 ID（续对话时传入）")
    args = parser.parse_args()

    output = query_once(args.query, A2A_SERVER, args.conversation_id)
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

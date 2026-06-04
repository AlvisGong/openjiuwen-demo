#!/usr/bin/env python
"""转账 CLI — 一键转账（基于 A2A 协议）"""
import argparse, json, urllib.request, urllib.error, sys, time

A2A_SERVER = "http://localhost:8080"
DEFAULT_TIMEOUT = 60


def _a2a_send(server: str, query_text: str, conv_id: str = None,
              node_id: str = None) -> dict:
    """发送 A2A tasks/send 请求"""
    task_id = f"trf_{int(time.time() * 1000)}"
    params = {
        "id": task_id,
        "message": {"role": "user", "parts": [{"type": "text", "text": query_text}]},
    }
    metadata = {}
    if conv_id:
        metadata["conversation_id"] = conv_id
    if node_id:
        metadata["node_id"] = node_id
    if metadata:
        params["metadata"] = metadata

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


def transfer(to: str, amount: str, server: str = A2A_SERVER) -> dict:
    """转账，通过 A2A 协议自动处理两轮交互"""
    query = f"我要给{to}转账" if to else "我要转账"

    # 第1轮：发起转账请求
    r1 = _a2a_send(server, query)
    status = r1.get("result", {}).get("status", "failed")
    text = _extract_text(r1)
    conv_id = _get_conv_id(r1)

    if status == "completed":
        return {"status": "completed", "result": text}

    if status == "input-required" and conv_id:
        # 第2轮：补充金额，从 node_id 字段获取
        meta = r1.get("result", {}).get("metadata", {})
        node_id = meta.get("node_id", "")
        r2 = _a2a_send(server, amount, conv_id, node_id)
        text = _extract_text(r2)
        return {"status": "completed", "result": text}

    return {"status": "failed", "result": text or str(r1)}


def main():
    parser = argparse.ArgumentParser(description="执行转账")
    parser.add_argument("--to", required=True, help="收款人（如 张三）")
    parser.add_argument("--amount", required=True, help="转账金额（如 100 或 100元）")
    args = parser.parse_args()

    output = transfer(args.to, args.amount, A2A_SERVER)
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

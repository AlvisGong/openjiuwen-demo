#!/usr/bin/env python
"""理财产品 CLI — 一键购买理财产品"""
import argparse, json, urllib.request, urllib.error, sys, time

A2A_SERVER = "http://localhost:8083"
DEFAULT_TIMEOUT = 60


def _a2a_send(server: str, query: str, conv_id: str = None) -> dict:
    """发送 A2A tasks/send 请求"""
    task_id = f"fin_{int(time.time() * 1000)}"
    params = {
        "id": task_id,
        "message": {"role": "user", "parts": [{"type": "text", "text": query}]},
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


def _unpack_result(text: str) -> str:
    """如果响应是 JSON 包裹的，解包出 response 字段"""
    import json as _json
    text = text.strip()
    if text.startswith("{"):
        try:
            obj = _json.loads(text)
            if "response" in obj:
                return obj["response"]
        except Exception:
            pass
    return text


def buy_product(product: str, server: str = A2A_SERVER) -> dict:
    """购买理财产品，自动处理两轮交互"""
    r1 = _a2a_send(server, "我要理财")
    conv_id = _get_conv_id(r1)
    if conv_id:
        r2 = _a2a_send(server, product, conv_id)
        text = _unpack_result(_extract_text(r2))
    else:
        text = _unpack_result(_extract_text(r1))
    return {"status": "completed", "result": text}


def main():
    parser = argparse.ArgumentParser(description="购买理财产品")
    parser.add_argument("--product", required=True, help="理财产品名称（如 稳健理财）")
    args = parser.parse_args()

    output = buy_product(args.product, A2A_SERVER)
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()

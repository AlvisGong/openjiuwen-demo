# coding: utf-8
"""
Funciton工具示例

"""
from openjiuwen.core.foundation.tool import tool

@tool(
    name="add",
    description="本地加法插件",
    input_params={
        "type": "object",
        "properties": {
            "a": {
                "type": "integer",
                "description": "第一个参数"
            },
            "b": {
                "type": "integer",
                "description": "第二个参数"
            }
        },
        "required": ["a", "b"]
    }
)
def add(a: int, b: int) -> int:
    return a + b

# 生成Tool类，可被LLM识别与调用
import asyncio
tool_info = add.card.tool_info()
print(f"调用成功，返回结果: {tool_info}")

inputs = {
    "a": 5,
    "b": 1
}
result = asyncio.run(add.invoke(inputs=inputs))
print(f"调用成功，返回结果: {result}")
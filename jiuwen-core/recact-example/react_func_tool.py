import os, sys
sys.path.insert(0,os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE,API_KEY,MODEL_NAME,MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")

from openjiuwen.core.foundation.llm import ModelRequestConfig, ModelClientConfig

model_config = ModelRequestConfig(
    model=MODEL_NAME,
    temperature=0.6,
    top_p=0.8,
)
model_client = ModelClientConfig(
    client_provider=MODEL_PROVIDER,
    api_base=API_BASE,
    api_key=API_KEY,
    verify_ssl=False
)

def create_prompt_template():
    system_prompt = "你是一个AI助手，在适当的时候调用合适的工作流，帮助我完成任务！注意：只需要调用一次工作流后就进行总结，不要重复调用！"
    return [
        dict(role="system", content=system_prompt)
    ]

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

@tool(
    name="subtract",
    description="本地减法插件",
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
def subtract(a: int, b: int) -> int:
    return a - b 

@tool(
    name="multiply",
    description="本地乘法插件",
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
def multiply(a: int, b: int) -> int:
    return a * b 

@tool(
    name="divide",
    description="本地除法插件",
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
def divide(a: int, b: int) -> int:
    return a / b 

from openjiuwen.core.single_agent import AgentCard, ReActAgentConfig, ReActAgent
from openjiuwen.core.runner import Runner

agent_card = AgentCard(
        id="react_agent_1234",
        description="计算器助手",
    )
prompt_template = create_prompt_template()
react_agent_config = ReActAgentConfig(
    model_client_config=model_client,
    model_config_obj=model_config,
    prompt_template=prompt_template
)
react_agent = ReActAgent(card=agent_card).configure(react_agent_config)

Runner.resource_mgr.add_tool(add)
react_agent.ability_manager.add(add.card)
Runner.resource_mgr.add_tool(subtract)
react_agent.ability_manager.add(subtract.card)
Runner.resource_mgr.add_tool(multiply)
react_agent.ability_manager.add(multiply.card)
Runner.resource_mgr.add_tool(divide)
react_agent.ability_manager.add(divide.card)

import asyncio

query = '''
你是一个专业的数学计算助手。
当用户提出数学问题时，你需要：
1. 理解问题中的数学表达式
2. 使用提供的计算器工具进行计算
3. 给出清晰的计算过程和结果
4. 对于复杂表达式，需要分步计算
				
计算 100 / 4 + 25 * 2 的结果
'''

async def main():
    result = await Runner.run_agent(agent=react_agent,
                                    inputs={"query": query, "conversation_id": "013"})
    print(result)

if __name__ == "__main__":
    asyncio.run(main())
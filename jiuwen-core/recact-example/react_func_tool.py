import os, sys
from datetime import datetime

# 如需把项目根目录加入 PYTHONPATH
sys.path.insert(0, os.path.dirname(os.path.abspath('__file__')))
os.environ.setdefault("LLM_SSL_VERIFY", "false")

API_BASE = "https://api.modelarts-maas.com/openai/v1";
API_KEY = "TdjVSA2nqjP8pQRpf-A5YVCSjgLx9FabIBxyN9B5TI7ZdcTMyhSxmzXzzyPr6xEWOylP7SR4BI8OAPsJwQYtfg";
MODEL_NAME = "glm-5";
MODEL_PROVIDER = "OpenAI";
WEATHER_URL = "http://127.0.0.1:8000/"

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

import asyncio

async def main():
    result = await Runner.run_agent(agent=react_agent,
                                    inputs={"query": "使用本地加法插件计算1+1", "conversation_id": "013"})
    print(result)

if __name__ == "__main__":
    asyncio.run(main())
    # await main()
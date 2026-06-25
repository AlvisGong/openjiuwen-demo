# React Agent使用API Server 工具示例

## 前置准备
- 安装openjiuwen agent-core
> pip install openjiuwen

- 准备模型信息
> 参考样例：
> 
> API_BASE = "https://coding.dashscope.aliyuncs.com/v1";
> 
> API_KEY = "************************";
> 
> MODEL_NAME = "MiniMax-M2.5";
> 
> MODEL_PROVIDER = "OpenAI";

- 准备天气服务
> 使用flask提供天气API服务，用来查询天气信息.
> 
> 代码参考：https://github.com/AlvisGong/openjiuwen-demo/blob/main/jiuwen-core/tools/weather_server.py
>
> 服务启动：python tools/weather_server.py

## React Agent调AIP工具样例构建

### 1. 定义模型配置
```python
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
```

### 2. 定义天气API工具
```python
from openjiuwen.core.foundation.tool import RestfulApiCard, RestfulApi

WEATHER_URL = "http://127.0.0.1:8000/"

def create_tool():
    weather_card = RestfulApiCard(
            id="weather_tool",
            name="WeatherReporter",
            description="天气查询插件",
            input_params={
                "type": "object",
                "properties": {
                    "location": {"description": "地点", "type": "string"},
                    "date": {"description": "日期", "type": "string"},
                },
                "required": ["location", "date"],
            },
            url=WEATHER_URL,
            headers={},
            method="GET",
        )
    weather_tool = RestfulApi(
        card=weather_card,
    )

    return weather_tool
```

### 3. 定义ReActAgent
```python
from openjiuwen.core.single_agent import AgentCard, ReActAgentConfig, ReActAgent
from openjiuwen.core.runner import Runner

agent_card = AgentCard(
        id="react_agent_1234",
        description="天气查询助手",
    )

prompt_template = create_prompt_template()

react_agent_config = ReActAgentConfig(
    model_client_config=model_client,
    model_config_obj=model_config,
    prompt_template=prompt_template
)
react_agent = ReActAgent(card=agent_card).configure(react_agent_config)

```

### 4. 注册工具到agent
```python
tool = create_tool()
Runner.resource_mgr.add_tool(tool)
react_agent.ability_manager.add(tool.card)
```

### 5. 启动agent
```python
import asyncio

async def main():
    result = await Runner.run_agent(agent=react_agent,
                                    inputs={"query": "查询一下beijing 2026-04-01这天的天气", "conversation_id": "013"})
    print(result)

if __name__ == "__main__":
    asyncio.run(main())
```
> 代码实现参考：https://github.com/AlvisGong/openjiuwen-demo/blob/main/jiuwen-core/recact-example/react_api_tool.py

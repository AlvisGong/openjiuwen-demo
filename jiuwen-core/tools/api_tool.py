from openjiuwen.core.foundation.tool import RestfulApi, RestfulApiCard
import asyncio

weather_plugin = RestfulApi(
    card=RestfulApiCard(
        id="WeatherReporter-1",
        name="WeatherReporter",
        description="天气查询插件",
        input_params={
            "type": "object",
            "properties": {
                "location":  {"description": "天气查询的地点，必须为英文", "type": "string"},
                "date":  {"description": "天气查询的时间，格式为YYYY-MM-DD", "type": "string"},
            },
            "required": ["location", "date"],
        },
        url="http://127.0.0.1:8000",
        headers={},
        method="GET",
    ),
)

# RestfulApi实例，可被LLM识别与调用
tool_info = weather_plugin.card.tool_info()
print(f"调用成功，返回结果: {tool_info}")

inputs = {
    "location": "beijing",
    "date": "2026-04-15"
}
loop = asyncio.get_event_loop()
result = loop.run_until_complete(weather_plugin.invoke(inputs=inputs))
print(f"调用成功，返回结果: {result}")
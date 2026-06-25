# weather_server.py
"""
基于 FastMCP 的天气查询 MCP 服务器

该服务器提供天气查询工具，供 MCPTool 客户端调用。
使用 SSE (Server-Sent Events) 作为传输协议。

运行方式：
    python mcp_server.py

服务器会在 http://127.0.0.1:8002/sse 提供服务
"""

import httpx
from mcp.server.fastmcp import FastMCP

# 配置日志
import logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# 1. 初始化 MCP 服务器，设置名称和端口
mcp = FastMCP("WeatherMCPServer", host="127.0.0.1", port=8002)


@mcp.tool()
async def get_weather(location: str) -> str:
    """
    查询指定城市的天气信息。返回城市的当前天气状况，包括温度、湿度、天气状况等。

    Args:
        location: 城市名称，可以是中文（如"北京"）或英文（如"beijing"）

    Returns:
        天气信息字符串
    """
    logger.info(f"收到天气查询请求，location: {location}")

    # 根据城市返回模拟天气数据
    weather_data = _get_mock_weather(location)

    result = f"""城市: {location}
天气状况: {weather_data['condition']}
温度: {weather_data['temperature']}°C
湿度: {weather_data['humidity']}%
风速: {weather_data['wind_speed']} km/h
更新时间: {weather_data['update_time']}"""

    return result


def _get_mock_weather(location: str) -> dict:
    """
    获取模拟天气数据

    Args:
        location: 城市名称

    Returns:
        天气数据字典
    """
    # 根据城市返回不同的模拟天气数据
    weather_map = {
        "北京": {"condition": "晴", "temperature": 25, "humidity": 45, "wind_speed": 12},
        "beijing": {"condition": "晴", "temperature": 25, "humidity": 45, "wind_speed": 12},
        "上海": {"condition": "多云", "temperature": 28, "humidity": 60, "wind_speed": 8},
        "shanghai": {"condition": "多云", "temperature": 28, "humidity": 60, "wind_speed": 8},
        "广州": {"condition": "小雨", "temperature": 32, "humidity": 85, "wind_speed": 5},
        "guangzhou": {"condition": "小雨", "temperature": 32, "humidity": 85, "wind_speed": 5},
        "深圳": {"condition": "晴", "temperature": 30, "humidity": 70, "wind_speed": 10},
        "shenzhen": {"condition": "晴", "temperature": 30, "humidity": 70, "wind_speed": 10},
    }

    # 根据城市名称查找天气数据，不区分大小写
    location_lower = location.lower()
    for city, data in weather_map.items():
        if city.lower() == location_lower:
            return {
                "condition": data["condition"],
                "temperature": data["temperature"],
                "humidity": data["humidity"],
                "wind_speed": data["wind_speed"],
                "update_time": "2026-04-16 12:00"
            }

    # 默认天气数据
    return {
        "condition": "晴",
        "temperature": 20,
        "humidity": 50,
        "wind_speed": 10,
        "update_time": "2026-04-16 12:00"
    }


# 2. 运行服务器，transport='sse' 表示通过 SSE 协议运行
if __name__ == "__main__":
    logger.info("启动 Weather MCP Server...")
    logger.info("服务器地址: http://127.0.0.1:8002")
    logger.info("SSE 端点: http://127.0.0.1:8002/sse")
    mcp.run(transport='sse')
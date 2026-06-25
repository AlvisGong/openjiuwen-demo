from flask import Flask, request, jsonify
from datetime import datetime
import re

app = Flask(__name__)

def is_english_text(text: str) -> bool:
    """检查字符串是否只包含英文字母、空格和连字符（允许常见城市名如 New-York）"""
    return bool(re.fullmatch(r'^[a-zA-Z\s\-]+$', text))

def validate_date(date_str: str) -> bool:
    """验证日期格式是否为 YYYY-MM-DD"""
    try:
        datetime.strptime(date_str, '%Y-%m-%d')
        return True
    except ValueError:
        return False

@app.route('/', methods=['GET'])
def get_weather():
    # 获取查询参数
    location = request.args.get('location')
    date = request.args.get('date')

    # 验证 required 字段
    if not location:
        return jsonify({"error": "Missing required parameter: location"}), 400
    if not date:
        return jsonify({"error": "Missing required parameter: date"}), 400

    # 验证 location 必须为英文
    if not is_english_text(location):
        return jsonify({"error": "location must contain only English letters, spaces, or hyphens"}), 400

    # 验证 date 格式
    if not validate_date(date):
        return jsonify({"error": "date must be in YYYY-MM-DD format"}), 400

    # 模拟天气数据（实际可调用真实 API 或数据库）
    # 为了演示，简单根据 location 的哈希值返回一些伪随机但稳定的数据
    import hashlib
    seed = int(hashlib.md5(f"{location}_{date}".encode()).hexdigest()[:8], 16)
    temp = (seed % 30) + 5  # 温度范围 5~34 摄氏度
    conditions = ["Sunny", "Cloudy", "Rainy", "Windy", "Snowy"]
    condition = conditions[seed % len(conditions)]

    response = {
        "location": location,
        "date": date,
        "temperature_celsius": temp,
        "condition": condition,
        "humidity_percent": (seed % 60) + 30,  # 30~89%
        "wind_speed_kmh": (seed % 50) + 5      # 5~54 km/h
    }
    return jsonify(response), 200

if __name__ == '__main__':
    # 监听 8000 端口，允许外部访问（若需仅本地可改为 host='127.0.0.1'）
    app.run(host='127.0.0.1', port=8000, debug=True)
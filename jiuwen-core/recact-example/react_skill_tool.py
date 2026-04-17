# coding: utf-8
"""
react agent 调用 Skill 工具示例

运行前请确保：
1. 已安装 openjiuwen 包
2. 配置正确的 API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER
3. skill 文件存在于指定路径

Skill 工具本质上是一个 Function Tool，封装了读取 skill 内容并执行的能力。
"""
import os, sys
import io

# 修复 Windows 控制台中文编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER

os.environ.setdefault("LLM_SSL_VERIFY", "false")

# Skill 路径配置
SKILL_BASE_PATH = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PYTHON_REVIEW_SKILL_PATH = os.path.join(SKILL_BASE_PATH, "tools", "skills", "python-review", "SKILL.md")

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
    system_prompt = """你是一个AI助手，在适当的时候调用合适的工作流，帮助我完成任务！
注意：只需要调用一次工作流后就进行总结，不要重复调用！
当用户请求代码评审时，请使用 python_review 工具进行专业的代码分析。"""
    return [
        dict(role="system", content=system_prompt)
    ]


def load_skill_content(skill_path: str) -> str:
    """加载 skill 文件内容"""
    if not os.path.exists(skill_path):
        raise FileNotFoundError(f"Skill 文件不存在: {skill_path}")
    with open(skill_path, 'r', encoding='utf-8') as f:
        return f.read()


def review_python_code(code_content: str, file_path: str = None) -> str:
    """
    执行 Python 代码评审

    根据 skill 指令对代码进行专业评审，输出问题列表和改进建议。
    """
    # 加载 skill 内容
    skill_content = load_skill_content(PYTHON_REVIEW_SKILL_PATH)

    # 构建评审提示
    review_prompt = f"""请根据以下评审指南对代码进行专业评审：

{skill_content}

---

待评审的代码：
文件路径: {file_path or '未指定'}

```python
{code_content}
```

请按照评审指南的输出格式，生成完整的代码评审报告。"""

    # 使用 LLM 执行评审
    from openjiuwen.core.foundation.llm import Model

    model = Model(
        model_config=model_config,
        client_config=model_client
    )

    import asyncio
    messages = [
        {"role": "system", "content": "你是一位专业的 Python 代码评审专家，请严格按照评审指南进行代码分析。"},
        {"role": "user", "content": review_prompt}
    ]

    result = asyncio.run(model.invoke(messages=messages))

    if hasattr(result, 'content'):
        return result.content
    elif isinstance(result, dict):
        return result.get('content', str(result))
    return str(result)


from openjiuwen.core.foundation.tool import tool

@tool(
    name="python_review",
    description="Python 代码评审工具。用于对 Python 代码进行专业评审，识别代码质量问题、潜在Bug、性能问题、安全漏洞等。输入代码内容或文件路径，输出评审报告。",
    input_params={
        "type": "object",
        "properties": {
            "code_content": {
                "type": "string",
                "description": "待评审的 Python 代码内容"
            },
            "file_path": {
                "type": "string",
                "description": "代码文件路径（可选，用于报告中标注位置）"
            }
        },
        "required": ["code_content"]
    }
)
def python_review(code_content: str, file_path: str = None) -> str:
    """Python 代码评审工具"""
    return review_python_code(code_content, file_path)


from openjiuwen.core.single_agent import AgentCard, ReActAgentConfig, ReActAgent
from openjiuwen.core.runner import Runner

import asyncio


async def create_agent():
    """创建 ReAct Agent"""
    agent_card = AgentCard(
        id="react_skill_agent_001",
        description="代码评审助手",
    )
    prompt_template = create_prompt_template()

    react_agent_config = ReActAgentConfig(
        model_client_config=model_client,
        model_config_obj=model_config,
        prompt_template=prompt_template
    )

    react_agent = ReActAgent(card=agent_card).configure(react_agent_config)

    # 注册 Skill 工具
    Runner.resource_mgr.add_tool(python_review)
    react_agent.ability_manager.add(python_review.card)

    print(f"已注册 Skill 工具: python_review")
    print(f"Skill 路径: {PYTHON_REVIEW_SKILL_PATH}")

    return react_agent


# 示例代码，用于演示评审功能
SAMPLE_CODE = '''
def calculate_total(items):
    total = 0
    for item in items:
        total = total + item["price"] * item["quantity"]
    return total

def process_user_input(user_id):
    query = "SELECT * FROM users WHERE id = " + user_id
    return execute_query(query)

def read_file(filename):
    f = open(filename, 'r')
    content = f.read()
    f.close()
    return content

def divide_numbers(a, b):
    return a / b

class DataProcessor:
    def __init__(self):
        self.data = []

    def add_data(self, data):
        self.data.append(data)

    def process(self):
        results = []
        for d in self.data:
            if d != None:
                results.append(d * 2)
        return results
'''


async def main():
    """主函数"""
    # 创建 Agent
    react_agent = await create_agent()

    print("=" * 50)
    print("开始执行代码评审任务...")
    print("=" * 50)

    # 构建查询
    query = f"""请对以下 Python 代码进行专业评审，识别其中的问题并给出改进建议：

文件路径: sample_code.py

```python
{SAMPLE_CODE}
```

请输出完整的评审报告。"""

    # 运行 Agent
    result = await Runner.run_agent(
        agent=react_agent,
        inputs={
            "query": query,
            "conversation_id": "skill_review_001"
        }
    )

    print("=" * 50)
    print("评审结果:")
    print("=" * 50)

    if isinstance(result, dict):
        output = result.get('output', str(result))
        print(output)
    else:
        print(result)


if __name__ == "__main__":
    asyncio.run(main())
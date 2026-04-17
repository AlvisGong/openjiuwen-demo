# coding: utf-8
"""
react agent 调用 Skill 工具示例 - 继承 Tool 类方式

运行前请确保：
1. 已安装 openjiuwen 包
2. 配置正确的 API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER
3. skill 文件存在于指定路径

本示例通过继承 openjiuwen.core.foundation.tool.Tool 类来创建 SkillTool，
相比 @tool 装饰器方式，这种方式更加灵活，可以自定义更多逻辑。
"""
import os, sys
import io
import asyncio

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
当用户请求代码评审时，请使用 python_review_skill 工具进行专业的代码分析。"""
    return [
        dict(role="system", content=system_prompt)
    ]


# ============================================================================
# 方式二：继承 Tool 类创建自定义 SkillTool
# ============================================================================

from openjiuwen.core.foundation.tool import Tool, ToolCard


class SkillTool(Tool):
    """
    Skill 工具类 - 通过继承 Tool 类实现

    这种方式相比 @tool 装饰器有以下优势：
    1. 可以在类中封装更多逻辑（如 skill 加载、缓存等）
    2. 可以自定义 invoke 和 stream 方法的实现
    3. 支持更复杂的初始化和配置
    4. 可以添加额外的属性和方法
    """

    def __init__(
        self,
        skill_path: str,
        card: ToolCard,
        model_config: ModelRequestConfig = None,
        model_client: ModelClientConfig = None
    ):
        """
        初始化 SkillTool

        Args:
            skill_path: skill 文件路径（SKILL.md）
            card: ToolCard 实例，定义工具的元信息
            model_config: LLM 模型配置
            model_client: LLM 客户端配置
        """
        super().__init__(card)
        self.skill_path = skill_path
        self.skill_content = self._load_skill_content()
        self.model_config = model_config or model_config
        self.model_client = model_client or model_client
        self._llm_model = None

    def _load_skill_content(self) -> str:
        """加载 skill 文件内容"""
        if not os.path.exists(self.skill_path):
            raise FileNotFoundError(f"Skill 文件不存在: {self.skill_path}")
        with open(self.skill_path, 'r', encoding='utf-8') as f:
            return f.read()

    def _get_llm_model(self):
        """获取 LLM 模型实例（延迟初始化）"""
        if self._llm_model is None:
            from openjiuwen.core.foundation.llm import Model
            self._llm_model = Model(
                model_config=self.model_config,
                client_config=self.model_client
            )
        return self._llm_model

    async def invoke(self, inputs: dict) -> dict:
        """
        执行 skill 调用

        Args:
            inputs: 输入参数，包含 code_content 和可选的 file_path

        Returns:
            评审结果字典
        """
        code_content = inputs.get("code_content", "")
        file_path = inputs.get("file_path", "未指定")

        # 构建评审提示
        review_prompt = f"""请根据以下评审指南对代码进行专业评审：

{self.skill_content}

---

待评审的代码：
文件路径: {file_path}

```python
{code_content}
```

请按照评审指南的输出格式，生成完整的代码评审报告。"""

        # 使用 LLM 执行评审
        model = self._get_llm_model()
        messages = [
            {"role": "system", "content": "你是一位专业的 Python 代码评审专家，请严格按照评审指南进行代码分析。"},
            {"role": "user", "content": review_prompt}
        ]

        result = await model.invoke(messages=messages)

        # 提取结果内容
        if hasattr(result, 'content'):
            output = result.content
        elif isinstance(result, dict):
            output = result.get('content', str(result))
        else:
            output = str(result)

        return {"result": output}

    async def stream(self, inputs: dict):
        """
        流式执行 skill 调用（如果需要支持流式输出）

        Args:
            inputs: 输入参数

        Yields:
            流式输出的内容片段
        """
        # 这里可以实现流式输出逻辑
        # 目前简单调用 invoke 并返回完整结果
        result = await self.invoke(inputs)
        yield result


def create_skill_tool() -> SkillTool:
    """
    创建 Python 代码评审 SkillTool 实例

    Returns:
        SkillTool 实例
    """
    # 创建 ToolCard - 定义工具的元信息
    skill_card = ToolCard(
        id="python_review_skill",
        name="PythonReviewSkill",
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

    # 创建 SkillTool 实例
    skill_tool = SkillTool(
        skill_path=PYTHON_REVIEW_SKILL_PATH,
        card=skill_card,
        model_config=model_config,
        model_client=model_client
    )

    return skill_tool


from openjiuwen.core.single_agent import AgentCard, ReActAgentConfig, ReActAgent
from openjiuwen.core.runner import Runner


async def create_agent():
    """创建 ReAct Agent"""
    agent_card = AgentCard(
        id="react_skill_tool_agent_001",
        description="代码评审助手（继承Tool方式）",
    )
    prompt_template = create_prompt_template()

    react_agent_config = ReActAgentConfig(
        model_client_config=model_client,
        model_config_obj=model_config,
        prompt_template=prompt_template
    )

    react_agent = ReActAgent(card=agent_card).configure(react_agent_config)

    # 创建并注册 SkillTool（继承 Tool 类方式）
    skill_tool = create_skill_tool()

    Runner.resource_mgr.add_tool(skill_tool)
    react_agent.ability_manager.add(skill_tool.card)

    print(f"已注册 SkillTool（继承方式）: {skill_tool.card.name}")
    print(f"Skill 路径: {skill_tool.skill_path}")
    print(f"Tool ID: {skill_tool.card.id}")

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
            "conversation_id": "skill_tool_review_001"
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
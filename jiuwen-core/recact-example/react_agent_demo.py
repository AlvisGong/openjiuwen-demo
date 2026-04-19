#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright (c) Huawei Technologies Co., Ltd. 2025. All rights reserved.
"""
ReAct Agent with Skills, MCP and Workflow Integration Demo
===========================================================

This example demonstrates how to build a ReActAgent that integrates:
1. MCP Server (calculator tools via SSE)
2. Agent Skills (code review skill)
3. 3 个 Workflowss (financial service workflows)

Run:
    python react_agent_demo.py
"""

import asyncio
import os
import uuid
from pathlib import Path
from datetime import datetime

from dotenv import load_dotenv

from openjiuwen.core.common.logging import logger
from openjiuwen.core.foundation.llm import (
    ModelRequestConfig,
    ModelClientConfig,
)
from openjiuwen.core.foundation.tool.mcp.base import McpServerConfig
from openjiuwen.core.runner import Runner
from openjiuwen.core.sys_operation import SysOperationCard, OperationMode, LocalWorkConfig
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig
from openjiuwen.core.workflow import (
    Workflow,
    WorkflowCard,
    Start,
    End,
    QuestionerComponent,
    QuestionerConfig,
    FieldInfo,
)

# Environment configuration
load_dotenv()

API_BASE = os.getenv("API_BASE", "")
API_KEY = os.getenv("API_KEY", "")
MODEL_NAME = os.getenv("MODEL_NAME", "")
MODEL_PROVIDER = os.getenv("MODEL_PROVIDER", "")
MODEL_ID = "default_model"

# Skills and files directories
SKILLS_DIR = Path(os.getenv("SKILLS_DIR", str(Path(__file__).parent / "skills")))
FILES_BASE_DIR = Path(os.getenv("FILES_BASE_DIR", str(Path(__file__).parent)))
OUTPUT_DIR = Path(os.getenv("OUTPUT_DIR", str(Path(__file__).parent / "output")))

# MCP Server configuration
MCP_SERVER_URL = os.getenv("MCP_SERVER_URL", "http://127.0.0.1:3001/sse")

# Disable SSL for local testing (enable in production)
os.environ.setdefault("SSRF_PROTECT_ENABLED", "false")
os.environ.setdefault("RESTFUL_SSL_VERIFY", "false")
os.environ.setdefault("LLM_SSL_VERIFY", "false")


def build_current_date():
    """Get current date for prompts."""
    return datetime.now().strftime("%Y-%m-%d")


class ReactAgentWithSkillsMcpWorkflow:
    """ReAct Agent integrating Skills, MCP and Workflow."""

    @staticmethod
    def _create_model_config() -> ModelRequestConfig:
        """Create model request configuration."""
        return ModelRequestConfig(
            model=MODEL_NAME,
            temperature=0.7,
            top_p=0.9,
        )

    @staticmethod
    def _create_client_config() -> ModelClientConfig:
        """Create model client configuration."""
        return ModelClientConfig(
            client_provider=MODEL_PROVIDER,
            api_key=API_KEY,
            api_base=API_BASE,
            timeout=120,
            verify_ssl=False,
        )

    @staticmethod
    def _create_system_prompt() -> list:
        """Create system prompt template."""
        system_prompt = (
            "您是一个具有多种能力的智能助手，负责用户意图识别，然后调用工具/技能来处理用户请求，不需要自己思考。\n"
            f"- 今天日期：{build_current_date()}\n"
            f"- 所有用户提供的文件位于 '{FILES_BASE_DIR}'\n"
            f"- 将所有生成的文件放入 '{OUTPUT_DIR}'\n"
            "\n"
            "您可以使用：\n"
            "1. 通过MCP的计算器工具（加、减、乘、除、幂运算）\n"
            "2. 代码评审技能，用于检查代码质量\n"
            "3. 金融工作流，用于处理转账、投资和余额查询\n"
            "\n"
            "根据用户请求，必须选择合适的工具/技能。\n"
        )
        return [{"role": "system", "content": system_prompt}]

    @staticmethod
    def build_simple_workflow(
        workflow_id: str,
        workflow_name: str,
        workflow_desc: str,
        field_name: str,
        field_desc: str
    ) -> Workflow:
        """Build a simple workflow with questioner component."""
        card = WorkflowCard(
            name=workflow_name,
            id=workflow_id,
            version="1.0",
            description=workflow_desc,
        )
        flow = Workflow(card=card)

        start = Start()
        key_fields = [
            FieldInfo(
                field_name=field_name,
                description=field_desc,
                required=True
            ),
        ]

        questioner_config = QuestionerConfig(
            model_client_config=ReactAgentWithSkillsMcpWorkflow._create_client_config(),
            model_config=ReactAgentWithSkillsMcpWorkflow._create_model_config(),
            question_content="",
            extract_fields_from_response=True,
            field_names=key_fields,
            with_chat_history=False,
        )
        questioner = QuestionerComponent(questioner_config)

        end = End({"responseTemplate": f"{workflow_name}完成: {{{{{field_name}}}}}"})

        flow.set_start_comp("start", start, inputs_schema={"query": "${query}"})
        flow.add_workflow_comp(
            "questioner", questioner, inputs_schema={"query": "${start.query}"}
        )
        flow.set_end_comp(
            "end", end, inputs_schema={field_name: f"${{questioner.{field_name}}}"}
        )

        flow.add_connection("start", "questioner")
        flow.add_connection("questioner", "end")

        return flow


async def setup_sys_operation():
    """Setup system operation for file/code/shell access."""
    sysop_card = SysOperationCard(
        mode=OperationMode.LOCAL,
        work_config=LocalWorkConfig(work_dir=None),
    )
    Runner.resource_mgr.add_sys_operation(sysop_card)
    return sysop_card


async def setup_model():
    """Setup model in resource manager."""
    from openjiuwen.core.foundation.llm import Model

    model = Model(
        model_client_config=ReactAgentWithSkillsMcpWorkflow._create_client_config(),
        model_config=ReactAgentWithSkillsMcpWorkflow._create_model_config(),
    )
    Runner.resource_mgr.add_model(
        model_id=MODEL_ID,
        model=lambda: model,
    )


async def setup_workflows():
    """Setup financial workflows."""
    # Transfer workflow
    transfer_workflow = ReactAgentWithSkillsMcpWorkflow.build_simple_workflow(
        workflow_id="transfer_flow",
        workflow_name="转账服务",
        workflow_desc="处理用户转账请求",
        field_name="amount",
        field_desc="转账金额（数字）"
    )

    # Investment workflow
    invest_workflow = ReactAgentWithSkillsMcpWorkflow.build_simple_workflow(
        workflow_id="invest_flow",
        workflow_name="理财服务",
        workflow_desc="提供理财产品推荐",
        field_name="product",
        field_desc="理财产品名称"
    )

    # Balance query workflow
    balance_workflow = ReactAgentWithSkillsMcpWorkflow.build_simple_workflow(
        workflow_id="balance_flow",
        workflow_name="余额查询",
        workflow_desc="查询用户账户余额",
        field_name="account",
        field_desc="账户号码"
    )

    Runner.resource_mgr.add_workflow(transfer_workflow.card, lambda: transfer_workflow)
    Runner.resource_mgr.add_workflow(invest_workflow.card, lambda: invest_workflow)
    Runner.resource_mgr.add_workflow(balance_workflow.card, lambda: balance_workflow)

    return [transfer_workflow, invest_workflow, balance_workflow]


async def setup_mcp_server(agent: ReActAgent):
    """Setup MCP server for calculator tools."""
    mcp_config = McpServerConfig(
        server_id="calculator_mcp",
        server_name="calculator",
        server_path=MCP_SERVER_URL,
        client_type="sse",
        params={
            "type": "object",
            "title": "calculatorArguments",
            "properties": {},
            "required": []
        }
    )

    try:
        await Runner.resource_mgr.add_mcp_server(mcp_config, expiry_time=600000)
        agent.ability_manager.add(mcp_config)
        logger.info(f"MCP server '{mcp_config.server_id}' added successfully")
        return mcp_config
    except Exception as e:
        logger.warning(f"Failed to add MCP server: {e}. MCP server may not be running.")
        return None


async def setup_skills(agent: ReActAgent, sysop_card: SysOperationCard):
    """Setup agent skills."""
    # Add file/code/shell operation tools
    read_file_card = Runner.resource_mgr.get_sys_op_tool_cards(
        sys_operation_id=sysop_card.id,
        operation_name="fs",
        tool_name="read_file"
    )
    agent.ability_manager.add(read_file_card)

    execute_code_card = Runner.resource_mgr.get_sys_op_tool_cards(
        sys_operation_id=sysop_card.id,
        operation_name="code",
        tool_name="execute_code"
    )
    agent.ability_manager.add(execute_code_card)

    execute_cmd_card = Runner.resource_mgr.get_sys_op_tool_cards(
        sys_operation_id=sysop_card.id,
        operation_name="shell",
        tool_name="execute_cmd"
    )
    agent.ability_manager.add(execute_cmd_card)

    # Register skills from directory
    if SKILLS_DIR.exists():
        await agent.register_skill(str(SKILLS_DIR))
        logger.info(f"Skills registered from '{SKILLS_DIR}'")
    else:
        logger.warning(f"Skills directory '{SKILLS_DIR}' does not exist")


async def create_react_agent(sysop_card: SysOperationCard):
    """Create and configure ReActAgent."""
    agent_card = AgentCard(
        id="react_agent_skills_mcp_workflow",
        name="MultiCapableAgent",
        description="智能助手，支持MCP计算器、代码评审技能和金融工作流",
    )

    agent = ReActAgent(card=agent_card)

    config = (ReActAgentConfig()
              .configure_model_client(
                  provider=MODEL_PROVIDER,
                  api_key=API_KEY,
                  api_base=API_BASE,
                  model_name=MODEL_NAME,
                  verify_ssl=False,
              )
              .configure_prompt_template(ReactAgentWithSkillsMcpWorkflow._create_system_prompt())
              .configure_max_iterations(20)
              .configure_context_engine(
                  max_context_message_num=None,
                  default_window_round_num=None
              )
             )
    config.sys_operation_id = sysop_card.id

    agent.configure(config)
    return agent


async def run_interactive_session(agent: ReActAgent, mcp_config: McpServerConfig | None):
    """Run interactive session with user."""
    print("\n========== 多功能智能体交互系统 ==========")
    print("支持功能:")
    print("  - 计算器: 加减乘除、幂运算 (需要启动MCP服务器)")
    print("  - 代码评审: review代码、检查代码质量")
    print("  - 金融服务: 转账、理财、余额查询")
    print("  - 'quit' 或 'exit': 退出系统\n")

    conversation_id = str(uuid.uuid4())[:8]
    print(f"当前会话 ID: {conversation_id}")

    OUTPUT_DIR.mkdir(exist_ok=True)

    while True:
        try:
            print(f"\n{'=' * 40}")
            user_input = input("\n请输入您的问题: ").strip()

            if user_input.lower() in ['quit', 'exit', '退出']:
                print("\n感谢使用，再见！")
                break

            if not user_input:
                print("输入不能为空，请重新输入")
                continue

            print("\n助手回复: ", end="", flush=True)

            result = await Runner.run_agent(
                agent=agent,
                inputs={"query": user_input, "conversation_id": conversation_id},
            )

            output = result.get("output", str(result))
            print(output)

        except KeyboardInterrupt:
            print("\n\n检测到 Ctrl+C，正在退出...")
            break
        except Exception as e:
            logger.error(f"处理输入时发生错误: {e}")
            print(f"\n发生错误: {e}")


async def cleanup(mcp_config: McpServerConfig | None):
    """Cleanup resources."""
    if mcp_config:
        try:
            await Runner.resource_mgr.remove_mcp_server(server_id=mcp_config.server_id)
            logger.info(f"MCP server '{mcp_config.server_id}' removed")
        except Exception as e:
            logger.warning(f"Failed to remove MCP server: {e}")


async def main():
    """Main entry point."""
    logger.info("Starting ReAct Agent with Skills, MCP and Workflow...")

    # 1. Setup system operation
    sysop_card = await setup_sys_operation()
    logger.info("System operation setup complete")

    # 2. Setup model
    await setup_model()
    logger.info("Model setup complete")

    # 3. Setup 金融助手工作流
    # 3.1 转账工作流
    # 3.2 理财工作流
    # 3.3 查询余额工作流
    workflows = await setup_workflows()
    logger.info(f"Workflows setup complete: {[w.card.name for w in workflows]}")

    # 4. Create agent
    agent = await create_react_agent(sysop_card)
    logger.info("Agent created")

    # 5. Setup MCP server (requires MCP server running at MCP_SERVER_URL)
    mcp_config = await setup_mcp_server(agent)

    # 6. Setup skills
    await setup_skills(agent, sysop_card)

    # 7. Add workflow_agent to agent abilities
    for workflow in workflows:
        agent.ability_manager.add(workflow.card)

    # 8. Run interactive session
    try:
        await run_interactive_session(agent, mcp_config)
    finally:
        await cleanup(mcp_config)


if __name__ == "__main__":
    """
    Usage:
    1. Start MCP server first (optional, for calculator tools):
       python examples/mcp/sse/server.py

    2. Set environment variables in .env file:
       API_BASE=your_api_base
       API_KEY=your_api_key
       MODEL_NAME=your_model_name
       MODEL_PROVIDER=OpenAI

    3. Run this demo:
       python react_agent_demo.py

    Example interactions:
    - "帮我计算 15 + 27 等于多少" (uses MCP calculator)
    - "帮我review这段代码: def add(a, b): return a + b"
    - "我要转账" (uses transfer workflow)
    - "我要理财" (uses investment workflow)
    - "查询余额" (uses balance workflow)
    """
    asyncio.run(main())
# coding: utf-8
"""
记忆引擎示例 - LongTermMemory 核心功能演示

核心概念：
1. LongTermMemory：记忆引擎本体，负责管理用户对话消息、变量记忆和长期用户画像
2. MemoryEngineConfig：全局引擎配置（模型配置、加密等）
3. MemoryScopeConfig：作用域级配置（模型、嵌入模型）
4. AgentMemoryConfig：Agent级记忆策略（变量记忆、长期记忆、用户画像等）

演示场景：
1. 创建记忆引擎并注册存储
2. 配置作用域和 Agent 记忆策略
3. 写入消息并生成记忆
4. 查询变量记忆
5. 语义检索记忆
6. 更新和删除记忆

注意：运行此演示需要安装 chromadb 和 aiosqlite
pip install chromadb aiosqlite
"""

import asyncio
import os
import sys
import io
from datetime import datetime, timezone

# 修复 Windows 控制台中文编码问题
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.constant import API_BASE, API_KEY, MODEL_NAME, MODEL_PROVIDER,API_BL_KEY,API_BL_BASE

os.environ.setdefault("LLM_SSL_VERIFY", "false")
os.environ.setdefault("IS_SENSITIVE", "false")

# ========== 导入核心组件 ==========
from openjiuwen.core.memory import (
    LongTermMemory,
    MemoryEngineConfig,
    MemoryScopeConfig,
    AgentMemoryConfig,
)
from openjiuwen.core.memory.long_term_memory import MemInfo, MemResult
from openjiuwen.core.memory.manage.mem_model.memory_unit import MemoryType
from openjiuwen.core.foundation.store.kv.in_memory_kv_store import InMemoryKVStore
from openjiuwen.core.foundation.llm.schema.config import (
    ModelClientConfig,
    ModelRequestConfig,
)
from openjiuwen.core.retrieval.common.config import EmbeddingConfig
from openjiuwen.core.common.schema.param import Param
from openjiuwen.core.foundation.llm.schema.message import (
    BaseMessage,
    UserMessage,
    AssistantMessage,
)

# 导入可选存储（如果可用）
try:
    from openjiuwen.core.foundation.store.vector.chroma_vector_store import ChromaVectorStore
    CHROMA_AVAILABLE = True
except ImportError:
    CHROMA_AVAILABLE = False
    print("[警告] chromadb 未安装，向量存储功能将受限")

try:
    from sqlalchemy.ext.asyncio import create_async_engine
    from openjiuwen.core.foundation.store.db.default_db_store import DefaultDbStore
    SQLALCHEMY_AVAILABLE = True
except ImportError:
    SQLALCHEMY_AVAILABLE = False
    print("[警告] sqlalchemy/aiosqlite 未安装，数据库存储功能将受限")


# ========== 模型配置 ==========
def create_model_request_config() -> ModelRequestConfig:
    """创建模型请求配置"""
    return ModelRequestConfig(
        model=MODEL_NAME,
        temperature=0.0,
        top_p=0.9,
    )


def create_model_client_config() -> ModelClientConfig:
    """创建模型客户端配置"""
    return ModelClientConfig(
        client_provider=MODEL_PROVIDER,
        api_base=API_BASE,
        api_key=API_KEY,
        verify_ssl=False,
        timeout=60,
    )


def create_embedding_config() -> EmbeddingConfig:
    """创建嵌入模型配置"""
    return EmbeddingConfig(
        model_name="text-embedding-v4",
        api_key=API_BL_KEY,
        base_url=API_BL_BASE,
    )


# ========== 1. 创建记忆引擎演示 ==========
async def demo_create_memory_engine():
    """
    演示如何创建 LongTermMemory 记忆引擎实例

    包括：
    - 注册 KV 存储、向量存储、数据库存储
    - 设置全局引擎配置
    """
    print("=" * 60)
    print("演示 1: 创建记忆引擎")
    print("=" * 60)

    # 检查依赖
    if not CHROMA_AVAILABLE or not SQLALCHEMY_AVAILABLE:
        print("[警告] 缺少必要依赖，演示将使用简化配置")
        print("请安装: pip install chromadb aiosqlite")
        print("=" * 60)
        return None

    # 1. 创建 LongTermMemory 实例
    engine = LongTermMemory()
    print("[OK] LongTermMemory 实例创建成功")

    # 2. 创建底层存储
    print("\n[步骤1] 创建底层存储")

    # KV 存储（内存版本）
    kv_store = InMemoryKVStore()
    print("  - KV 存储: InMemoryKVStore ✓")

    # 向量存储（Chroma 本地版本）
    persist_dir = os.path.join(os.path.dirname(__file__), "chroma_data")
    os.makedirs(persist_dir, exist_ok=True)
    vector_store = ChromaVectorStore(persist_directory=persist_dir)
    print("  - 向量存储: ChromaVectorStore ✓")

    # 数据库存储（SQLite 本地版本）
    db_path = os.path.join(os.path.dirname(__file__), "memory_db.sqlite")
    async_engine = create_async_engine(
        f"sqlite+aiosqlite:///{db_path}",
        pool_size=5,
        max_overflow=5,
    )
    db_store = DefaultDbStore(async_engine)
    print("  - 数据库存储: SQLite ✓")

    # 3. 注册存储
    print("\n[步骤2] 注册存储到引擎")
    await engine.register_store(
        kv_store=kv_store,
        vector_store=vector_store,
        db_store=db_store,
    )
    print("  [OK] 存储注册成功")

    # 4. 设置全局引擎配置
    print("\n[步骤3] 设置全局引擎配置")
    engine_config = MemoryEngineConfig(
        default_model_cfg=create_model_request_config(),
        default_model_client_cfg=create_model_client_config(),
        input_msg_max_len=8192,
        crypto_key=b"",  # 不启用加密
    )
    engine.set_config(engine_config)
    print("  [OK] 全局配置设置成功")
    print(f"    - 默认模型: {MODEL_NAME}")
    print(f"    - 输入消息最大长度: {engine_config.input_msg_max_len}")

    print("=" * 60)
    print("演示 1 完成 - 记忆引擎已初始化")
    print("=" * 60)

    return engine


# ========== 2. 配置作用域演示 ==========
async def demo_configure_scope(engine: LongTermMemory):
    """
    演示如何配置 MemoryScopeConfig（作用域级配置）

    作用域（scope_id）用于将不同业务/Agent 的记忆隔离开来
    """
    print("\n" + "=" * 60)
    print("演示 2: 配置作用域")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    scope_id = "demo_scope_001"

    # 1. 创建作用域配置
    print("\n[步骤1] 创建 MemoryScopeConfig")
    scope_config = MemoryScopeConfig(
        model_cfg=create_model_request_config(),
        model_client_cfg=create_model_client_config(),
        embedding_cfg=create_embedding_config(),
    )
    print("  [OK] MemoryScopeConfig 创建成功")

    # 2. 设置作用域配置
    print("\n[步骤2] 设置作用域配置")
    ok = await engine.set_scope_config(scope_id, scope_config)
    if ok:
        print(f"  [OK] 作用域 '{scope_id}' 配置成功")
    else:
        print(f"  [失败] 作用域 '{scope_id}' 配置失败")
        return

    # 3. 获取作用域配置（验证）
    print("\n[步骤3] 验证配置（获取已设置的配置）")
    retrieved_config = await engine.get_scope_config(scope_id)
    if retrieved_config:
        print(f"  [OK] 成功获取作用域配置")
        print(f"    - 模型: {retrieved_config.model_cfg.model_name if retrieved_config.model_cfg else 'N/A'}")
    else:
        print("  [失败] 无法获取作用域配置")

    print("=" * 60)
    print("演示 2 完成")
    print("=" * 60)

    return scope_id


# ========== 3. 配置 Agent 记忆策略演示 ==========
async def demo_agent_memory_config():
    """
    演示如何配置 AgentMemoryConfig（Agent级记忆策略）

    包括：
    - 变量记忆配置（mem_variables）
    - 各类记忆开关（长期记忆、用户画像、语义记忆等）
    """
    print("\n" + "=" * 60)
    print("演示 3: 配置 Agent 记忆策略")
    print("=" * 60)

    # 1. 创建变量记忆配置
    print("\n[步骤1] 定义变量记忆字段（用户画像变量）")
    agent_config = AgentMemoryConfig(
        mem_variables=[
            Param.string("姓名", "用户姓名", required=False),
            Param.string("职业", "用户职业", required=False),
            Param.string("居住地", "用户居住地", required=False),
            Param.string("爱好", "用户爱好", required=False),
            Param.string("年龄", "用户年龄", required=False),
        ],
        enable_long_term_mem=True,      # 开启长期记忆
        enable_user_profile=True,       # 开启用户画像
        enable_semantic_memory=True,    # 开启语义记忆
        enable_episodic_memory=True,    # 开启情景记忆
        enable_summary_memory=True,     # 开启摘要记忆
    )
    print("  [OK] AgentMemoryConfig 创建成功")
    print("  变量记忆字段:")
    for var in agent_config.mem_variables:
        print(f"    - {var.name}: {var.description}")

    # 2. 显示记忆类型开关状态
    print("\n[步骤2] 记忆类型开关状态")
    print(f"  - 长期记忆 (long_term_mem): {agent_config.enable_long_term_mem}")
    print(f"  - 用户画像 (user_profile): {agent_config.enable_user_profile}")
    print(f"  - 语义记忆 (semantic_memory): {agent_config.enable_semantic_memory}")
    print(f"  - 情景记忆 (episodic_memory): {agent_config.enable_episodic_memory}")
    print(f"  - 摘要记忆 (summary_memory): {agent_config.enable_summary_memory}")

    print("=" * 60)
    print("演示 3 完成")
    print("=" * 60)

    return agent_config


# ========== 4. 写入消息并生成记忆演示 ==========
async def demo_add_messages(engine: LongTermMemory, scope_id: str, agent_config: AgentMemoryConfig):
    """
    演示如何使用 add_messages 写入对话并生成记忆

    add_messages 会：
    - 将消息写入消息表
    - 结合历史消息和 AgentMemoryConfig 调用大模型抽取变量/用户画像
    - 把抽取出的长期记忆写入向量存储和数据库
    """
    print("\n" + "=" * 60)
    print("演示 4: 写入消息并生成记忆")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    user_id = "demo_user_001"
    session_id = "session_001"

    # 1. 构建对话消息
    print("\n[步骤1] 构建对话消息")
    messages = [
        UserMessage(content="你好，我叫张三，今年25岁"),
        AssistantMessage(content="你好张三，很高兴认识你！25岁正是人生好时光。"),
        UserMessage(content="我是一名软件工程师，目前在杭州工作"),
        AssistantMessage(content="杭州是个美丽的城市！软件工程师是个很有前途的职业。"),
        UserMessage(content="我喜欢打羽毛球和看电影"),
        AssistantMessage(content="羽毛球是个很好的运动，电影也是放松的好方式。"),
    ]
    print(f"  消息数: {len(messages)}")
    for i, msg in enumerate(messages):
        role = msg.role
        content_preview = msg.content[:30] if len(msg.content) > 30 else msg.content
        print(f"    [{i}] {role}: {content_preview}...")

    # 2. 写入消息并生成记忆
    print("\n[步骤2] 调用 add_messages 写入消息并生成记忆")
    timestamp = datetime.now(timezone.utc)

    try:
        await engine.add_messages(
            messages=messages,
            agent_config=agent_config,
            user_id=user_id,
            scope_id=scope_id,
            session_id=session_id,
            timestamp=timestamp,
            gen_mem=True,                     # 是否生成长期记忆
            gen_mem_with_history_msg_num=5    # 生成记忆时使用的历史消息数
        )
        print("  [OK] 消息写入成功，记忆生成完成")
    except Exception as e:
        print(f"  [错误] add_messages 失败: {e}")
        print("  注意: 记忆生成需要 LLM 模型支持，请确保模型配置正确")

    # 3. 获取最近消息（验证写入）
    print("\n[步骤3] 验证：获取最近写入的消息")
    recent_messages = await engine.get_recent_messages(
        user_id=user_id,
        scope_id=scope_id,
        session_id=session_id,
        num=3
    )
    print(f"  最近消息数: {len(recent_messages)}")
    for msg in recent_messages:
        role = msg.role
        content_preview = msg.content[:30] if len(msg.content) > 30 else msg.content
        print(f"    - {role}: {content_preview}...")

    print("=" * 60)
    print("演示 4 完成")
    print("=" * 60)

    return user_id


# ========== 5. 查询变量记忆演示 ==========
async def demo_get_variables(engine: LongTermMemory, user_id: str, scope_id: str):
    """
    演示如何使用 get_variables 查询变量记忆

    支持：
    - 获取所有变量
    - 获取指定变量
    - 获取单个变量
    """
    print("\n" + "=" * 60)
    print("演示 5: 查询变量记忆")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    # 1. 获取所有变量
    print("\n[步骤1] 获取所有变量记忆")
    try:
        all_variables = await engine.get_variables(
            user_id=user_id,
            scope_id=scope_id
        )
        print(f"  变量数: {len(all_variables)}")
        for name, value in all_variables.items():
            print(f"    - {name}: {value}")
    except Exception as e:
        print(f"  [错误] get_variables 失败: {e}")

    # 2. 获取指定变量
    print("\n[步骤2] 获取指定变量（姓名、职业）")
    try:
        specific_vars = await engine.get_variables(
            names=["姓名", "职业"],
            user_id=user_id,
            scope_id=scope_id
        )
        print(f"  获取结果:")
        for name, value in specific_vars.items():
            print(f"    - {name}: {value}")
    except Exception as e:
        print(f"  [错误] 获取指定变量失败: {e}")

    # 3. 获取单个变量
    print("\n[步骤3] 获取单个变量（爱好）")
    try:
        hobby_var = await engine.get_variables(
            names="爱好",
            user_id=user_id,
            scope_id=scope_id
        )
        print(f"  获取结果: {hobby_var}")
    except Exception as e:
        print(f"  [错误] 获取单个变量失败: {e}")

    print("=" * 60)
    print("演示 5 完成")
    print("=" * 60)


# ========== 6. 分页查看长期记忆演示 ==========
async def demo_get_user_mem_by_page(engine: LongTermMemory, user_id: str, scope_id: str):
    """
    演示如何使用 get_user_mem_by_page 分页查看长期记忆

    支持按记忆类型过滤：
    - MemoryType.USER_PROFILE: 用户画像
    - MemoryType.SEMANTIC_MEMORY: 语义记忆
    - MemoryType.EPISODIC_MEMORY: 情景记忆
    """
    print("\n" + "=" * 60)
    print("演示 6: 分页查看长期记忆")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    # 1. 获取用户画像类记忆
    print("\n[步骤1] 获取用户画像类记忆 (MemoryType.USER_PROFILE)")
    try:
        user_profile_mems = await engine.get_user_mem_by_page(
            user_id=user_id,
            scope_id=scope_id,
            page_size=10,
            page_idx=0,
            memory_type=MemoryType.USER_PROFILE
        )
        print(f"  记忆数: {len(user_profile_mems)}")
        for mem in user_profile_mems:
            print(f"    - mem_id: {mem.mem_id}")
            print(f"      type: {mem.type}")
            print(f"      content: {mem.content[:50]}...")
    except Exception as e:
        print(f"  [错误] 获取用户画像失败: {e}")

    # 2. 获取所有类型记忆
    print("\n[步骤2] 获取所有类型记忆")
    try:
        all_mems = await engine.get_user_mem_by_page(
            user_id=user_id,
            scope_id=scope_id,
            page_size=10,
            page_idx=0,
            memory_type=MemoryType.UNKNOWN  # 不过滤类型
        )
        print(f"  总记忆数: {len(all_mems)}")
        for mem in all_mems:
            print(f"    - [{mem.type}] {mem.content[:40]}...")
    except Exception as e:
        print(f"  [错误] 获取所有记忆失败: {e}")

    # 3. 统计记忆数量
    print("\n[步骤3] 统计用户记忆总数")
    try:
        total_num = await engine.user_mem_total_num(
            user_id=user_id,
            scope_id=scope_id
        )
        print(f"  用户记忆总数: {total_num}")
    except Exception as e:
        print(f"  [错误] 统计失败: {e}")

    print("=" * 60)
    print("演示 6 完成")
    print("=" * 60)


# ========== 7. 语义检索记忆演示 ==========
async def demo_search_user_mem(engine: LongTermMemory, user_id: str, scope_id: str):
    """
    演示如何使用 search_user_mem 进行语义检索记忆

    使用向量相似度搜索，返回最相关的记忆
    """
    print("\n" + "=" * 60)
    print("演示 7: 语义检索记忆")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    # 1. 搜索用户职业信息
    print("\n[步骤1] 搜索：用户职业是什么？")
    query = "用户的职业是什么？"
    try:
        results = await engine.search_user_mem(
            query=query,
            num=5,
            user_id=user_id,
            scope_id=scope_id,
            threshold=0.3
        )
        print(f"  搜索结果数: {len(results)}")
        for item in results:
            mem = item.mem_info
            print(f"    - mem_id: {mem.mem_id}")
            print(f"      type: {mem.type}")
            print(f"      score: {item.score:.4f}")
            print(f"      content: {mem.content[:50]}...")
    except Exception as e:
        print(f"  [错误] 搜索失败: {e}")
        print("  注意: 语义检索需要向量存储支持")

    # 2. 搜索用户爱好
    print("\n[步骤2] 搜索：用户喜欢做什么？")
    query = "用户喜欢做什么？"
    try:
        results = await engine.search_user_mem(
            query=query,
            num=5,
            user_id=user_id,
            scope_id=scope_id,
            threshold=0.3
        )
        print(f"  搜索结果数: {len(results)}")
        for item in results:
            mem = item.mem_info
            print(f"    - score={item.score:.4f}, content={mem.content[:40]}...")
    except Exception as e:
        print(f"  [错误] 搜索失败: {e}")

    # 3. 搜索用户居住地
    print("\n[步骤3] 搜索：用户住在哪里？")
    query = "用户住在哪里？"
    try:
        results = await engine.search_user_mem(
            query=query,
            num=3,
            user_id=user_id,
            scope_id=scope_id,
            threshold=0.2
        )
        print(f"  搜索结果数: {len(results)}")
        for item in results:
            mem = item.mem_info
            print(f"    - score={item.score:.4f}, content={mem.content[:40]}...")
    except Exception as e:
        print(f"  [错误] 搜索失败: {e}")

    print("=" * 60)
    print("演示 7 完成")
    print("=" * 60)


# ========== 8. 更新和删除记忆演示 ==========
async def demo_update_delete_memory(engine: LongTermMemory, user_id: str, scope_id: str):
    """
    演示如何更新和删除记忆

    包括：
    - update_variables: 更新变量记忆
    - delete_variables: 删除变量记忆
    - delete_mem_by_id: 按ID删除长期记忆
    - delete_mem_by_user_id: 删除用户所有记忆
    """
    print("\n" + "=" * 60)
    print("演示 8: 更新和删除记忆")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    # 1. 更新变量记忆
    print("\n[步骤1] 更新变量记忆（爱好改为篮球）")
    try:
        await engine.update_variables(
            variables={"爱好": "篮球"},
            user_id=user_id,
            scope_id=scope_id
        )
        print("  [OK] 变量更新成功")

        # 验证更新
        hobby = await engine.get_variables(names="爱好", user_id=user_id, scope_id=scope_id)
        print(f"  验证: 爱好 = {hobby.get('爱好', 'N/A')}")
    except Exception as e:
        print(f"  [错误] 更新失败: {e}")

    # 2. 删除单个变量
    print("\n[步骤2] 删除单个变量（年龄）")
    try:
        await engine.delete_variables(
            names=["年龄"],
            user_id=user_id,
            scope_id=scope_id
        )
        print("  [OK] 变量删除成功")

        # 验证删除
        all_vars = await engine.get_variables(user_id=user_id, scope_id=scope_id)
        print(f"  验证: 剩余变量 = {list(all_vars.keys())}")
    except Exception as e:
        print(f"  [错误] 删除变量失败: {e}")
        # 查看是否有 delete_variables 方法
        if not hasattr(engine, 'delete_variables'):
            print("  注意: delete_variables 方法可能未实现")

    # 3. 查看长期记忆（准备删除测试）
    print("\n[步骤3] 查看长期记忆（准备按ID删除测试）")
    try:
        mems = await engine.get_user_mem_by_page(
            user_id=user_id,
            scope_id=scope_id,
            page_size=5,
            page_idx=0
        )
        if mems:
            first_mem = mems[0]
            print(f"  将删除的记忆: mem_id={first_mem.mem_id}, content={first_mem.content[:30]}...")

            # 4. 按ID删除长期记忆
            print("\n[步骤4] 按ID删除长期记忆")
            try:
                await engine.delete_mem_by_id(
                    mem_id=first_mem.mem_id,
                    user_id=user_id,
                    scope_id=scope_id
                )
                print("  [OK] 记忆删除成功")
            except Exception as e:
                print(f"  [错误] delete_mem_by_id 失败: {e}")
        else:
            print("  没有长期记忆可供删除测试")
    except Exception as e:
        print(f"  [错误] 获取记忆失败: {e}")

    # 5. 删除整个用户记忆（可选，谨慎操作）
    print("\n[步骤5] 删除用户所有记忆（演示仅打印，不实际执行）")
    print("  代码示例:")
    print("    await engine.delete_mem_by_user_id(user_id='demo_user_001', scope_id='demo_scope_001')")
    print("  注意: 此操作会删除用户所有记忆，谨慎使用!")

    # 6. 删除整个作用域（可选，谨慎操作）
    print("\n[步骤6] 删除整个作用域（演示仅打印，不实际执行）")
    print("  代码示例:")
    print("    await engine.delete_mem_by_scope(scope_id='demo_scope_001')")
    print("    await engine.delete_scope_config(scope_id='demo_scope_001')")
    print("  注意: 此操作会删除整个作用域下所有用户的记忆!")

    print("=" * 60)
    print("演示 8 完成")
    print("=" * 60)


# ========== 9. 搜索用户历史摘要演示 ==========
async def demo_search_user_history_summary(engine: LongTermMemory, user_id: str, scope_id: str):
    """
    演示如何使用 search_user_history_summary 搜索用户历史摘要
    """
    print("\n" + "=" * 60)
    print("演示 9: 搜索用户历史摘要")
    print("=" * 60)

    if engine is None:
        print("[警告] 记忆引擎未初始化，跳过此演示")
        print("=" * 60)
        return

    print("\n[步骤1] 搜索用户历史摘要")
    query = "用户的个人信息"
    try:
        results = await engine.search_user_history_summary(
            query=query,
            num=5,
            user_id=user_id,
            scope_id=scope_id,
            threshold=0.3
        )
        print(f"  搜索结果数: {len(results)}")
        for item in results:
            mem = item.mem_info
            print(f"    - mem_id: {mem.mem_id}")
            print(f"      type: {mem.type}")
            print(f"      score: {item.score:.4f}")
            print(f"      content: {mem.content[:50]}...")
    except Exception as e:
        print(f"  [错误] 搜索摘要失败: {e}")

    print("=" * 60)
    print("演示 9 完成")
    print("=" * 60)


# ========== 主入口 ==========
async def main():
    """运行所有演示"""
    print("\n" + "=" * 60)
    print("记忆引擎 (LongTermMemory) 功能演示")
    print("=" * 60)

    # 检查依赖状态
    print("\n依赖检查:")
    print(f"  - chromadb: {'已安装' if CHROMA_AVAILABLE else '未安装（需要向量存储）'}")
    print(f"  - sqlalchemy/aiosqlite: {'已安装' if SQLALCHEMY_AVAILABLE else '未安装（需要数据库存储）'}")

    if not CHROMA_AVAILABLE or not SQLALCHEMY_AVAILABLE:
        print("\n[重要提示] 记忆引擎需要以下依赖才能正常运行:")
        print("  pip install chromadb aiosqlite")
        print("\n演示将使用简化模式，部分功能可能受限")

    # 演示 1: 创建记忆引擎
    engine = await demo_create_memory_engine()

    if engine is None:
        print("\n由于依赖缺失，完整演示无法继续")
        print("请安装依赖后重新运行: pip install chromadb aiosqlite")
        return

    # 演示 2: 配置作用域
    scope_id = await demo_configure_scope(engine)

    # 演示 3: 配置 Agent 记忆策略
    agent_config = await demo_agent_memory_config()

    # 演示 4: 写入消息并生成记忆
    user_id = await demo_add_messages(engine, scope_id, agent_config)

    # 演示 5: 查询变量记忆
    await demo_get_variables(engine, user_id, scope_id)

    # 演示 6: 分页查看长期记忆
    await demo_get_user_mem_by_page(engine, user_id, scope_id)

    # 演示 7: 语义检索记忆
    await demo_search_user_mem(engine, user_id, scope_id)

    # 演示 8: 更新和删除记忆
    await demo_update_delete_memory(engine, user_id, scope_id)

    # 演示 9: 搜索用户历史摘要
    await demo_search_user_history_summary(engine, user_id, scope_id)

    print("\n" + "=" * 60)
    print("所有演示完成！")
    print("=" * 60)

    # 清理提示
    print("\n[清理提示] 演示数据保存在本地:")
    chroma_dir = os.path.join(os.path.dirname(__file__), "chroma_data")
    db_file = os.path.join(os.path.dirname(__file__), "memory_db.sqlite")
    print(f"  - Chroma 数据: {chroma_dir}")
    print(f"  - SQLite 数据库: {db_file}")
    print("  如需清理，可手动删除这些文件/目录")


if __name__ == "__main__":
    asyncio.run(main())
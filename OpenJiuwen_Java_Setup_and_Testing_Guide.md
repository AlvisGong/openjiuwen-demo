# OpenJiuwen Java 版本搭建与测试手册

**版本**: agent-core-java 0.1.12  
**最后更新**: 2026-06-25  
**适用平台**: Windows 10/11  

---

## 一、整体架构概览

Java 版本的 OpenJiuwen 由以下三部分组成：

| 组件 | 说明 | 仓库/路径 |
|------|------|-----------|
| **agent-core-java** | 核心框架库，提供 Agent、工作流、工具调用等能力 | `https://gitcode.com/openJiuwen/agent-core-java` (tag 0.1.12) |
| **openjiuwen-java-demo** | 基于核心框架的可运行示例集合 | 本地项目 `openjiuwen-java-demo` |
| **openjiuwen-demo** | 包含 Python 示例和早期 Java 示例的参考仓库 | `https://github.com/AlvisGong/openjiuwen-demo.git` |

依赖关系：`openjiuwen-java-demo` → `agent-core-java` (本地 Maven 仓库) → 第三方依赖 (Maven Central)

---

## 二、环境准备

### 2.1 JDK 17 安装

本项目使用 **Eclipse Temurin JDK 17.0.13+11**（独立安装，不影响系统全局 Java 环境）。

**下载地址**:  
https://adoptium.net/temurin/releases/?version=17

**安装方式（免安装 ZIP 版）**:

1. 下载 `OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip`
2. 解压到 `D:\Dev\codex\.sdk\jdk-17.0.13+11`
3. 验证：

```powershell
$env:JAVA_HOME = "D:\Dev\codex\.sdk\jdk-17.0.13+11"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
```

预期输出：
```
openjdk version "17.0.13" 2024-10-15
OpenJDK Runtime Environment Temurin-17.0.13+11 (build 17.0.13+11)
OpenJDK 64-Bit Server VM Temurin-17.0.13+11 (build 17.0.13+11, mixed mode, sharing)
```

### 2.2 Maven 3.9.9 安装

**下载地址**:  
https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip

**安装步骤**:

1. 下载 Maven ZIP 包
2. 解压到 `D:\Dev\codex\.sdk\apache-maven-3.9.9`
3. 验证：

```powershell
$env:JAVA_HOME = "D:\Dev\codex\.sdk\jdk-17.0.13+11"
$env:PATH = "$env:JAVA_HOME\bin;D:\Dev\codex\.sdk\apache-maven-3.9.9\bin;$env:PATH"
mvn -version
```

预期输出：
```
Apache Maven 3.9.9
Maven home: D:\Dev\codex\.sdk\apache-maven-3.9.9
Java version: 17.0.13, vendor: Eclipse Adoptium
```

### 2.3 环境变量快速设置

每次打开新终端时，需要临时设置环境变量。可以创建一个快捷脚本：

**PowerShell (`setup-env.ps1`)**:
```powershell
$env:JAVA_HOME = "D:\Dev\codex\.sdk\jdk-17.0.13+11"
$env:PATH = "$env:JAVA_HOME\bin;D:\Dev\codex\.sdk\apache-maven-3.9.9\bin;$env:PATH"
Write-Host "Environment ready: Java $(java -version 2>&1 | Select-Object -First 1)"
```

**Batch (`setup-env.bat`)**:
```batch
@echo off
set JAVA_HOME=D:\Dev\codex\.sdk\jdk-17.0.13+11
set PATH=%JAVA_HOME%\bin;D:\Dev\codex\.sdk\apache-maven-3.9.9\bin;%PATH%
echo Environment ready.
java -version
```

---

## 三、搭建步骤（从零开始）

### 步骤 1：克隆 agent-core-java 核心库

```powershell
cd D:\Dev\codex
git clone --branch 0.1.12 --depth 1 https://gitcode.com/openJiuwen/agent-core-java.git
```

> **说明**: `--branch 0.1.12` 拉取指定版本标签，`--depth 1` 仅拉取最新提交以节省空间。

### 步骤 2：编译并安装 agent-core-java 到本地 Maven 仓库

```powershell
# 确保环境变量已设置
$env:JAVA_HOME = "D:\Dev\codex\.sdk\jdk-17.0.13+11"
$env:PATH = "$env:JAVA_HOME\bin;D:\Dev\codex\.sdk\apache-maven-3.9.9\bin;$env:PATH"

cd D:\Dev\codex\agent-core-java
mvn clean install -DskipTests
```

> **说明**: `install` 会将编译产物安装到本地 Maven 仓库（`~/.m2/repository`），使其他项目可以引用。`-DskipTests` 跳过测试以加快编译速度。

预期输出（关键部分）：
```
[INFO] Building agent-core-java 0.1.12
[INFO] --- maven-jar-plugin: --- Building jar: ...\agent-core-java-0.1.12.jar
[INFO] --- maven-install-plugin: --- Installing ...\agent-core-java-0.1.12.jar to ...
[INFO] BUILD SUCCESS
```

### 步骤 3：创建示例项目 openjiuwen-java-demo

```powershell
cd D:\Dev\codex
mkdir openjiuwen-java-demo
cd openjiuwen-java-demo
```

#### 3.1 创建 `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>openjiuwen-java-demo</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>openjiuwen-java-demo</name>
    <description>Demo project for openJiuwen agent-core-java examples</description>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.openjiuwen</groupId>
            <artifactId>agent-core-java</artifactId>
            <version>0.1.12</version>
            <exclusions>
                <exclusion>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-simple</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.4.14</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

**关键依赖说明**:
- `agent-core-java 0.1.12`：核心框架，从本地 Maven 仓库引用（步骤 2 安装）
- `logback-classic 1.4.14`：替换框架自带的 `slf4j-simple`，提供更好的日志控制
- `exec-maven-plugin 3.1.0`：用于通过 `mvn exec:java` 直接运行示例

#### 3.2 创建目录结构

```
openjiuwen-java-demo/
├── pom.xml
├── run_demo.bat                      # Windows 批处理运行脚本
├── run_demo.ps1                      # PowerShell 运行脚本
├── src/main/java/
│   ├── com/example/
│   │   ├── deepagent/                # DeepAgent 规划/简单模式示例
│   │   │   ├── DeepAgentMinimalDemo.java
│   │   │   └── ConsoleStreamRenderer.java
│   │   ├── tool/                     # 工具类
│   │   │   ├── ApiConfigLoader.java  # API 配置加载器
│   │   │   └── MathTools.java        # 数学运算工具
│   │   └── session/                  # 会话持久化
│   │       ├── CheckPointerFile.java
│   │       └── FileCheckpointer.java
│   ├── myexample/
│   │   ├── deepagent/
│   │   │   └── SteerDemo.java        # Steer 引导示例
│   │   ├── stream/
│   │   │   └── ToolDemo.java         # 流式输出 + 工具调用
│   │   ├── multiquery/
│   │   │   └── ShortMemoryDemo.java  # 短记忆多查询
│   │   ├── sysop/
│   │   │   ├── SysOpExample.java     # 系统操作（文件读写）
│   │   │   └── ExecuteCmdExample.java# 命令执行
│   │   └── tool/
│   │       └── MathTools.java
│   └── examples/utils/
│       └── SharedExampleApiConfigLoader.java
└── src/main/resources/
    ├── apiconfig.json                # LLM API 配置（必须）
    └── logback.xml                   # 日志配置
```

#### 3.3 配置 LLM API

编辑 `src/main/resources/apiconfig.json`：

```json
{
  "API_BASE": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "API_KEY": "你的API密钥",
  "MODEL_PROVIDER": "OpenAI",
  "MODEL_NAME": "模型名称",
  "LLM_SSL_VERIFY": "true"
}
```

**支持的 API 提供商**:

| 提供商 | API_BASE | MODEL_NAME 示例 |
|--------|----------|----------------|
| 阿里云 DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-max`, `glm-5` |
| OpenAI 官方 | `https://api.openai.com/v1` | `gpt-4o`, `gpt-4` |
| 自建 OpenAI 兼容服务 | 自定义地址 | 自定义模型名 |

> **注意**: `MODEL_PROVIDER` 统一填写 `OpenAI`，因为框架使用 OpenAI 兼容协议。

#### 3.4 配置日志（可选）

编辑 `src/main/resources/logback.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="com.openjiuwen" level="INFO"/>
    <logger name="com.openjiuwen.core.foundation.llm" level="WARN"/>
    <root level="WARN">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

> **调试技巧**: 如果需要查看框架的详细日志，将 `com.openjiuwen` 的 level 改为 `DEBUG`。

### 步骤 4：编译示例项目

```powershell
$env:JAVA_HOME = "D:\Dev\codex\.sdk\jdk-17.0.13+11"
$env:PATH = "$env:JAVA_HOME\bin;D:\Dev\codex\.sdk\apache-maven-3.9.9\bin;$env:PATH"

cd D:\Dev\codex\openjiuwen-java-demo
mvn clean compile
```

预期输出：
```
[INFO] Compiling 13 source files to ...\openjiuwen-java-demo\target\classes
[INFO] BUILD SUCCESS
```

---

## 四、运行测试各示例

### 4.1 使用运行脚本

项目提供了 `run_demo.bat` 和 `run_demo.ps1` 脚本，已内置环境变量设置：

```batch
.\run_demo.bat list
```

输出可用示例列表：
```
Available demos:
  deepagent          - DeepAgent planning demo (stream + task planning)
  deepagent-simple   - DeepAgent simple ReAct mode
  steer              - Steer demo (auto mode)
  steer-interactive  - Steer demo (interactive mode)
  tool               - Tool demo (stream + multi-turn conversation)
  tool-query         - Tool demo (single query)
  shortmem           - ShortMemory demo (multi-query)
  sysop              - SysOp example (system operations)
  exec               - ExecuteCmd example
  checkpoint         - File checkpointer demo
```

### 4.2 手动运行（mvn exec:java）

```powershell
# 通用格式
mvn compile exec:java "-Dexec.mainClass=包名.类名" "-Dexec.args=参数" "-Dexec.jvmArgs=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8" -q
```

> **重要**: `-Dexec.jvmArgs` 中的 UTF-8 编码参数在 Windows 上**必须**设置，否则中文输出会出现乱码。

---

## 五、各示例详细测试

### 5.1 DeepAgent 规划模式

**类名**: `com.example.deepagent.DeepAgentMinimalDemo`  
**功能**: 基于 TaskPlanningRail 的多步任务规划，支持流式输出思考过程

```batch
.\run_demo.bat deepagent
```

**预期行为**:
1. 创建临时工作空间目录
2. Agent 自动规划任务（创建 A.txt → 生成随机字符串 → 读取并转小写 → 写入 B.txt）
3. 通过 `todo_create/list/modify` 工具管理任务清单
4. 流式输出推理过程（`llm_reasoning`）
5. 最终在工作空间中生成 A.txt 和 B.txt

**关键观察点**:
- 任务规划是否合理（通常 3 个 todo）
- 文件是否正确生成（检查 workspace 路径）
- 流式输出是否连贯

### 5.2 DeepAgent 简单模式

```batch
.\run_demo.bat deepagent-simple
```

**预期行为**:
- 基础 ReAct 模式，无任务规划
- 回答简单问题："用一两句话介绍 DeepAgent 是什么"
- 注册 `get_current_time` 工具

### 5.3 Steer 引导示例

```batch
# 自动模式（3 个阶段对比测试）
.\run_demo.bat steer

# 交互模式
.\run_demo.bat steer-interactive
```

**功能说明**:
- **Phase 1**: 使用 steer="请用简短方式回答"，验证引导生效
- **Phase 2**: 无 steer 对比测试
- **Phase 3**: steer + follow-up 组合，验证追加指令

### 5.4 工具调用与多轮对话

```batch
# 交互式多轮对话
.\run_demo.bat tool

# 单次查询
.\run_demo.bat tool-query "计算 3+5"
```

**功能说明**:
- 流式输出 + Redis Checkpointer 持久化
- 数学运算工具（加减乘除）
- 多轮对话上下文保持

**预期输出（单次查询）**:
```
[llm_reasoning] 用户要求计算 3 + 5，这是一个简单的加法运算。
[llm_output] 3 + 5 = **8**
```

### 5.5 短记忆多查询

```batch
.\run_demo.bat shortmem
```

**功能说明**:
- 短期记忆窗口：保留最近 3 轮对话
- 自动截断历史消息，防止上下文过长
- 内存模式（无需 Redis）

**关键观察点**:
- buffer 截断日志：`buffer 截断: 18 → 10 条消息（保留最近 3 轮）`
- 7 轮对话后仍能保持上下文连贯性

### 5.6 系统操作

```batch
# 文件操作
.\run_demo.bat sysop

# 命令执行
.\run_demo.bat exec
```

**功能说明**:
- `SysOpExample`: 文件读取（readFile）、目录列表（listDir）、文件搜索（searchFiles）
- `ExecuteCmdExample`: Shell 命令执行，支持 bash/sh 白名单

**已知限制**:
- `fs.listDir` 工具在 agent-core-java 0.1.12 中不可用
- `searchFiles` 参数为空时会抛出 NullPointerException

### 5.7 会话持久化

```batch
.\run_demo.bat checkpoint
```

**功能说明**:
- 文件 Checkpointer 实现
- 会话状态保存到本地文件系统
- 支持中断恢复

---

## 六、常见问题排查

### 6.1 编译失败

| 错误信息 | 原因 | 解决方案 |
|----------|------|----------|
| `Could not find artifact com.openjiuwen:agent-core-java:jar:0.1.12` | 未安装核心库到本地 Maven 仓库 | 先执行步骤 2：`mvn clean install -DskipTests` |
| `java.lang.UnsupportedClassVersionError` | JDK 版本不匹配 | 确保使用 JDK 17，检查 `$env:JAVA_HOME` |
| `Cannot find apiconfig.json` | 配置文件缺失 | 检查 `src/main/resources/apiconfig.json` 是否存在 |

### 6.2 运行时错误

| 错误信息 | 原因 | 解决方案 |
|----------|------|----------|
| `Missing required key in apiconfig.json: API_KEY` | API 配置不完整 | 编辑 apiconfig.json，填入完整的 API 信息 |
| 中文输出乱码 | Windows 编码问题 | 添加 JVM 参数 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` |
| `logback.xml` 重复警告 | classpath 中存在多个 logback 配置 | 可忽略，或在 pom.xml 中排除 agent-core-java 自带的 logback.xml |
| `NullPointerException` at searchFiles | SysOp searchFiles 参数为空 | 确保传入非空搜索参数 |

### 6.3 网络连接问题

| 现象 | 原因 | 解决方案 |
|------|------|----------|
| Maven 下载依赖失败 | 网络代理/防火墙 | 配置 Maven `settings.xml` 中的 mirror 或 proxy |
| LLM API 调用超时 | API 地址不可达 | 检查 API_BASE 地址，确认网络连通 |
| SSL 握手失败 | 证书问题 | 设置 `LLM_SSL_VERIFY` 为 `false`（仅调试用） |

---

## 七、项目关键源码解析

### 7.1 ApiConfigLoader（API 配置加载器）

**位置**: `src/main/java/com/example/tool/ApiConfigLoader.java`

加载优先级：
1. JVM 系统属性 `agent.config.path`
2. 环境变量 `AGENT_API_CONFIG`
3. 当前目录 `apiconfig.json`
4. `src/main/resources/apiconfig.json`
5. classpath 中的 `apiconfig.json`

### 7.2 DeepAgentMinimalDemo（核心示例）

**位置**: `src/main/java/com/example/deepagent/DeepAgentMinimalDemo.java`

两种模式：
- **规划模式**（默认）: 使用 `TaskPlanningRail` + `ToolTrackingRail`，maxIterations=18
- **简单模式**（`--simple`）: 基础 ReAct Agent，maxIterations=5

关键组件：
- `HarnessFactory.createDeepAgent()`: 创建 DeepAgent 实例
- `DeepAgentConfig`: 配置 Agent 行为参数
- `Workspace`: 指定工作空间路径和语言
- `Runner.runAgentStreaming()`: 启动流式 Agent 执行

### 7.3 运行脚本（run_demo.bat）

**位置**: `run_demo.bat`

脚本自动完成：
1. 设置 UTF-8 代码页（`chcp 65001`）
2. 设置 `JAVA_HOME` 和 `PATH`
3. 切换到项目目录
4. 根据参数选择对应的 `mainClass` 执行

---

## 八、完整搭建流程速查表

```
1. 安装 JDK 17          → 解压到 D:\Dev\codex\.sdk\jdk-17.0.13+11
2. 安装 Maven 3.9.9     → 解压到 D:\Dev\codex\.sdk\apache-maven-3.9.9
3. 克隆 agent-core-java → git clone --branch 0.1.12 --depth 1
4. 安装核心库           → cd agent-core-java && mvn clean install -DskipTests
5. 创建 demo 项目       → mkdir openjiuwen-java-demo && 创建 pom.xml + 源码
6. 配置 API             → 编辑 src/main/resources/apiconfig.json
7. 编译                 → mvn clean compile
8. 运行测试             → .\run_demo.bat deepagent
```

---

## 九、依赖版本清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| Eclipse Temurin JDK | 17.0.13+11 | Java 运行时 |
| Apache Maven | 3.9.9 | 构建工具 |
| agent-core-java | 0.1.12 | OpenJiuwen 核心框架 |
| Jackson | 2.17.0 | JSON 处理 |
| SLF4J | 2.0.12 | 日志门面 |
| Logback | 1.4.14 (demo) / 1.5.3 (core) | 日志实现 |
| Lombok | 1.18.32 | 代码简化 |
| exec-maven-plugin | 3.1.0 | Maven 运行插件 |

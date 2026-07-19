# NPC 环境搭建指南（WSL2 Ubuntu，无图形界面）

本文档记录在全新 WSL2 Ubuntu 系统上搭建 NPC（Chisel + Verilator 仿真）所需完整环境的步骤。

---

## 版本参考（本地已验证）

| 工具 | 版本 |
|------|------|
| Java (OpenJDK) | **17.0.16** |
| sbt | **1.11.6** |
| Scala | **2.13.10**（由 sbt 自动管理，无需单独安装） |
| Chisel3 | **3.6.0**（sbt 依赖，见 `build.sbt`） |
| chiseltest | **0.6.2** |
| Verilator | **5.041**（来自 oss-cad-suite **20251027**） |

---

## 第一步：系统基础包

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y \
    build-essential \
    g++ \
    make \
    git \
    curl \
    wget \
    gnupg \
    libreadline-dev \
    libsdl2-dev \
    libsdl2-image-dev \
    llvm \
    clang \
    python3 \
    python3-pip
```

说明：
- `build-essential` / `g++` / `make`：编译 Verilator 生成的 C++ 代码
- `libreadline-dev`：NEMU 行编辑（调试器输入）
- `libsdl2-dev` / `libsdl2-image-dev`：NEMU 设备模拟（无图形时可选，但 NEMU 编译时需要头文件）
- `llvm` / `clang`：NEMU 反汇编支持（`CONFIG_TARGET_NATIVE_ELF` 模式）

---

## 第二步：Java（OpenJDK 17）

**必须用 Java 17**，不要装 11 或 21，版本不匹配可能导致 sbt/Chisel 编译异常。

```bash
sudo apt install -y openjdk-17-jdk
```

验证：

```bash
java -version
# 应输出：openjdk version "17.x.x" ...
```

如果系统有多个 Java 版本，用 `update-alternatives` 切换：

```bash
sudo update-alternatives --config java
# 选择 java-17
```

---

## 第三步：sbt

通过官方 apt 仓库安装：

```bash
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | \
    sudo tee /etc/apt/sources.list.d/sbt.list

curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | \
    sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg

sudo apt update && sudo apt install -y sbt
```

验证（第一次运行会下载大量依赖，耗时较长，属于正常现象）：

```bash
sbt --version
# 应输出：sbt runner version: 1.x.x
```

> Scala 2.13.10 由 sbt 在首次编译时自动下载，无需额外安装。

---

## 第四步：Verilator（通过 oss-cad-suite）

**不要用 `apt install verilator`**，Ubuntu apt 源版本过旧（通常是 4.x），与项目不兼容。

从 oss-cad-suite 获取 Verilator 5.x：

### 4.1 下载

前往 [oss-cad-suite releases](https://github.com/YosysHQ/oss-cad-suite-build/releases)，下载 `linux-x64` 版本：

```bash
# 示例（以 20251027 为参考，建议用最新版本）
cd ~/
wget https://github.com/YosysHQ/oss-cad-suite-build/releases/download/2025-10-27/oss-cad-suite-linux-x64-20251027.tgz
```

### 4.2 解压

```bash
mkdir -p ~/oss-cad-suite
tar -xzf oss-cad-suite-linux-x64-*.tgz -C ~/
# 解压后目录为 ~/oss-cad-suite/
```

### 4.3 添加到 PATH

将以下内容加入 `~/.bashrc`（或 `~/.bash_profile`）：

```bash
# oss-cad-suite（包含 verilator）
export PATH="$HOME/oss-cad-suite/bin:$PATH"
```

生效：

```bash
source ~/.bashrc
```

验证：

```bash
verilator --version
# 应输出：Verilator 5.x ...
```

> 注意：项目 Makefile 中通过 `$(dirname $(dirname $(which verilator)))/share/verilator` 推断 `VERILATOR_ROOT`，只要 `verilator` 在 PATH 中这个逻辑就能自动找到 include 文件，无需手动设置 `VERILATOR_ROOT`。

---

## 第五步：环境变量

在 `~/.bashrc` 中补充以下内容（根据实际路径调整）：

```bash
# ysyx-workbench 路径
export YSYX_HOME=/path/to/ysyx-workbench

# 各子项目 HOME
export NEMU_HOME=$YSYX_HOME/nemu
export AM_HOME=$YSYX_HOME/abstract-machine
export NPC_HOME=$YSYX_HOME/npc
export NVBOARD_HOME=$YSYX_HOME/nvboard

# oss-cad-suite（若第四步已加则不需要重复）
export PATH="$HOME/oss-cad-suite/bin:$PATH"
```

```bash
source ~/.bashrc
```

---

## 第六步：克隆仓库（含 submodule）

```bash
git clone --recurse-submodules git@github.com:yamato720/ysyx-workbench.git
cd ysyx-workbench
```

若已克隆但忘记初始化子模块：

```bash
git submodule update --init --recursive
```

验证所有 submodule 已初始化（应有 4 条，无 `-` 前缀）：

```bash
git submodule status
# am-kernels            (heads/ics2021)
# fceux-am              (heads/ics2021)
# npc/VerilogVisualization  (heads/2.1)   ← 在 npc/ 子目录下，同样会被一并拉取
# nvboard               (heads/master)
```

> **注意**：`npc/VerilogVisualization` 虽然路径带有子目录 `npc/`，但它是在**根仓库的 `.gitmodules`** 中注册的一级 submodule，不需要额外操作，`--init --recursive` 会自动处理它。若某条显示 `-` 前缀（未初始化），可单独初始化：
>
> ```bash
> git submodule update --init npc/VerilogVisualization
> ```

---

## 第七步：验证构建

### 7.1 先编译 NEMU（NPC difftest 依赖它生成 .so）

```bash
cd $NEMU_HOME
make menuconfig    # 选 ISA=riscv64，开启 Build as shared library（CONFIG_TARGET_SHARE）
make -j$(nproc)
# 产物：build/riscv64-nemu-interpreter-so
```

### 7.2 生成统一 NPC 构造

```bash
cd $NPC_HOME
make config-list
make build config=NpcDpiConfig
```

正常完成后，`constructions/scpu.NpcDpiConfig/` 包含 RTL、Verilator ABI 和 NEMU host。

### 7.3 通过统一入口运行 NPC

```bash
cd $YSYX_HOME
make -C am-kernels/tests/cpu-tests run ALL=add config=NpcDpiConfig
```

---

## 常见问题

### sbt 下载依赖时网络超时

sbt 需要访问 Maven Central 和 Ivy2 仓库。在网络不稳定的 WSL2 环境中可以配置镜像：

在 `~/.sbt/1.0/global.sbt`（不存在就创建）中添加：

```scala
resolvers += "aliyun" at "https://maven.aliyun.com/repository/central"
```

### verilator: command not found

确认 `$HOME/oss-cad-suite/bin` 已加入 PATH，并执行了 `source ~/.bashrc`：

```bash
echo $PATH | grep oss-cad-suite
which verilator
```

### java: 版本不对 / sbt 报 unsupported class file

确认 `JAVA_HOME` 指向 JDK 17：

```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
java -version
```

### `make build config=NpcDpiConfig` 报 `g++: fatal error: verilated.h: No such file`

verilator 未在 PATH 中或 VERILATOR_ROOT 推断失败。检查：

```bash
which verilator
ls $(dirname $(dirname $(which verilator)))/share/verilator/include/verilated.h
```

若文件不存在，说明 oss-cad-suite 解压有问题，重新解压。

---

## 无图形界面说明

- 不需要安装 `gtkwave`（波形查看器，只在有 GUI 时用）
- NEMU 的 SDL2 相关设备模块在 `make menuconfig` 中可以关闭（`CONFIG_DEVICE`），若不需要运行带显示设备的程序可以不装 `libsdl2-dev`（但建议装上，以免切换配置时编译报错）

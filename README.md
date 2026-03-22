# "一生一芯"工程项目

这是"一生一芯"的工程项目. 通过运行
```bash
bash init.sh subproject-name
```
进行初始化, 具体请参考[实验讲义][lecture note].

[lecture note]: https://ysyx.oscc.cc/docs/

---

## 在新设备上一键还原

### 前提条件

1. 已安装 Git、Java（JDK 11+）、Scala/sbt、Verilator
2. 已配置 SSH key 并添加到 GitHub（验证：`ssh -T git@github.com` 返回成功）
3. 已安装 conda，并创建所需 Python/C++ 工具链环境

### 克隆还原

```bash
# 克隆主仓库，同时自动拉取所有 submodule（am-kernels、fceux-am、nvboard、npc/VerilogVisualization）
git clone --recurse-submodules git@github.com:yamato720/ysyx-workbench.git
cd ysyx-workbench
```

如果已经 clone 但忘记加 `--recurse-submodules`，补救：

```bash
git submodule update --init --recursive
```

### 设置环境变量

```bash
export NEMU_HOME=$(pwd)/nemu
export AM_HOME=$(pwd)/abstract-machine
export NPC_HOME=$(pwd)/npc
export NVBOARD_HOME=$(pwd)/nvboard
# 将以上内容加入 ~/.bashrc 以持久生效
echo "export NEMU_HOME=$(pwd)/nemu" >> ~/.bashrc
echo "export AM_HOME=$(pwd)/abstract-machine" >> ~/.bashrc
echo "export NPC_HOME=$(pwd)/npc" >> ~/.bashrc
echo "export NVBOARD_HOME=$(pwd)/nvboard" >> ~/.bashrc
source ~/.bashrc
```

### 各组件说明

| 组件 | 位置 | 来源 | 还原方式 |
|------|------|------|----------|
| NEMU 模拟器 | `nemu/` | 已嵌入主 repo | 自动克隆 |
| Abstract Machine | `abstract-machine/` | 已嵌入主 repo | 自动克隆 |
| am-kernels 测试集 | `am-kernels/` | fork: yamato720/am-kernels | submodule 自动拉取 |
| fceux-am NES模拟器 | `fceux-am/` | fork: yamato720/fceux-am | submodule 自动拉取 |
| nvboard 虚拟开发板 | `nvboard/` | fork: yamato720/nvboard | submodule 自动拉取 |
| NPC Chisel CPU | `npc/` | 已嵌入主 repo | 自动克隆 |
| VerilogVisualization | `npc/VerilogVisualization/` | yamato720/VerilogVisualization | submodule 自动拉取 |
| capstone 反汇编库 | `nemu/tools/capstone/repo/` | 上游 capstone-engine | 编译 NEMU 时 Makefile 自动 clone |
| spike-diff | `nemu/tools/spike-diff/repo/` | NJU-ProjectN/riscv-isa-sim | 编译 NEMU 时 Makefile 自动 clone |

### 编译 NPC（Chisel CPU）

```bash
cd npc
# 编译 Chisel 生成 Verilog，再用 Verilator 编译仿真库
make
```

### 同步上游更新

上游仓库为 `git@github.com:OSCPU/ysyx-workbench.git`，已作为 `upstream` remote 配置：

```bash
git fetch upstream
git merge upstream/master
git push origin
```


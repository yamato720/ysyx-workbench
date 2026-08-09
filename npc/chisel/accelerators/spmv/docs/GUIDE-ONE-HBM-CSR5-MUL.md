# 单 PC 公平共享 HBM 的 CSR5 v3 设计合同

> 对方已有Chisel与仿真框架。本文只规定算法原理、硬件架构和数据结构。
> 当前实现一个可复制的HBM cell；不实现32路顶层、RTL行归约、partial-y或最终Y。
> Verilator host负责自动二维分块、分轮发送，并在软件中恢复原CSR顺序完成结果校验。

## 1. 当前范围

最终32路理想架构由32个相同cell组成。第一阶段只实现：

```text
OneHbmCsr5MulCell
    = 同一 HBM PC 上的 A/X 双请求源
    + 全局 credit-aware burst 调度
    + CSR5 packet解包
    + paired-X 或 cached-X 静态构造
    + 8路FP32乘法
    + 带行信息的ProductBeat输出
```

当前停止在乘法结果：

```text
a = A[local_row, local_col]
x = X_block[local_col]
p = FP32(a * x)

output = (local_row, row_start, row_end, p, packet_context)
```

当前明确不做：

- 同行加法和CSR5 segmented sum。
- partial-y、Y缓存和Y写回。
- RTL内部的多矩阵块结果组合；仿真host在软件中完成组合。
- 32路HBM调度。
- PCG、Cholesky或其他迭代逻辑。

## 2. 固定参数

```text
HBM_PORTS             = 1
UNIT_ID               = 0          // cell实例参数
HBM_USER_DATA_BITS    = 512
HBM_USER_CLOCK_MHZ    = 225        // 逻辑用户接口目标

A_BITS                = 32         // FP32
X_BITS                = 32         // FP32
PRODUCT_BITS          = 32         // FP32
COORD_BITS            = 32

OMEGA                 = 8
SIGMA                 = 16
TILE_NNZ              = 128
RECORD_BITS           = 64
RECORDS_PER_BEAT      = 8
FULL_TILE_BEATS       = 17         // metadata 1 + payload 16

MAX_BLOCK_ROWS        = 8192
MAX_BLOCK_COLS        = 8192
MAX_OUTSTANDING       = 2          // 运行时可降为1
A_FIFO_BEATS          = 128
X_FIFO_BEATS          = 128
X_REPLICAS            = 0 paired / 4 cached
```

`512b @ 225MHz`是Vivado用户侧逻辑接口约定，不解释为HBM PHY时钟，也不代表已经时序收敛。

## 3. 硬件数据流

```text
                         one physical 512-bit PC
                                  |
                    shared credit-aware scheduler
                         /                    \
                  A beat FIFO             X beat FIFO
                       |                       |
              Metadata/CSR5 decoder     paired unpack / wide loader
                       |                       |
                       +-------- sequence/cache join
                         v
                +----------------+
                | Fp32MulArray8  |
                +-------+--------+
                        v
                +----------------+
                | Product FIFO   |
                +-------+--------+
                        v
                   ProductBeat
```

模块责任：

```text
SharedHbmReadScheduler  A/X按burst公平共享1或2个全局credit
A/X BeatFIFO            各保留两个完整64-beat burst
Csr5PacketDecoder   锁存metadata并解出8条record
PairedXUnpacker     每个X beat依次产生低、高两个八路group
WideXCache8R        cached构造每拍装入16项并提供8个任意读地址
Fp32MulArray8       8个吞吐1/cycle的FP32 multiplier
ProductFIFO         吸收下游反压
OneHbmCsr5MulCell   管理配置、start、drain、done、error和计数
```

Chisel类名可以遵循对方工程规范，但这些责任边界应保留。

## 4. 矩阵分块与寻址

Host预处理将原矩阵切成局部块：

```text
block.rows <= 8192
block.cols <= 8192
```

Host采用固定的列块优先顺序：先按`block_col_id`分组，组内再按`block_row_id`递增排列。
空块不生成packet；完全为空的列块不加载X，也不启动cell。同一列块中的所有非空行块共用一份X slice，
其packet串接为一次`config -> start -> drain`。每个块内部仍保持原CSR顺序，然后独立执行
128-NNZ Tile打包。

cell一次处理一个列块组，所有packet只使用：

```text
local_row in [0, 8192)
local_col in [0, current_column_block.cols)
XCache address = local_col
```

每个packet的Metadata v2携带自己的`block_row_id`、`block_col_id`、`block_row_base`和
`block_col_base`，因此同一stream可以连续包含多个行块。配置中的`block_rows`是本轮统一的局部坐标
上界，`block_cols`是当前X slice的实际长度。

全局坐标只用于后续定位：

```text
global_row = block_row_base + local_row
global_col = block_col_base + local_col
```

ProductBeat在host侧通过预处理时保存的全局NNZ下标回填。全部列块组完成后，host严格按原始CSR
非零元顺序执行FP32 RNE逐行归约，因此列块优先的发送顺序不会改变最终加法顺序。

合法最大局部坐标为`8191`；`8192`必须报告越界。

建议配置上下文：

```text
CellConfig {
    block_rows[13:0]       // 1..8192
    block_cols[13:0]       // 1..8192
    a_address / a_beats
    x_address / x_beats / x_crc32
    expected_packets / expected_product_beats / expected_products
    outstanding_limit      // 1或2，全PC共享
}
```

## 5. Coord32与NzRecord64

```text
Coord32[31]    valid
Coord32[30]    row_start
Coord32[29]    row_end
Coord32[28:16] local_row[12:0]
Coord32[15:13] reserved = 0
Coord32[12:0]  local_col[12:0]
```

```text
NzRecord64[63:32] = Coord32
NzRecord64[31:0]  = A_fp32
```

字段生命周期：

```text
local_col                  查询X后退休
A_fp32                     乘法后退休
local_row/start/end        随product输出
valid=0                    不读X、不启动乘法、不产生有效product
```

一个512位payload beat包含8条record，lane 0固定在低64位：

```text
Payload512[lane * 64 +: 64] = NzRecord64[lane]
```

## 6. CSR5预处理与Tile顺序

每个完整Tile从块内CSR顺序取得连续128个非零元，先解释成lane-major：

```text
lane 0 = source[  0 ..  15]
lane 1 = source[ 16 ..  31]
...
lane 7 = source[112 .. 127]
```

Host转置成HBM step-major：

```text
payload(step, lane) = source[lane * SIGMA + step]
```

输入顺序：

```text
beat 0      Metadata512
beat 1      step 0  × lanes 0..7
beat 2      step 1  × lanes 0..7
...
beat 16     step 15 × lanes 0..7
```

硬件不执行CSR5转置，只消费已转置payload。metadata只锁存context，不进入X查询和乘法器。

## 7. Metadata512 v2

低256位是8个lane summary：

```text
Metadata512[lane * 32 +: 32] = LaneSummary32[lane]
```

```text
LaneSummary32[31]    lane_valid
LaneSummary32[30]    head_continues
LaneSummary32[29]    tail_continues
LaneSummary32[28:24] segment_count
LaneSummary32[23:16] valid_steps
LaneSummary32[15:13] reserved
LaneSummary32[12:0]  first_row_local
```

当前乘法阶段只锁存或传递lane summary，不解释其归约语义。

高256位采用字节对齐布局：

```text
[263:256] format_version = 2
[271:264] unit_id[7:0]
[279:272] flags[7:0]
[287:280] payload_beats[7:0]
[303:288] valid_count[15:0]
[319:304] block_row_id[15:0]
[335:320] block_col_id[15:0]
[367:336] global_tile_id[31:0]
[399:368] block_tile_id[31:0]
[431:400] block_row_base[31:0]
[463:432] block_col_base[31:0]
[495:464] payload_crc32[31:0]
[511:496] reserved[15:0]
```

```text
flags[0] full
flags[1] tail
flags[2] transposed
flags[7:3] reserved = 0
```

decoder校验：

```text
unit_id == UNIT_ID
format_version == 2
full XOR tail == true
payload_beats in [1, 16]
valid_count in [1, 128]
all reserved bits == 0
```

完整Tile固定：

```text
full=1, tail=0, transposed=1
payload_beats=16, valid_count=128
```

`payload_crc32`覆盖当前packet的payload，不覆盖metadata。字节顺序为payload beat先后顺序，每个beat从`data[7:0]`到`data[511:504]`。

## 8. Tail

不足128 NNZ的块尾保持CSR顺序，不转置：

```text
Tail = Metadata512 + ceil(tail_nnz / 8) Payload512
```

```text
full=0
tail=1
transposed=0
valid_count=tail_nnz
payload_beats=ceil(tail_nnz / 8)
```

最后一个payload不足8条时，其余record全零，即`valid=0`。

## 9. paired-X与cached-X

paired构造不实例化X cache。一个512-bit X beat包含两个group：低256 bit对应A group `2k`，高256 bit
对应`2k+1`；每个group按lane 0..7放置八个FP32。tag、valid、local_col和last都由decoder顺序与
计数推导，无效lane和奇数组尾的高256 bit必须为零。

cached构造每个X beat包含连续16个FP32。A可以先进入128-beat FIFO，但decoder必须等全部X beat
写入并完成CRC检查后开始。四份cache都按一个宽word写入：

每个payload beat有8个任意`local_col`，稳态需要每拍8次随机X读取。

当前约定使用4份完整、内容相同的双端口存储：

```text
replica 0 port A/B -> lane 0/1
replica 1 port A/B -> lane 2/3
replica 2 port A/B -> lane 4/5
replica 3 port A/B -> lane 6/7
```

这样任意8个列索引都没有bank conflict，包括8个lane读取同一列。

容量与寻址：

```text
one replica   = 512 × 512 bit = 32 KiB
four replicas               = 128 KiB / cell
word address                 = local_col[12:4]
element select               = local_col[3:0]
```

两种构造都对全部原始X beat及其零填充执行IEEE reflected CRC32。

## 10. 乘法流水线

```text
cycle N:
    decode 8 records
    issue 8 X read addresses
    register A/row/flags/valid/context/step

cycle N + X_READ_LATENCY:
    receive 8 X values
    launch 8 FP32 multipliers

cycle N + X_READ_LATENCY + MUL_LATENCY:
    emit matching ProductBeat
```

FP32乘法：

```text
product_fp32 = round_to_fp32(A_fp32 * X_fp32)
```

当前没有加法器或FMA。FP32单元的NaN、Inf、denormal和rounding模式应与现有工程统一；输入预处理默认只接受有限A和X。

必须与乘积等深延迟：

```text
lane valid/row/start/end
global_tile_id/block_tile_id
block_row_id/block_col_id/block_row_base
step/tile_last
```

## 11. ProductBeat

```text
ProductLane {
    valid
    row_start
    row_end
    local_row[12:0]
    product_fp32[31:0]
}
```

```text
ProductBeat {
    global_tile_id[31:0]
    block_tile_id[31:0]
    block_row_id[15:0]
    block_col_id[15:0]
    block_row_base[31:0]
    step[4:0]
    tile_last
    lanes[8] : ProductLane
}
```

输出不携带`local_col`、原始A或X，它们已经完成生命周期。

每个输入payload beat对应一个ProductBeat。完整Tile输出16个ProductBeat；Tail输出`payload_beats`个，其中无效lane保持`valid=0`。

ProductBeat使用工程已有的ready/valid或Decoupled语义。下游反压时必须停住结果或写入elastic FIFO，不能丢弃已进入FP32流水线的数据。

## 12. 控制与握手

AXI ID 0表示A、ID 1表示X。scheduler最多接受两个AR，descriptor FIFO按AR接收顺序校验R ID/RLAST
并分流；任一来源空闲时另一来源可以占用全部credit。burst不得跨4 KiB。必须保证：

```text
beat顺序和512位边界不变
metadata与其payload连续归属于同一packet
反压时不丢失或重复beat
```

decoder最小状态：

```text
WAIT_METADATA
READ_PAYLOAD(payload_remaining, step, latched_context)
```

cell控制阶段：

```text
RESET -> READY -> RUN -> DRAIN -> DONE
```

最后一个HBM beat到达时不能立即DONE；必须等待X读、FP32流水线和ProductFIFO全部排空。

13位sticky error mask依次表示：control、X描述/装载、AXI、A stream、metadata、lane summary、
coord、valid count、A CRC、X CRC/count、join sequence、multiplier、drain/accounting。

## 13. 必须保持的不变量

```text
1. metadata永远不进入乘法器
2. 每条valid record恰好产生一条valid product
3. invalid record不读X、不产生valid product
4. product.local_row与源record一致
5. product的tile/step/context与源record一致
6. DONE时product_count == valid_record_count
7. cached X地址始终小于block.cols且小于8192
8. 完整Tile输入17 beats、输出16 ProductBeats
9. ready/valid反压不造成丢失、重复或重排
10. 连续packet之间context不串扰
```

## 14. 算法级验证场景

1. 一个完整Tile。
2. 小于8 NNZ的Tail。
3. 129 NNZ：完整Tile加Tail。
4. 8个lane读取同一个X地址。
5. 8个lane读取8个不同X地址。
6. `local_col=0`和`local_col=8191`。
7. 越界`local_col=8192`。
8. payload中部分lane无效。
9. 连续16拍payload无气泡。
10. HBM输入随机暂停。
11. Product输出随机反压。
12. 两个packet流水重叠且context正确。
13. 最后输入后正确DRAIN。
14. 每条product的FP32位模式与参考乘法一致。
15. 每条product的行、flags、tile和step与源record一致。
16. paired奇数组尾、cached尾元素和所有无效lane都保持零填充。
17. outstanding 1/2只改变周期，不改变任何ProductBeat或最终FP32结果。

## 15. 单cell吞吐与资源数量级

完整Tile为128条乘法和17个输入beats：

```text
128 / 17 = 7.529 multiplications / user cycle
```

逻辑目标225 MHz下约为：

```text
1.694 billion FP32 multiplications / second / cell
```

这是接口理论上限，未扣除X加载、HBM burst间隙、Tail和反压。

单cell逻辑资源核心为：

```text
8个FP32 multiplier
paired: 无X cache
cached: 128 KiB宽X cache
2 x 128-beat输入FIFO
Product FIFO
```

应先获得单cell综合资源与Fmax，再判断32倍复制是否可行。

## 16. 扩展到32路

单cell验证后，理想顶层关系是：

```text
HBM_PORT[i] -> OneHbmCsr5MulCell(UNIT_ID=i), i=0..31
```

原样复制：

```text
NzRecord64与Metadata512 v2
XCache8R
CSR5 decoder
8-lane FP32乘法流水线
ProductBeat
ready/valid和错误语义
```

32路阶段才新增：

```text
Tile到端口的分配
按paired/cached构造复制X路径
CSR5行归约
partial-y合并
最终Y写回
```

理论数量级为256个FP32 multiplier；cached版本另含4 MiB X cache，但不能在单cell综合前视为已可实现。

## 17. 当前验收边界

- 正确解析完整Tile和Tail。
- paired按两个group/beat消费X，且RTL中不存在宽cache。
- cached每拍加载16项X到4份宽replica。
- A/X公平共享一个PC及全局1或2个outstanding credit。
- 每拍对8条record发起独立X查询。
- 固定latency后正确输出8路FP32乘积及全部sideband。
- 连续输入和随机反压下满足全部不变量。
- 输出严格停在ProductBeat，不包含行求和或Y状态。
- 记录单cell综合资源与时序，作为是否扩展到32路的依据。

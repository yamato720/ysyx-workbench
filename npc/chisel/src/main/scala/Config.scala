package scpu

// ─────────────────────────────────────────────────────────────────────────────
// ExtSelBits: bit-position constants for the runtime `extSel` signal.
//   Each bit indicates which functional unit's result to route.
//   Only extensions that own a separate functional unit get a bit here.
//   Adding a new unit: one val here + one case‑class field in ISAConfig below.
// ─────────────────────────────────────────────────────────────────────────────
object ExtSelBits {
  val extSelWidth = 8   // fixed-width extSel bus
  val I = 0             // bit 0 — base integer ALU (RVI)
  val Pri = 1           // bit 1 — priority encoder (Zicsr)
  val M = 2             // bit 2 — mul / div unit   (M extension)
  val F = 3             // bit 3 — FPU single‑prec  (F extension, future)
  val D = 4             // bit 4 — FPU double‑prec  (D extension, future)
  val V = 5             // bit 5 — vector unit       (V extension, future)
  // bits 6‑7 reserved
}

// ─────────────────────────────────────────────────────────────────────────────
// ISAConfig: compile-time ISA feature switches.
//   xlen      — XLEN (register width): 32 or 64.
//   Single-letter standard extensions follow RISC-V spec naming.
//   Z-extensions are listed after; only the ones actually decoded matter.
//   Extensions not yet implemented are commented out for future reference.
// ─────────────────────────────────────────────────────────────────────────────
case class ISAConfig(
  // ── XLEN ──────────────────────────────────────────────────────────────────
  xlen:     Int     = 64,    // register width: 32 (RV32) or 64 (RV64)
  // ── Standard single-letter extensions ────────────────────────────────────
  M:        Boolean = false, // integer multiply / divide
  // A:     Boolean = false, // atomic memory operations (LR/SC/AMO)
  // F:     Boolean = false, // single-precision float
  // D:     Boolean = false, // double-precision float (requires F)
  // Q:     Boolean = false, // quad-precision float   (requires D)
  // C:     Boolean = false, // compressed (16-bit) instructions
  // V:     Boolean = false, // vector operations
  // H:     Boolean = false, // hypervisor
  // ── Z sub-extensions ─────────────────────────────────────────────────────
  Zicsr:    Boolean = true,  // CSR instructions (ecall / mret always synthesised)
  // Zifencei: Boolean = false, // instruction-fetch fence (fence.i)
  // Zba:   Boolean = false, // address-generation bit-manip (sh1add etc.)
  // Zbb:   Boolean = false, // basic bit-manip (clz, ctz, cpop, min, max …)
  // Zbc:   Boolean = false, // carry-less multiply (clmul / clmulh / clmulr)
  // Zbs:   Boolean = false, // single-bit instructions (bset / bclr / binv / bext)
  // Zicond: Boolean = false, // integer conditional operations (czero.eqz etc.)
  // Ztso:  Boolean = false, // total-store-ordering memory model
)

// ─────────────────────────────────────────────────────────────────────────────
// CSRMap: machine-mode CSR address constants (RISC-V privileged spec §2).
//   addr  — 12-bit CSR address as used in CSRRW / CSRRS / CSRRC instructions.
//   Entries are pure Scala Ints; use `.U(12.W)` at the Chisel call site.
// ─────────────────────────────────────────────────────────────────────────────
object CSRMap {
  // ── Machine Information Registers ─────────────────────────────────────────
  val mvendorid = 0xF11  // Vendor ID (read-only)
  val marchid   = 0xF12  // Architecture ID (read-only)
  val mimpid    = 0xF13  // Implementation ID (read-only)
  val mhartid   = 0xF14  // Hardware thread ID (read-only)
  // ── Machine Trap Setup ────────────────────────────────────────────────────
  val mstatus   = 0x300  // Machine status register
  val misa      = 0x301  // ISA and extensions (read-only in this impl)
  val mie       = 0x304  // Machine interrupt-enable register
  val mtvec     = 0x305  // Machine trap-handler base address
  // ── Machine Trap Handling ─────────────────────────────────────────────────
  val mscratch  = 0x340  // Scratch register for machine trap handlers
  val mepc      = 0x341  // Machine exception program counter
  val mcause    = 0x342  // Machine trap cause
  val mtval     = 0x343  // Machine bad address or instruction
  val mip       = 0x344  // Machine interrupt pending

}

// ─────────────────────────────────────────────────────────────────────────────
// DeviceMap: physical address ranges for memory-mapped peripherals.
//   Keys are human-readable device names; values are inclusive [start, end].
//   Device index in startAddrs insertion order determines the enable-bit number
//   (bit 0 = "physiscal memory", bit 1 = "serial", …).
// ─────────────────────────────────────────────────────────────────────────────
object DeviceMap {
  val startAddrs: Map[String, Long] = Map(
    "physiscal memory" -> 0x80000000L,
    "serial"           -> 0xa00003f8L,
    "rtc"              -> 0xa0000048L,
    "vgactrl"          -> 0xa0000100L,
    "vmem"             -> 0xa1000000L,
    "keyboard"         -> 0xa0000060L,
    "audio"            -> 0xa0000200L,
    "audio-sbuf"       -> 0xa1200000L
    // "CSRs"             -> 0x000L      // full 12-bit CSR address space (RISC-V priv spec)
  )
  val endAddrs: Map[String, Long] = Map(
    "physiscal memory" -> 0x8fffffffL,
    "serial"           -> 0xa00003ffL,
    "rtc"              -> 0xa000004fL,
    "vgactrl"          -> 0xa0000107L,
    "vmem"             -> 0xa10752ffL,
    "keyboard"         -> 0xa0000063L,
    "audio"            -> 0xa0000217L,
    "audio-sbuf"       -> 0xa120ffffL
    // "CSRs"             -> 0xFFFl      // full 12-bit CSR address space (RISC-V priv spec)
  )
  // 纯 Scala Int，可在任意 Chisel 类中以 DeviceMap.device_num.W 的形式使用
  val device_num: Int = startAddrs.size
}

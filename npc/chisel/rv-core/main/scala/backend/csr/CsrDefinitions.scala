package scpu

/** 机器态内核已实现的架构 CSR 地址。 */
object CsrAddress {
  val fflags = 0x001
  val frm = 0x002
  val fcsr = 0x003

  val mvendorid = 0xF11
  val marchid = 0xF12
  val mimpid = 0xF13
  val mhartid = 0xF14
  val mstatus = 0x300
  val misa = 0x301
  val mie = 0x304
  val mtvec = 0x305
  val mscratch = 0x340
  val mepc = 0x341
  val mcause = 0x342
  val mtval = 0x343
  val mip = 0x344
}

/** mip 与 mie 共用的位位置。 */
object CsrInterruptBit {
  val usip = 0
  val ssip = 1
  val msip = 3
  val utip = 4
  val stip = 5
  val mtip = 7
  val ueip = 8
  val seip = 9
  val meip = 11
}

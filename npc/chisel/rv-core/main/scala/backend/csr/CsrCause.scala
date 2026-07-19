package scpu

/** 写入 mcause 的同步异常原因码。 */
object CsrCause {
  val misalignedFetch = 0x0
  val fetchAccess = 0x1
  val illegalInstruction = 0x2
  val breakpoint = 0x3
  val misalignedLoad = 0x4
  val loadAccess = 0x5
  val misalignedStore = 0x6
  val storeAccess = 0x7
  val userEcall = 0x8
  val supervisorEcall = 0x9
  val virtualSupervisorEcall = 0xa
  val machineEcall = 0xb
  val fetchPageFault = 0xc
  val loadPageFault = 0xd
  val storePageFault = 0xf
  val fetchGuestPageFault = 0x14
  val loadGuestPageFault = 0x15
  val virtualInstruction = 0x16
  val storeGuestPageFault = 0x17

  val all: Array[Int] = Array(
    misalignedFetch, fetchAccess, illegalInstruction, breakpoint,
    misalignedLoad, loadAccess, misalignedStore, storeAccess,
    userEcall, supervisorEcall, virtualSupervisorEcall, machineEcall,
    fetchPageFault, loadPageFault, storePageFault, fetchGuestPageFault,
    loadGuestPageFault, virtualInstruction, storeGuestPageFault
  )
}

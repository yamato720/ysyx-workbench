package npc.fpga

/** Selects whether an FPGA terminal implements the interactive SDB control
  * plane.  This is deliberately independent from NEMU's host-side SDB UI:
  * it decides whether halt/step and wide architectural snapshots exist in
  * the synthesized mailbox.
  */
final case class FpgaRuntimeSdbConfig(enabled: Boolean)

object FpgaRuntimeSdbConfig {
  val Enabled: FpgaRuntimeSdbConfig = FpgaRuntimeSdbConfig(enabled = true)
  val Disabled: FpgaRuntimeSdbConfig = FpgaRuntimeSdbConfig(enabled = false)
}

package scpu
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class metronome_test extends AnyFlatSpec with ChiselScalatestTester {
  "Metronome" should "tick at regular intervals" in {
    test(new Metronome())
      .withAnnotations(Seq(WriteVcdAnnotation))
    { c =>
      c.io.stuck.poke(false.B)  // 使用 .poke() 而不是 :=
      c.reset.poke(true.B)
      c.clock.step(1)
      c.reset.poke(false.B)
      c.clock.step(1)
      // 观察输出
      for(i <- 0 until 200) {
        c.clock.step(1)
      }
    }
  }

}

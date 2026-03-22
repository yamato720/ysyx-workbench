package scpu
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

class alu_test extends AnyFlatSpec with ChiselScalatestTester {
  "ALU" should "correctly perform addition" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation))
      { c =>

      c.io.alu_ctrl_in.poke("b00000".U) // ADD operation
      c.io.pc.poke(0.U)
      val rand = new Random(42) // 固定种子，可复现

      // 随机测试 100 次
      for(i <- 0 until 100) {
        var a = rand.nextInt(1000)
        var b = rand.nextInt(1000)

        // 随机生成负数
        if (rand.nextInt(2) == 1) {
          a = a * -1
        }
        if (rand.nextInt(2) == 1) {
          b = b * -1
        }

        // 将有符号数转换为32位无符号表示（补码）
        val a_unsigned = if (a < 0) (0x100000000L + a) else a.toLong
        val b_unsigned = if (b < 0) (0x100000000L + b) else b.toLong

        val expected = (a + b) & 0xFFFFFFFFL // 32位结果

        c.io.a_in.poke(a_unsigned.U)
        c.io.b_in.poke(b_unsigned.U)

        // 第1步：tick_idex=1，锁存输入数据
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)

        // 第2步：tick_idex=0，执行运算
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        // 第3步：读取结果
        c.io.alu_result.expect(expected.U, s"ADD failed: $a + $b = ${a + b}, expected=$expected, a_unsigned=$a_unsigned, b_unsigned=$b_unsigned")
        c.io.zero.expect((expected == 0).B, s"Zero flag failed for: $a + $b = $expected")
        c.io.branch_taken.expect(0.U, s"Branch taken should be 0 for ADD: $a + $b")
      }
    }
  }

  it should "detect overflow in addition" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      // Test overflow: 0x7FFFFFFF + 1 = overflow
      c.io.a_in.poke("h7FFFFFFF".U)
      c.io.b_in.poke(1.U)
      c.io.alu_ctrl_in.poke("b00000".U)
      c.io.pc.poke(0.U)
      c.io.tick_idex.poke(true.B)
      c.clock.step(1)
      c.io.tick_idex.poke(false.B)
      c.clock.step(1)
      c.io.overflow.expect(true.B)
    }
  }

  it should "correctly perform subtraction" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00001".U) // SUB operation
      c.io.pc.poke(0.U)
      val rand = new Random(43)

      for(_ <- 0 until 100) {
        var a = rand.nextInt(1000)
        var b = rand.nextInt(1000)

        if (rand.nextInt(2) == 1) a = a * -1
        if (rand.nextInt(2) == 1) b = b * -1

        val a_unsigned = if (a < 0) (0x100000000L + a) else a.toLong
        val b_unsigned = if (b < 0) (0x100000000L + b) else b.toLong
        val expected = (a - b) & 0xFFFFFFFFL

        c.io.a_in.poke(a_unsigned.U)
        c.io.b_in.poke(b_unsigned.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"SUB failed: $a - $b")
        c.io.zero.expect((expected == 0).B)
      }
    }
  }

  it should "correctly identify zero result" in {
    test(new ALU) { c =>
      // Test subtraction: 3 - 3 = 0
      c.io.a_in.poke(3.U)
      c.io.b_in.poke(3.U)
      c.io.alu_ctrl_in.poke("b00001".U)
      c.io.pc.poke(0.U)
      c.io.tick_idex.poke(true.B)
      c.clock.step(1)
      c.io.tick_idex.poke(false.B)
      c.clock.step(1)
      c.io.alu_result.expect(0.U)
      c.io.zero.expect(true.B)
    }
  }

  it should "correctly perform NOT operation" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00010".U) // NOT operation
      c.io.pc.poke(0.U)
      val rand = new Random(44)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val expected = (~a) & 0xFFFFFFFFL

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(0.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"NOT failed: ~$a")
      }
    }
  }

  it should "correctly perform AND operation" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00011".U) // AND operation
      c.io.pc.poke(0.U)
      val rand = new Random(45)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = rand.nextLong() & 0xFFFFFFFFL
        val expected = a & b

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"AND failed: $a & $b")
      }
    }
  }

  it should "correctly perform OR operation" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00100".U) // OR operation
      c.io.pc.poke(0.U)
      val rand = new Random(46)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = rand.nextLong() & 0xFFFFFFFFL
        val expected = a | b

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"OR failed: $a | $b")
      }
    }
  }

  it should "correctly perform XOR operation" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00101".U) // XOR operation
      c.io.pc.poke(0.U)
      val rand = new Random(47)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = rand.nextLong() & 0xFFFFFFFFL
        val expected = a ^ b

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"XOR failed: $a ^ $b")
      }
    }
  }

  it should "correctly perform BLT (signed less than)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00110".U) // BLT operation
      c.io.pc.poke(0.U)
      val rand = new Random(48)

      for(_ <- 0 until 100) {
        var a = rand.nextInt(1000)
        var b = rand.nextInt(1000)

        if (rand.nextInt(2) == 1) a = a * -1
        if (rand.nextInt(2) == 1) b = b * -1

        val a_unsigned = if (a < 0) (0x100000000L + a) else a.toLong
        val b_unsigned = if (b < 0) (0x100000000L + b) else b.toLong
        val expected_result = if (a < b) 1L else 0L
        val expected_branch = if (a < b) "b001".U else 0.U

        c.io.a_in.poke(a_unsigned.U)
        c.io.b_in.poke(b_unsigned.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected_result.U, s"BLT failed: $a < $b")
        c.io.branch_taken.expect(expected_branch, s"BLT branch failed: $a < $b")
      }
    }
  }

  it should "correctly perform BEQ (branch equal)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b00111".U) // BEQ operation
      c.io.pc.poke(0.U)
      val rand = new Random(49)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = if (rand.nextInt(3) == 0) a else rand.nextLong() & 0xFFFFFFFFL  // 1/3 概率相等
        val expected_result = if (a == b) 1L else 0L
        val expected_branch = if (a == b) "b001".U else 0.U

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected_result.U, s"BEQ failed: $a == $b")
        c.io.branch_taken.expect(expected_branch, s"BEQ branch failed: $a == $b")
      }
    }
  }

  it should "correctly perform SLL (shift left logical)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01000".U) // SLL operation
      c.io.pc.poke(0.U)
      val rand = new Random(50)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val shift = rand.nextInt(32)  // 移位量 0-31
        val expected = (a << shift) & 0xFFFFFFFFL

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(shift.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"SLL failed: $a << $shift")
      }
    }
  }

  it should "correctly perform BLTU (unsigned less than)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01001".U) // BLTU operation
      c.io.pc.poke(0.U)
      val rand = new Random(51)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = rand.nextLong() & 0xFFFFFFFFL
        val expected_result = if (a < b) 1L else 0L
        val expected_branch = if (a < b) "b001".U else 0.U

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected_result.U, s"BLTU failed: $a < $b (unsigned)")
        c.io.branch_taken.expect(expected_branch, s"BLTU branch failed: $a < $b")
      }
    }
  }

  it should "correctly perform SRL (shift right logical)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01010".U) // SRL operation
      c.io.pc.poke(0.U)
      val rand = new Random(52)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val shift = rand.nextInt(32)
        val expected = (a >> shift) & 0xFFFFFFFFL

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(shift.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"SRL failed: $a >> $shift")
      }
    }
  }

  it should "correctly perform SRA (shift right arithmetic)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01011".U) // SRA operation
      c.io.pc.poke(0.U)
      val rand = new Random(53)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val shift = rand.nextInt(32)
        // 算术右移：保持符号位
        val a_signed = if (a >= 0x80000000L) a - 0x100000000L else a
        val expected = ((a_signed >> shift) & 0xFFFFFFFFL)

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(shift.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"SRA failed: $a >> $shift (arithmetic)")
      }
    }
  }

  it should "correctly perform LUI (load upper immediate)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01100".U) // LUI operation
      c.io.pc.poke(0.U)
      val rand = new Random(54)

      for(_ <- 0 until 100) {
        val imm = rand.nextLong() & 0xFFFFFFFFL
        val expected = imm

        c.io.a_in.poke(0.U)
        c.io.b_in.poke(imm.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"LUI failed: imm=$imm")
      }
    }
  }

  it should "correctly perform AUIPC (add upper immediate to PC)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01101".U) // AUIPC operation
      val rand = new Random(55)

      for(_ <- 0 until 100) {
        val pc = rand.nextLong() & 0xFFFFFFFFL
        val imm = rand.nextLong() & 0xFFFFFFFFL
        val expected = (pc + imm) & 0xFFFFFFFFL

        c.io.a_in.poke(0.U)
        c.io.b_in.poke(imm.U)
        c.io.pc.poke(pc.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected.U, s"AUIPC failed: PC=$pc + imm=$imm")
      }
    }
  }

  it should "correctly perform BNE (branch not equal)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01110".U) // BNE operation
      c.io.pc.poke(0.U)
      val rand = new Random(56)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = if (rand.nextInt(3) == 0) a else rand.nextLong() & 0xFFFFFFFFL  // 1/3 概率相等
        val expected_result = if (a != b) 1L else 0L
        val expected_branch = if (a != b) "b001".U else 0.U

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected_result.U, s"BNE failed: $a != $b")
        c.io.branch_taken.expect(expected_branch, s"BNE branch failed: $a != $b")
      }
    }
  }

  it should "correctly perform BGE (signed greater or equal)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b01111".U) // BGE operation
      c.io.pc.poke(0.U)
      val rand = new Random(57)

      for(_ <- 0 until 100) {
        var a = rand.nextInt(1000)
        var b = rand.nextInt(1000)

        if (rand.nextInt(2) == 1) a = a * -1
        if (rand.nextInt(2) == 1) b = b * -1

        val a_unsigned = if (a < 0) (0x100000000L + a) else a.toLong
        val b_unsigned = if (b < 0) (0x100000000L + b) else b.toLong
        val expected_result = if (a >= b) 1L else 0L
        val expected_branch = if (a >= b) "b001".U else 0.U

        c.io.a_in.poke(a_unsigned.U)
        c.io.b_in.poke(b_unsigned.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected_result.U, s"BGE failed: $a >= $b")
        c.io.branch_taken.expect(expected_branch, s"BGE branch failed: $a >= $b")
      }
    }
  }

  it should "correctly perform BGEU (unsigned greater or equal)" in {
    test(new ALU)
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.alu_ctrl_in.poke("b10000".U) // BGEU operation
      c.io.pc.poke(0.U)
      val rand = new Random(58)

      for(_ <- 0 until 100) {
        val a = rand.nextLong() & 0xFFFFFFFFL
        val b = rand.nextLong() & 0xFFFFFFFFL
        val expected_result = if (a >= b) 1L else 0L
        val expected_branch = if (a >= b) "b001".U else 0.U

        c.io.a_in.poke(a.U)
        c.io.b_in.poke(b.U)
        c.io.tick_idex.poke(true.B)
        c.clock.step(1)
        c.io.tick_idex.poke(false.B)
        c.clock.step(1)

        c.io.alu_result.expect(expected_result.U, s"BGEU failed: $a >= $b (unsigned)")
        c.io.branch_taken.expect(expected_branch, s"BGEU branch failed: $a >= $b")
      }
    }
  }

  it should "correctly perform JALR (jump and link register)" in {
    test(new ALU) { c =>
      c.io.a_in.poke(0.U)
      c.io.b_in.poke(0.U)
      c.io.alu_ctrl_in.poke("b10001".U)
      c.io.pc.poke("h100".U)
      c.io.tick_idex.poke(true.B)
      c.clock.step(1)
      c.io.tick_idex.poke(false.B)
      c.clock.step(1)
      c.io.alu_result.expect("h104".U)
      c.io.branch_taken.expect("b011".U)
    }
  }

  it should "correctly perform JAL (jump and link)" in {
    test(new ALU) { c =>
      c.io.a_in.poke(0.U)
      c.io.b_in.poke(0.U)
      c.io.alu_ctrl_in.poke("b10010".U)
      c.io.pc.poke("h100".U)
      c.io.tick_idex.poke(true.B)
      c.clock.step(1)
      c.io.tick_idex.poke(false.B)
      c.clock.step(1)
      c.io.alu_result.expect("h104".U)
      c.io.branch_taken.expect("b010".U)
    }
  }
}

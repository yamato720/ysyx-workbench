package spmv

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import npc.SpmvCsr5MulConfig
import org.scalatest.flatspec.AnyFlatSpec

class SpmvOneHbmCsr5MulTest extends AnyFlatSpec {
  private val pairedConfig = SpmvCsr5MulConfig.OneHbmFp32X8192Paired
  private val cachedConfig = SpmvCsr5MulConfig.OneHbmFp32X8192Cached

  private final case class Record(row: Int, col: Int, rowStart: Boolean, rowEnd: Boolean,
                                  a: Long = 0x3f800000L, valid: Boolean = true) {
    val coord: Long = (if (valid) 1L << 31 else 0L) |
      (if (rowStart) 1L << 30 else 0L) | (if (rowEnd) 1L << 29 else 0L) |
      (row.toLong << 16) | col.toLong
    val word: BigInt = (BigInt(coord) << 32) | BigInt(a)
  }

  private def payloadBeat(records: Seq[Record]): BigInt =
    records.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (record, lane)) =>
      bits | (record.word << (lane * 64))
    }

  private def wordsBeat(words: Seq[Long]): BigInt =
    words.zipWithIndex.foldLeft(BigInt(0)) { case (bits, (word, index)) =>
      bits | (BigInt(word & 0xffffffffL) << (index * 32))
    }

  private def crc32(beats: Seq[BigInt]): Long = {
    var crc = 0xffffffffL
    for (beat <- beats; byte <- 0 until 64) {
      crc ^= (beat >> (byte * 8)).toLong & 0xffL
      for (_ <- 0 until 8) {
        crc = (crc >>> 1) ^ (if ((crc & 1L) != 0) 0xedb88320L else 0L)
      }
    }
    (crc ^ 0xffffffffL) & 0xffffffffL
  }

  private def laneSummary(payload: Seq[Seq[Record]], lane: Int): Long = {
    val records = payload.flatMap(_.lift(lane)).filter(_.valid)
    if (records.isEmpty) 0L
    else {
      val segments = 1 + records.sliding(2).count(pair => pair.head.row != pair.last.row)
      (1L << 31) | (if (!records.head.rowStart) 1L << 30 else 0L) |
        (if (!records.last.rowEnd) 1L << 29 else 0L) |
        (segments.toLong << 24) | (records.size.toLong << 16) | records.head.row.toLong
    }
  }

  private def metadata(payload: Seq[Seq[Record]], full: Boolean, validCount: Int,
                       version: Int = 2, expectedCrc: Option[Long] = None): BigInt = {
    val payloadBits = payload.map(payloadBeat)
    val summaries = (0 until 8).foldLeft(BigInt(0)) { case (bits, lane) =>
      bits | (BigInt(laneSummary(payload, lane)) << (lane * 32))
    }
    val flags = if (full) 0x5 else 0x2
    summaries | (BigInt(version) << 256) | (BigInt(flags) << 272) |
      (BigInt(payload.size) << 280) | (BigInt(validCount) << 288) |
      (BigInt(expectedCrc.getOrElse(crc32(payloadBits))) << 464)
  }

  private def initializeScheduler(dut: SharedHbmReadScheduler, aAddress: Long, aBeats: Int,
                                  xAddress: Long, xBeats: Int, limit: Int): Unit = {
    dut.io.start.poke(false.B)
    dut.io.aAddress.poke(aAddress.U)
    dut.io.aBeats.poke(aBeats.U)
    dut.io.xAddress.poke(xAddress.U)
    dut.io.xBeats.poke(xBeats.U)
    dut.io.outstandingLimit.poke(limit.U)
    dut.io.axi.ar.ready.poke(false.B)
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.id.poke(0.U)
    dut.io.axi.r.bits.data.poke(0.U)
    dut.io.axi.r.bits.resp.poke(0.U)
    dut.io.axi.r.bits.last.poke(false.B)
    dut.io.aOutput.ready.poke(true.B)
    dut.io.xOutput.ready.poke(true.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
  }

  private def acceptAr(dut: SharedHbmReadScheduler): Unit = {
    dut.io.axi.ar.valid.expect(true.B)
    dut.io.axi.ar.ready.poke(true.B)
    dut.clock.step()
    dut.io.axi.ar.ready.poke(false.B)
  }

  private def returnBeat(dut: SharedHbmReadScheduler, id: Int, last: Boolean,
                         response: Int = 0): Unit = {
    dut.io.axi.r.valid.poke(true.B)
    dut.io.axi.r.bits.id.poke(id.U)
    dut.io.axi.r.bits.data.poke((0x1000L + id).U)
    dut.io.axi.r.bits.resp.poke(response.U)
    dut.io.axi.r.bits.last.poke(last.B)
    while (!dut.io.axi.r.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.axi.r.valid.poke(false.B)
  }

  "SharedHbmReadScheduler" should "start with A and fairly share two global credits" in {
    simulate(new SharedHbmReadScheduler(pairedConfig)) { dut =>
      initializeScheduler(dut, 0x80000fc0L, 65, 0x80010000L, 64, limit = 2)

      dut.io.axi.ar.bits.id.expect(0.U)
      dut.io.axi.ar.bits.addr.expect(0x80000fc0L.U)
      dut.io.axi.ar.bits.len.expect(0.U)
      acceptAr(dut)
      dut.io.axi.ar.bits.id.expect(1.U)
      dut.io.axi.ar.bits.addr.expect(0x80010000L.U)
      dut.io.axi.ar.bits.len.expect(63.U)
      acceptAr(dut)
      dut.io.axi.ar.valid.expect(false.B)

      returnBeat(dut, id = 0, last = true)
      dut.io.axi.ar.bits.id.expect(0.U)
      dut.io.axi.ar.bits.addr.expect(0x80001000L.U)
      dut.io.axi.ar.bits.len.expect(63.U)
      dut.io.aBurstCount.expect(1.U)
      dut.io.xBurstCount.expect(1.U)
      dut.io.error.expect(false.B)
    }
  }

  it should "let one source occupy both credits and honor runtime limit one" in {
    simulate(new SharedHbmReadScheduler(pairedConfig)) { dut =>
      initializeScheduler(dut, 0x80000000L, 128, 0x80020000L, 0, limit = 2)
      dut.io.axi.ar.bits.id.expect(0.U)
      dut.io.axi.ar.bits.len.expect(63.U)
      acceptAr(dut)
      dut.io.axi.ar.bits.id.expect(0.U)
      dut.io.axi.ar.bits.addr.expect(0x80001000L.U)
      acceptAr(dut)
      dut.io.axi.ar.valid.expect(false.B)
    }

    simulate(new SharedHbmReadScheduler(pairedConfig)) { dut =>
      initializeScheduler(dut, 0x80000000L, 64, 0x80010000L, 64, limit = 1)
      dut.io.axi.ar.bits.id.expect(0.U)
      acceptAr(dut)
      dut.io.axi.ar.valid.expect(false.B)
      for (beat <- 0 until 64) returnBeat(dut, id = 0, last = beat == 63)
      dut.io.axi.ar.bits.id.expect(1.U)
      acceptAr(dut)
      dut.io.aBeatCount.expect(64.U)
    }
  }

  it should "check response ID, order, and RLAST against the descriptor FIFO" in {
    simulate(new SharedHbmReadScheduler(pairedConfig)) { dut =>
      initializeScheduler(dut, 0x80000000L, 1, 0x80010000L, 1, limit = 2)
      acceptAr(dut)
      acceptAr(dut)
      returnBeat(dut, id = 1, last = false)
      dut.io.error.expect(true.B)
      dut.io.aBeatCount.expect(1.U)
    }
  }

  it should "reserve two complete bursts and wait for FIFO capacity before a third" in {
    simulate(new SharedHbmReadScheduler(pairedConfig)) { dut =>
      initializeScheduler(dut, 0x80000000L, 192, 0x80040000L, 0, limit = 2)
      dut.io.aOutput.ready.poke(false.B)
      acceptAr(dut)
      acceptAr(dut)
      for (burst <- 0 until 2; beat <- 0 until 64) {
        returnBeat(dut, id = 0, last = beat == 63)
      }
      dut.io.axi.ar.valid.expect(false.B)
      dut.io.aOutput.ready.poke(true.B)
      dut.clock.step(63)
      dut.io.axi.ar.valid.expect(false.B)
      dut.clock.step()
      dut.io.axi.ar.valid.expect(true.B)
      dut.io.axi.ar.bits.addr.expect(0x80002000L.U)
      dut.io.axi.ar.bits.len.expect(63.U)
    }
  }

  "PairedXUnpacker" should "emit two groups per beat and validate an odd zero-padded tail" in {
    val beats = Seq(
      wordsBeat((1L to 16L)),
      wordsBeat((17L to 24L) ++ Seq.fill(8)(0L)))
    simulate(new PairedXUnpacker) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.expectedBeats.poke(2.U)
      dut.io.expectedGroups.poke(3.U)
      dut.io.expectedCrc.poke(crc32(beats).U)
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      var group = 0
      beats.zipWithIndex.foreach { case (beat, index) =>
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.data.poke(beat.U)
        dut.io.input.bits.error.poke(false.B)
        dut.io.input.bits.last.poke((index == 1).B)
        while (!dut.io.input.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        val groupsFromBeat = if (index == 0) 2 else 1
        for (_ <- 0 until groupsFromBeat) {
          dut.io.output.valid.expect(true.B)
          dut.io.output.bits.tag.expect(group.U)
          dut.io.output.bits.last.expect((group == 2).B)
          for (lane <- 0 until 8) {
            dut.io.output.bits.values(lane).expect((group * 8 + lane + 1).U)
          }
          dut.clock.step()
          group += 1
        }
      }
      dut.io.done.expect(true.B)
      dut.io.groupCount.expect(3.U)
      dut.io.loadError.expect(false.B)
      dut.io.crcError.expect(false.B)
    }
  }

  it should "report nonzero odd padding and X CRC corruption" in {
    val beat = wordsBeat((1L to 8L) ++ Seq(9L) ++ Seq.fill(7)(0L))
    simulate(new PairedXUnpacker) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.expectedBeats.poke(1.U)
      dut.io.expectedGroups.poke(1.U)
      dut.io.expectedCrc.poke(0.U)
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.data.poke(beat.U)
      dut.io.input.bits.error.poke(false.B)
      dut.io.input.bits.last.poke(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.clock.step()
      dut.io.done.expect(true.B)
      dut.io.loadError.expect(true.B)
      dut.io.crcError.expect(true.B)
    }
  }

  "CachedXLoader" should "load sixteen values per beat and validate CRC plus tail padding" in {
    val validBeats = Seq(wordsBeat(1L to 16L), wordsBeat(Seq(17L) ++ Seq.fill(15)(0L)))
    simulate(new CachedXLoader) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.blockCols.poke(17.U)
      dut.io.expectedBeats.poke(2.U)
      dut.io.expectedCrc.poke(crc32(validBeats).U)
      dut.io.input.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      validBeats.zipWithIndex.foreach { case (beat, index) =>
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.data.poke(beat.U)
        dut.io.input.bits.error.poke(false.B)
        dut.io.input.bits.last.poke((index == 1).B)
        dut.io.load.valid.expect(true.B)
        dut.io.load.bits.wordIndex.expect(index.U)
        dut.clock.step()
      }
      dut.io.input.valid.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.loadError.expect(false.B)
      dut.io.crcError.expect(false.B)
    }

    val invalidTail = wordsBeat(Seq(17L, 99L) ++ Seq.fill(14)(0L))
    simulate(new CachedXLoader) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.blockCols.poke(1.U)
      dut.io.expectedBeats.poke(1.U)
      dut.io.expectedCrc.poke(0.U)
      dut.io.input.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.input.valid.poke(true.B)
      dut.io.input.bits.data.poke(invalidTail.U)
      dut.io.input.bits.error.poke(false.B)
      dut.io.input.bits.last.poke(true.B)
      dut.clock.step()
      dut.io.input.valid.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.loadError.expect(true.B)
      dut.io.crcError.expect(true.B)
    }
  }

  private def pokeDecoded(dut: WideXCache8R, columns: Seq[Int]): Unit = {
    dut.io.request.bits.globalTileId.poke(3.U)
    dut.io.request.bits.blockTileId.poke(2.U)
    dut.io.request.bits.blockRowId.poke(1.U)
    dut.io.request.bits.blockColId.poke(0.U)
    dut.io.request.bits.blockRowBase.poke(9.U)
    dut.io.request.bits.step.poke(4.U)
    dut.io.request.bits.tileLast.poke(false.B)
    dut.io.request.bits.tag.poke(7.U)
    columns.zipWithIndex.foreach { case (column, lane) =>
      dut.io.request.bits.lanes(lane).valid.poke(true.B)
      dut.io.request.bits.lanes(lane).rowStart.poke((lane == 0).B)
      dut.io.request.bits.lanes(lane).rowEnd.poke((lane == 7).B)
      dut.io.request.bits.lanes(lane).localRow.poke(lane.U)
      dut.io.request.bits.lanes(lane).localCol.poke(column.U)
      dut.io.request.bits.lanes(lane).a.poke((0x3f800000L + lane).U)
    }
  }

  "WideXCache8R" should "load sixteen values per beat and serve eight arbitrary columns" in {
    simulate(new WideXCache8R(cachedConfig)) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.load.valid.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      Seq(
        0 -> wordsBeat(0L until 16L),
        1 -> wordsBeat(100L until 116L),
        511 -> wordsBeat(1000L until 1016L)
      ).foreach { case (index, beat) =>
        dut.io.load.valid.poke(true.B)
        dut.io.load.bits.wordIndex.poke(index.U)
        dut.io.load.bits.data.poke(beat.U)
        dut.clock.step()
      }
      dut.io.load.valid.poke(false.B)

      val columns = Seq(0, 15, 16, 31, 8191, 16, 0, 8191)
      val expected = Seq(0, 15, 100, 115, 1015, 100, 0, 1015)
      pokeDecoded(dut, columns)
      dut.io.request.valid.poke(true.B)
      while (!dut.io.request.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      while (!dut.io.response.valid.peek().litToBoolean) dut.clock.step()
      for (_ <- 0 until 4) {
        dut.io.response.bits.decoded.tag.expect(7.U)
        expected.zipWithIndex.foreach { case (value, lane) =>
          dut.io.response.bits.x(lane).expect(value.U)
        }
        dut.clock.step()
      }
      dut.io.response.ready.poke(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  "Csr5PacketDecoder" should "decode one full tile and a one-record tail as one stream" in {
    val fullRecords = (0 until 128).map { index =>
      Record(index / 2, index, index % 2 == 0, index % 2 == 1, 0x3f000000L + index)
    }
    val fullPayload = (0 until 16).map { step =>
      (0 until 8).map(lane => fullRecords(lane * 16 + step))
    }
    val tailRecord = Record(64, 128, rowStart = true, rowEnd = true, 0x40000000L)
    val tailPayload = Seq(Seq(tailRecord) ++
      Seq.fill(7)(Record(0, 0, false, false, 0, valid = false)))
    val stream = Seq(metadata(fullPayload, full = true, 128)) ++ fullPayload.map(payloadBeat) ++
      Seq(metadata(tailPayload, full = false, 1)) ++ tailPayload.map(payloadBeat)
    val expected = fullPayload ++ tailPayload

    simulate(new Csr5PacketDecoder(pairedConfig)) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.blockRows.poke(256.U)
      dut.io.blockCols.poke(256.U)
      dut.io.streamFinished.poke(false.B)
      dut.io.input.valid.poke(false.B)
      dut.io.output.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      var outputIndex = 0
      var cycle = 0
      var observedErrors = BigInt(0)
      stream.zipWithIndex.foreach { case (beat, inputIndex) =>
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.data.poke(beat.U)
        dut.io.input.bits.error.poke(false.B)
        dut.io.input.bits.last.poke((inputIndex + 1 == stream.size).B)
        var accepted = false
        while (!accepted) {
          val ready = (cycle % 4) != 1
          dut.io.output.ready.poke(ready.B)
          if (dut.io.output.valid.peek().litToBoolean && ready) {
            dut.io.output.bits.tag.expect(outputIndex.U)
            expected(outputIndex).zipWithIndex.foreach { case (record, lane) =>
              dut.io.output.bits.lanes(lane).valid.expect(record.valid.B)
              if (record.valid) {
                dut.io.output.bits.lanes(lane).localRow.expect(record.row.U)
                dut.io.output.bits.lanes(lane).localCol.expect(record.col.U)
                dut.io.output.bits.lanes(lane).a.expect(record.a.U)
              }
            }
            outputIndex += 1
          }
          accepted = dut.io.input.ready.peek().litToBoolean
          observedErrors |= dut.io.errorMask.peek().litValue
          dut.clock.step()
          cycle += 1
        }
      }
      dut.io.input.valid.poke(false.B)
      dut.io.streamFinished.poke(true.B)
      dut.io.output.ready.poke(true.B)
      dut.clock.step(2)
      assert(outputIndex == 17)
      dut.io.packetCount.expect(2.U)
      assert(observedErrors == 0, f"decoder error mask 0x$observedErrors%x")
      dut.io.idle.expect(true.B)
    }
  }

  it should "report metadata, stream, summary, bounds, count, and CRC errors" in {
    val invalid = Record(7, 7, rowStart = true, rowEnd = true)
    val payload = Seq(Seq(invalid) ++
      Seq.fill(7)(Record(0, 0, false, false, 0, valid = false)))
    val malformedMetadata = metadata(payload, full = false, validCount = 2,
      version = 1, expectedCrc = Some(0L)) | (BigInt(1) << 13)

    simulate(new Csr5PacketDecoder(pairedConfig)) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.blockRows.poke(1.U)
      dut.io.blockCols.poke(1.U)
      dut.io.streamFinished.poke(false.B)
      dut.io.output.ready.poke(true.B)
      dut.io.input.valid.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)

      var observed = BigInt(0)
      Seq(malformedMetadata, payloadBeat(payload.head)).foreach { beat =>
        dut.io.input.valid.poke(true.B)
        dut.io.input.bits.data.poke(beat.U)
        dut.io.input.bits.error.poke(true.B)
        dut.io.input.bits.last.poke(true.B)
        while (!dut.io.input.ready.peek().litToBoolean) dut.clock.step()
        observed |= dut.io.errorMask.peek().litValue
        dut.clock.step()
        dut.io.input.valid.poke(false.B)
        observed |= dut.io.errorMask.peek().litValue
      }
      assert((observed & 0x1fc) == 0x1fc, f"decoder error mask 0x$observed%x")
    }
  }

  "SpmvOneHbmCsr5MulSimulationTop" should
      "eliminate the cache from paired RTL and instantiate four wide memories for cached RTL" in {
    val paired = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new SpmvOneHbmCsr5MulSimulationTop(pairedConfig))
    val cached = _root_.circt.stage.ChiselStage.emitCHIRRTL(
      new SpmvOneHbmCsr5MulSimulationTop(cachedConfig))

    assert(!paired.contains("module WideXCache8R"))
    assert(!paired.contains("smem memories_0"))
    assert(cached.contains("module WideXCache8R"))
    (0 until 4).foreach(index => assert(cached.contains(s"smem memories_$index")))
    assert(!cached.contains("module PairedXUnpacker"))
  }
}

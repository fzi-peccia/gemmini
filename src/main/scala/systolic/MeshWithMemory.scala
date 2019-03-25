//DO NOT TOUCH
package systolic

import chisel3._
import chisel3.util._

import systolic.Util._

// TODO fix output ready. It doesn't work when banking, and it seems to assume that SRAM outputs stay steady when ren is low
// TODO add option to shift output with SRAM banking instead
// TODO change banks to support non-square tiles
// TODO Handle matrices where N1 =/= N2 =/= N3
// TODO Change S to an enum
// TODO Does it make sense to not pause when the output isn't valid? And is that possible with the current setup anyway?
// TODO Should I add a reset buffers input?
// TODO should I add an output_valid counter?

class MeshWithMemory[T <: Data: Arithmetic](innerType: T, tagWidth: Int, df: Dataflow.Value,
                     val tileRows: Int, val tileColumns: Int,
                     val meshRows: Int, val meshColumns: Int,
                     val sramEntries: Int, val banks: Int) extends Module {

  val A_TYPE = Vec(meshRows, Vec(tileRows, innerType))
  val B_TYPE = Vec(meshColumns, Vec(tileColumns, innerType))
  val C_TYPE = Vec(meshColumns, Vec(tileColumns, innerType))
  val D_TYPE = Vec(meshColumns, Vec(tileColumns, innerType))
  val S_TYPE = Vec(meshColumns, Vec(tileColumns, UInt(2.W)))

  val io = IO(new Bundle {
    val a = Flipped(Decoupled(A_TYPE))
    val b = Flipped(Decoupled(B_TYPE))
    val d = Flipped(Decoupled(D_TYPE))

    val s = Flipped(Decoupled(UInt(1.W)))
    val m = Input(UInt(1.W))

    val tag_in = Flipped(Decoupled(UInt(tagWidth.W)))
    val tag_out = Output(UInt(tagWidth.W))

    val out = Decoupled(Output(C_TYPE))
    val out_s = Output(S_TYPE)

    // TODO make this a decoupled
    val flush = new Bundle {
      val ready = Output(Bool())
      val valid = Input(Bool())

      def fire() = ready && valid
    }
  })

  assert(meshRows*tileRows == meshColumns*tileColumns && meshRows*tileRows == sramEntries)

  val active = RegInit(0.U(1.W)) // Which buffer is currently being read from?
  val not_active = (~active).asUInt()

  // Double-buffers
  val a_buf = Module(new InputBuffer(sramEntries, A_TYPE, banks))
  val b_buf = Module(new InputBuffer(sramEntries, B_TYPE, banks))
  val d_buf = Module(new InputBuffer(meshRows*tileRows, D_TYPE, banks))
  val mat_bufs = Seq(a_buf, b_buf, d_buf)

  val s_bufs = RegInit(VecInit(0.U, 0.U)) // TODO find a better name for this

  val s_next_written = RegInit(false.B)
  val tag_written = RegInit(false.B)

  // TODO this seems inelegant...
  val last_output_retrieved = RegInit(false.B)
  val all_emptied = a_buf.io.emptied && b_buf.io.emptied && d_buf.io.emptied
  val compute_done = (all_emptied && io.out.ready) || (all_emptied && last_output_retrieved)
  when (compute_done && io.out.ready) { last_output_retrieved := true.B }

  val compute_stalling = compute_done && RegNext(compute_done) // TODO this also seems inelegant...

  val buffering_done = a_buf.io.full && b_buf.io.full && d_buf.io.full && s_next_written && tag_written

  val flushing = RegInit(false.B)

  val flip = compute_done && buffering_done // When the double-buffers flip roles
  val pause = compute_stalling || !io.out.ready

  mat_bufs.zip(Seq(io.a, io.b, io.d)).foreach { case (b, mio) =>
      b.io.in := mio.bits
      b.io.valid := mio.valid
      mio.ready := b.io.ready
      b.io.fire := flip
      b.io.pause := pause
      b.io.flush := (flushing || io.flush.fire())
  }

  io.s.ready := !s_next_written

  // Wire up mesh's IO to this module's IO
  val mesh = Module(new Mesh(innerType, df, tileRows, tileColumns, meshRows, meshColumns))

  mesh.io.in_a_vec := a_buf.io.out
  mesh.io.in_b_vec := b_buf.io.out
  mesh.io.in_d_vec := d_buf.io.out

  mesh.io.in_s_vec.zipWithIndex.foreach { case (ss, i) =>
    ss.foreach(_ := ShiftRegister(Cat(io.m, s_bufs(active)), i, !pause))
    // ss.foreach(_ := ShiftRegister(Cat(io.m, s_bufs(not_active)), i, !pause)) // We need the LAST value of S
  }
  mesh.io.pause := pause

  // We want to output C when we're output-stationary, but B when we're weight-stationary
  val bottom_mesh_io = (mesh.io.out_b_vec, mesh.io.out_c_vec, mesh.io.out_s_vec).zipped.toSeq
  io.out.bits := bottom_mesh_io.zip(bottom_mesh_io.indices.reverse).map { case ((bs, cs, ss), i) =>
    // TODO these would actually overlap when we switch from output-stationary to weight-stationary
    // TODO should we use io.m, or the mode output of the mesh?
    ShiftRegister(Mux(io.m === Dataflow.OS.id.U, cs, bs), i, !pause)
  }
  io.out_s := mesh.io.out_s_vec.zip(mesh.io.out_s_vec.indices.reverse).map{case (s, i) => ShiftRegister(s, i, !pause)}

  val out_is_valid = RegInit(true.B)
  when (!pause) { out_is_valid := true.B }.
    elsewhen (io.out.fire()) { out_is_valid := false.B }
  io.out.valid := out_is_valid

  // Tags
  val tag_garbage = Cat(Seq.fill(tagWidth)(1.U(1.W)))
  val tag_queue = Module(new TagQueue(5, UInt(tagWidth.W))) // TODO understand the actual required size better. It seems there may be a bug with it
  tag_queue.io.in.bits := Mux(flushing, tag_garbage, io.tag_in.bits)
  tag_queue.io.garbage := tag_garbage

  val tag_id = RegInit(0.U(1.W)) // Used to keep track of when we should increment
  val tag_id_delayed = ShiftRegister(tag_id, meshRows + S_TYPE.size-1, 0.U, !pause)

  tag_queue.io.out.next := tag_id_delayed =/= RegNext(tag_id_delayed, 0.U)

  when (io.tag_in.fire()) { tag_written := true.B }
  io.tag_in.ready := !tag_written
  tag_queue.io.in.valid := io.tag_in.fire()

  io.tag_out := tag_queue.io.out.bits(Mux(io.m === Dataflow.OS.id.U, 0.U, 1.U))

  // Flushing logic
  val flush_counter = Reg(UInt(3.W))

  io.flush.ready := !flushing
  // assert(!(io.flush.valid && !buffering_done)) // TODO get rid of this once we get the ability to ignore D

  when (io.flush.fire()) {
    flushing := true.B
    flush_counter := 3.U
  }

  when (flushing) {
    Seq(io.a.ready, io.b.ready, io.d.ready, io.s.ready, io.tag_in.ready).foreach(_ := false.B)

    s_bufs(not_active) := ~s_bufs(active)
    s_next_written := true.B

    tag_written := true.B

    when (buffering_done) {
      flush_counter := flush_counter - 1.U

      tag_queue.io.in.valid := true.B

      when (flush_counter === 0.U) {
        flushing := false.B
        mat_bufs.foreach(_.io.flush := false.B)
      }
    }
  }

  /*printf(p"     active: $active,     compute_done: $compute_done,    buffering_done: $buffering_done,    s_buf(active): ${s_bufs(active)}\n")
  printf(p"     io.s.bits: ${io.s.bits}, io.s.ready: ${io.s.ready}, io.s.valid: ${io.s.valid}\n")
  printf(p"     io.a: ${io.a.bits}, a_read: ${a_buf.io.out}\n")
  printf(p"     io.b: ${io.b.bits}, b_read: ${b_buf.io.out}\n")
  printf(p"     io.d: ${io.d.bits}, d_read: ${d_buf.io.out}\n")
  printf(p"     io.out: ${io.out.bits} (valid: ${io.out.valid}) (out_s: ${io.out_s(0)(0)}) (tag: ${io.tag_out})\n")
  printf(p"     tag_queue.io.out.next: ${tag_queue.io.out.next}, tag_id: $tag_id\n")
  printf(p"     flushing: $flushing\n")*/

  // Control logic
  when(io.s.fire() && !flip) {
    s_bufs(not_active) := io.s.bits ^ s_bufs(active)
    s_next_written := true.B
  }

  when(flip) {
    active := not_active

    io.s.ready := true.B
    s_next_written := io.s.fire()
    when(io.s.fire()) { s_bufs(active) := io.s.bits ^ s_bufs(not_active) }

    last_output_retrieved := false.B

    io.tag_in.ready := true.B
    tag_written := io.tag_in.fire()

    tag_id := (~tag_id).asUInt()

    // printf(p"     Done!   (stalling: $compute_stalling) (a.valid: ${io.a.valid}) (a.ready: ${io.a.ready}) (out.ready: ${io.out.ready})\n\n")
  }.elsewhen(!compute_done) {
    // printf(p"     Computing!  (stalling: $compute_stalling) (a.valid: ${io.a.valid}) (a.ready: ${io.a.ready}) (out.ready: ${io.out.ready})\n\n")
  }.otherwise {
    // Pause systolic array
    // printf(p"     PAUSING  (stalling: $compute_stalling) (a.valid: ${io.a.valid}) (a.ready: ${io.a.ready}) (out.ready: ${io.out.ready})\n\n")
  }
}

class InputBuffer[T <: Data](n: Int, t: Vec[Vec[T]], banks: Int) extends Module {
  val io = IO(new Bundle {
    // TODO use the standard Chisel Decoupled interface for these two
    val in = Input(t)
    val out = Output(t)

    val ready = Output(Bool())
    val valid = Input(Bool())

    val full = Output(Bool()) // Refers to the buffer for the next computation
    val emptied = Output(Bool()) // Refers to the buffer for the current computation

    val pause = Input(Bool()) // Pauses the output, not the input
    val flush = Input(Bool())

    // TODO add ability to fire without switching SRAMs
    val fire = Input(Bool())
  })

  val in_fire = io.ready && (io.valid || io.flush)

  val addrs = RegInit(VecInit(Seq.fill(2)(0.U((log2Ceil(n) max 1).W))))

  // We read from buf(read_from), and we write into buf(write_into)
  val read_from = RegInit(0.U(1.W))
  val write_into = (~read_from).asUInt()

  val bufs = VecInit(Seq.fill(2)(Module(new BankedMem(n, t, banks)).io))
  bufs.foreach { b =>
    b.wdata := io.in
    b.ren := false.B
    b.wen := false.B
  }

  bufs.zip(addrs).foreach { case (b, a) => b.addr := a }
  bufs.zip(addrs.reverse).foreach { case (b, ao) => b.addr_other := ao }
  bufs.zip(bufs.reverse).foreach { case (b1, b2) => b1.ren_other := b2.ren }

  val buffering_started = RegInit(false.B)
  // val output_started = RegInit(false.B)
  val buffering_done = buffering_started && addrs(write_into) === 0.U
  val output_done = /*output_started &&*/ addrs(read_from) === 0.U

  io.ready := !buffering_done
  io.full := buffering_done
  io.emptied := output_done

  io.out := (bufs(read_from).rdata, bufs(write_into).rdata, bufs(read_from).valid).zipped.toSeq.zipWithIndex.map {
    case ((br, bw, v), i) =>
      ShiftRegister(Mux(v, br, bw), i % (t.size / banks), !io.pause)
  }

  when (in_fire && !buffering_done) {
    addrs(write_into) := Mux(addrs(write_into) === (n-1).U, 0.U, addrs(write_into) + 1.U)
    bufs(write_into).wen := true.B
    buffering_started := true.B
  }

  when (/*buffering_done &&*/ io.fire) {
    read_from := write_into

    bufs(write_into).ren := true.B
    bufs(read_from).wen := in_fire

    bufs.foreach(_.addr := 0.U)
    addrs(write_into) := 1.U
    addrs(read_from) := Mux(in_fire, 1.U, 0.U)

    buffering_started := in_fire
    io.ready := true.B
  }.elsewhen(!output_done && !io.pause) {
    bufs(read_from).ren := true.B
    addrs(read_from) := Mux(addrs(read_from) === (n-1).U, 0.U, addrs(read_from) + 1.U)
  }/*.elsewhen(!output_done) {
    bufs(read_from).ren := true.B
  }*/

  when (io.flush) {
    bufs.foreach(_.wen := false.B)
  }

  // assert(!(io.fire && !buffering_done), "can't fire banked_mem before buffering is finished")
  // assert(!(io.pause && io.fire), "fired when paused") // TODO add this back when possible
}

class BankedMem[T <: Data](n: Int, t: Vec[Vec[T]], banks: Int) extends Module {
  // This writes horizontally, but it reads in a staggered format
  val io = IO(new Bundle {
    val addr = Input(UInt((log2Ceil(n) max 1).W))
    val addr_other = Input(UInt((log2Ceil(n) max 1).W))
    val wdata = Input(t)
    val rdata = Output(t)
    val wen = Input(Bool())
    val ren = Input(Bool())
    val ren_other = Input(Bool())
    val valid = Output(Vec(t.size, Bool()))
  })

  assert(t.size % banks == 0, "banks must be divisible")

  val banked_t_width = t.size / banks
  val banked_t = Vec(banked_t_width, t.head.cloneType)
  val banked_mems = for (i <- 1 to banks) yield SplitSinglePortedSyncMem(n, banked_t, i).io // TODO We don't have to split so aggressively

  // TODO better names
  val wdata_banked = io.wdata.grouped(io.wdata.size / banks).toSeq
  val rdata_banked = io.rdata.grouped(io.rdata.size / banks).toSeq
  val valid_banked = io.valid.grouped(io.valid.size / banks).toSeq

  banked_mems.zipWithIndex.foreach { case (b, i) =>
    val thresh = i * banked_t_width

    b.ren := ((io.ren && io.addr >= thresh.U) || (io.ren_other && io.addr_other < thresh.U))
    b.wen := io.wen
    b.wdata := wdata_banked(i)

    b.waddr := io.addr
    b.raddr := DontCare

    when (io.ren) {
      b.raddr := wrappingSub(io.addr, thresh.U, n)
    }.elsewhen(io.ren_other) {
      b.raddr := io.addr_other + (n - thresh).U
    }
  }

  for ((buf, out) <- banked_mems zip rdata_banked) {
    (out zip buf.rdata).foreach { case (o, b) => o := b }
  }

  valid_banked.zipWithIndex.foreach { case (v, i) =>
    val thresh = i * banked_t_width
    v.foreach(_ := RegNext(io.addr >= thresh.U)) // We delay by one cycle since memory is synchronous
  }

  assert(!(io.ren && io.ren_other), "both banks are being read from at the same time")
}

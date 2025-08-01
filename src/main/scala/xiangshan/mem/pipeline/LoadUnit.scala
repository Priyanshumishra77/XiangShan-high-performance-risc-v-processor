/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import utility._
import xiangshan._
import xiangshan.ExceptionNO._
import xiangshan.backend.Bundles.{DynInst, MemExuInput, MemExuOutput, connectSamePort}
import xiangshan.backend.fu.PMPRespBundle
import xiangshan.backend.fu.FuConfig._
import xiangshan.backend.fu.FuType
import xiangshan.backend.ctrlblock.{DebugLsInfoBundle, LsTopdownInfo}
import xiangshan.backend.rob.RobPtr
import xiangshan.backend.ctrlblock.DebugLsInfoBundle
import xiangshan.backend.fu.NewCSR._
import xiangshan.backend.fu.util.SdtrigExt
import xiangshan.mem.mdp._
import xiangshan.mem.Bundles._
import xiangshan.cache._
import xiangshan.cache.wpu.ReplayCarry
import xiangshan.cache.mmu._

class LoadToLsqReplayIO(implicit p: Parameters) extends XSBundle
  with HasDCacheParameters
  with HasTlbConst
{
  // mshr refill index
  val mshr_id         = UInt(log2Up(cfg.nMissEntries).W)
  // get full data from store queue and sbuffer
  val full_fwd        = Bool()
  // wait for data from store inst's store queue index
  val data_inv_sq_idx = new SqPtr
  // wait for address from store queue index
  val addr_inv_sq_idx = new SqPtr
  // replay carry
  val rep_carry       = new ReplayCarry(nWays)
  // data in last beat
  val last_beat       = Bool()
  // replay cause
  val cause           = Vec(LoadReplayCauses.allCauses, Bool())
  // performance debug information
  val debug           = new PerfDebugInfo
  // tlb hint
  val tlb_id          = UInt(log2Up(loadfiltersize).W)
  val tlb_full        = Bool()

  // alias
  def mem_amb       = cause(LoadReplayCauses.C_MA)
  def tlb_miss      = cause(LoadReplayCauses.C_TM)
  def fwd_fail      = cause(LoadReplayCauses.C_FF)
  def dcache_rep    = cause(LoadReplayCauses.C_DR)
  def dcache_miss   = cause(LoadReplayCauses.C_DM)
  def wpu_fail      = cause(LoadReplayCauses.C_WF)
  def bank_conflict = cause(LoadReplayCauses.C_BC)
  def rar_nack      = cause(LoadReplayCauses.C_RAR)
  def raw_nack      = cause(LoadReplayCauses.C_RAW)
  def misalign_nack = cause(LoadReplayCauses.C_MF)
  def nuke          = cause(LoadReplayCauses.C_NK)
  def need_rep      = cause.asUInt.orR
}


class LoadToLsqIO(implicit p: Parameters) extends XSBundle {
  // ldu -> lsq UncacheBuffer
  val ldin            = DecoupledIO(new LqWriteBundle)
  // uncache-mmio -> ldu
  val uncache         = Flipped(DecoupledIO(new MemExuOutput))
  val ld_raw_data     = Input(new LoadDataFromLQBundle)
  // uncache-nc -> ldu
  val nc_ldin = Flipped(DecoupledIO(new LsPipelineBundle))
  // storequeue -> ldu
  val forward         = new PipeLoadForwardQueryIO
  // ldu -> lsq LQRAW
  val stld_nuke_query = new LoadNukeQueryIO
  // ldu -> lsq LQRAR
  val ldld_nuke_query = new LoadNukeQueryIO
  // lq -> ldu for misalign
  val lqDeqPtr = Input(new LqPtr)
}

class LoadToLoadIO(implicit p: Parameters) extends XSBundle {
  val valid      = Bool()
  val data       = UInt(XLEN.W) // load to load fast path is limited to ld (64 bit) used as vaddr src1 only
  val dly_ld_err = Bool()
}

class LoadUnitTriggerIO(implicit p: Parameters) extends XSBundle {
  val tdata2      = Input(UInt(64.W))
  val matchType   = Input(UInt(2.W))
  val tEnable     = Input(Bool()) // timing is calculated before this
  val addrHit     = Output(Bool())
}

class LoadUnit(implicit p: Parameters) extends XSModule
  with HasLoadHelper
  with HasPerfEvents
  with HasDCacheParameters
  with HasCircularQueuePtrHelper
  with HasVLSUParameters
  with SdtrigExt
{
  val io = IO(new Bundle() {
    // control
    val redirect      = Flipped(ValidIO(new Redirect))
    val csrCtrl       = Flipped(new CustomCSRCtrlIO)

    // int issue path
    val ldin          = Flipped(Decoupled(new MemExuInput))
    val ldout         = Decoupled(new MemExuOutput)

    // vec issue path
    val vecldin = Flipped(Decoupled(new VecPipeBundle))
    val vecldout = Decoupled(new VecPipelineFeedbackIO(isVStore = false))

    // misalignBuffer issue path
    val misalign_ldin = Flipped(Decoupled(new LsPipelineBundle))
    val misalign_ldout = Valid(new LqWriteBundle)

    // data path
    val tlb           = new TlbRequestIO(2)
    val pmp           = Flipped(new PMPRespBundle()) // arrive same to tlb now
    val dcache        = new DCacheLoadIO
    val sbuffer       = new LoadForwardQueryIO
    val ubuffer       = new LoadForwardQueryIO
    val lsq           = new LoadToLsqIO
    val tl_d_channel  = Input(new DcacheToLduForwardIO)
    val forward_mshr  = Flipped(new LduToMissqueueForwardIO)
   // val refill        = Flipped(ValidIO(new Refill))
    val l2_hint       = Input(Valid(new L2ToL1Hint))
    val tlb_hint      = Flipped(new TlbHintReq)
    // fast wakeup
    // TODO: implement vector fast wakeup
    val fast_uop = ValidIO(new DynInst) // early wakeup signal generated in load_s1, send to RS in load_s2

    // trigger
    val fromCsrTrigger = Input(new CsrTriggerBundle)

    // prefetch
    val prefetch_train            = ValidIO(new LsPrefetchTrainBundle()) // provide prefetch info to sms
    val prefetch_train_l1         = ValidIO(new LsPrefetchTrainBundle()) // provide prefetch info to stream & stride
    // speculative for gated control
    val s1_prefetch_spec = Output(Bool())
    val s2_prefetch_spec = Output(Bool())

    val prefetch_req              = Flipped(ValidIO(new L1PrefetchReq)) // hardware prefetch to l1 cache req
    val canAcceptLowConfPrefetch  = Output(Bool())
    val canAcceptHighConfPrefetch = Output(Bool())

    // ifetchPrefetch
    val ifetchPrefetch = ValidIO(new SoftIfetchPrefetchBundle)

    // load to load fast path
    val l2l_fwd_in    = Input(new LoadToLoadIO)
    val l2l_fwd_out   = Output(new LoadToLoadIO)

    val ld_fast_match    = Input(Bool())
    val ld_fast_fuOpType = Input(UInt())
    val ld_fast_imm      = Input(UInt(12.W))

    // rs feedback
    val wakeup = ValidIO(new DynInst)
    val feedback_fast = ValidIO(new RSFeedback) // stage 2
    val feedback_slow = ValidIO(new RSFeedback) // stage 3
    val ldCancel = Output(new LoadCancelIO()) // use to cancel the uops waked by this load, and cancel load

    // load ecc error
    val s3_dly_ld_err = Output(Bool()) // Note that io.s3_dly_ld_err and io.lsq.s3_dly_ld_err is different

    // schedule error query
    val stld_nuke_query = Flipped(Vec(StorePipelineWidth, Valid(new StoreNukeQueryBundle)))

    // queue-based replay
    val replay       = Flipped(Decoupled(new LsPipelineBundle))
    val lq_rep_full  = Input(Bool())

    // misc
    val s2_ptr_chasing = Output(Bool()) // provide right pc for hw prefetch

    // Load fast replay path
    val fast_rep_in  = Flipped(Decoupled(new LqWriteBundle))
    val fast_rep_out = Decoupled(new LqWriteBundle)

    // to misalign buffer
    val misalign_enq = new MisalignBufferEnqIO
    val misalign_allow_spec = Input(Bool())

    // Load RAR rollback
    val rollback = Valid(new Redirect)

    // perf
    val debug_ls         = Output(new DebugLsInfoBundle)
    val lsTopdownInfo    = Output(new LsTopdownInfo)
    val correctMissTrain = Input(Bool())
  })


  PerfCCT.updateInstPos(io.ldin.bits.uop.debug_seqNum, PerfCCT.InstPos.AtFU.id.U, io.ldin.valid, clock, reset)

  val s1_ready, s2_ready, s3_ready = WireInit(false.B)

  // Pipeline
  // --------------------------------------------------------------------------------
  // stage 0
  // --------------------------------------------------------------------------------
  // generate addr, use addr to query DCache and DTLB
  val s0_valid         = Wire(Bool())
  val s0_mmio_select   = Wire(Bool())
  val s0_nc_select     = Wire(Bool())
  val s0_misalign_select= Wire(Bool())
  val s0_kill          = Wire(Bool())
  val s0_can_go        = s1_ready
  val s0_fire          = s0_valid && s0_can_go
  val s0_mmio_fire     = s0_mmio_select && s0_can_go
  val s0_nc_fire       = s0_nc_select && s0_can_go
  val s0_out           = Wire(new LqWriteBundle)
  val s0_tlb_valid     = Wire(Bool())
  val s0_tlb_hlv       = Wire(Bool())
  val s0_tlb_hlvx      = Wire(Bool())
  val s0_tlb_vaddr     = Wire(UInt(VAddrBits.W))
  val s0_tlb_fullva    = Wire(UInt(XLEN.W))
  val s0_dcache_vaddr  = Wire(UInt(VAddrBits.W))
  val s0_is128bit      = Wire(Bool())
  val s0_misalign_wakeup_fire = s0_misalign_select && s0_can_go &&
    io.dcache.req.ready &&
    io.misalign_ldin.bits.misalignNeedWakeUp

  // flow source bundle
  class FlowSource extends Bundle {
    val vaddr         = UInt(VAddrBits.W)
    val mask          = UInt((VLEN/8).W)
    val uop           = new DynInst
    val try_l2l       = Bool()
    val has_rob_entry = Bool()
    val rep_carry     = new ReplayCarry(nWays)
    val mshrid        = UInt(log2Up(cfg.nMissEntries).W)
    val isFirstIssue  = Bool()
    val fast_rep      = Bool()
    val ld_rep        = Bool()
    val l2l_fwd       = Bool()
    val prf           = Bool()
    val prf_rd        = Bool()
    val prf_wr        = Bool()
    val prf_i         = Bool()
    val sched_idx     = UInt(log2Up(LoadQueueReplaySize+1).W)
    // Record the issue port idx of load issue queue. This signal is used by load cancel.
    val deqPortIdx    = UInt(log2Ceil(LoadPipelineWidth).W)
    val frm_mabuf     = Bool()
    // vec only
    val isvec         = Bool()
    val is128bit      = Bool()
    val uop_unit_stride_fof = Bool()
    val reg_offset    = UInt(vOffsetBits.W)
    val vecActive     = Bool() // 1: vector active element or scala mem operation, 0: vector not active element
    val is_first_ele  = Bool()
    // val flowPtr       = new VlflowPtr
    val usSecondInv   = Bool()
    val mbIndex       = UInt(vlmBindexBits.W)
    val elemIdx       = UInt(elemIdxBits.W)
    val elemIdxInsideVd = UInt(elemIdxBits.W)
    val alignedType   = UInt(alignTypeBits.W)
    val vecBaseVaddr  = UInt(VAddrBits.W)
    //for Svpbmt NC
    val isnc          = Bool()
    val paddr         = UInt(PAddrBits.W)
    val data          = UInt((VLEN+1).W)
  }
  val s0_sel_src = Wire(new FlowSource)

  // load flow select/gen
  // src 0: misalignBuffer load (io.misalign_ldin)
  // src 1: super load replayed by LSQ (cache miss replay) (io.replay)
  // src 2: fast load replay (io.fast_rep_in)
  // src 3: mmio (io.lsq.uncache)
  // src 4: nc (io.lsq.nc_ldin)
  // src 5: load replayed by LSQ (io.replay)
  // src 6: hardware prefetch from prefetchor (high confidence) (io.prefetch)
  // NOTE: Now vec/int loads are sent from same RS
  //       A vec load will be splited into multiple uops,
  //       so as long as one uop is issued,
  //       the other uops should have higher priority
  // src 7: vec read from RS (io.vecldin)
  // src 8: int read / software prefetch first issue from RS (io.in)
  // src 9: load try pointchaising when no issued or replayed load (io.fastpath)
  // src10: hardware prefetch from prefetchor (high confidence) (io.prefetch)
  // priority: high to low
  val s0_rep_stall           = io.ldin.valid && isAfter(io.replay.bits.uop.lqIdx, io.ldin.bits.uop.lqIdx) ||
                               io.vecldin.valid && isAfter(io.replay.bits.uop.lqIdx, io.vecldin.bits.uop.lqIdx)
  private val SRC_NUM = 11
  private val Seq(
    mab_idx, super_rep_idx, fast_rep_idx, mmio_idx, nc_idx, lsq_rep_idx,
    high_pf_idx, vec_iss_idx, int_iss_idx, l2l_fwd_idx, low_pf_idx
  ) = (0 until SRC_NUM).toSeq
  // load flow source valid
  val s0_src_valid_vec = WireInit(VecInit(Seq(
    io.misalign_ldin.valid,
    io.replay.valid && io.replay.bits.forward_tlDchannel,
    io.fast_rep_in.valid,
    io.lsq.uncache.valid,
    io.lsq.nc_ldin.valid,
    io.replay.valid && !io.replay.bits.forward_tlDchannel && !s0_rep_stall,
    io.prefetch_req.valid && io.prefetch_req.bits.confidence > 0.U,
    io.vecldin.valid,
    io.ldin.valid, // int flow first issue or software prefetch
    io.l2l_fwd_in.valid,
    io.prefetch_req.valid && io.prefetch_req.bits.confidence === 0.U,
  )))
  // load flow source ready
  val s0_src_ready_vec = Wire(Vec(SRC_NUM, Bool()))
  s0_src_ready_vec(0) := true.B
  for(i <- 1 until SRC_NUM){
    s0_src_ready_vec(i) := !s0_src_valid_vec.take(i).reduce(_ || _)
  }
  // load flow source select (OH)
  val s0_src_select_vec = WireInit(VecInit((0 until SRC_NUM).map{i => s0_src_valid_vec(i) && s0_src_ready_vec(i)}))
  val s0_hw_prf_select = s0_src_select_vec(high_pf_idx) || s0_src_select_vec(low_pf_idx)

  val s0_tlb_no_query = s0_hw_prf_select || s0_sel_src.prf_i ||
    s0_src_select_vec(fast_rep_idx) || s0_src_select_vec(mmio_idx) ||
    s0_src_select_vec(nc_idx)
  s0_valid := !s0_kill && (s0_src_select_vec(nc_idx) || ((
    s0_src_valid_vec(mab_idx) ||
    s0_src_valid_vec(super_rep_idx) ||
    s0_src_valid_vec(fast_rep_idx) ||
    s0_src_valid_vec(lsq_rep_idx) ||
    s0_src_valid_vec(high_pf_idx) ||
    s0_src_valid_vec(vec_iss_idx) ||
    s0_src_valid_vec(int_iss_idx) ||
    s0_src_valid_vec(l2l_fwd_idx) ||
    s0_src_valid_vec(low_pf_idx)
  ) && !s0_src_select_vec(mmio_idx) && io.dcache.req.ready &&
    !(io.misalign_ldin.fire && io.misalign_ldin.bits.misalignNeedWakeUp) // Currently, misalign is the highest priority
  ))

  s0_mmio_select := s0_src_select_vec(mmio_idx) && !s0_kill
  s0_nc_select := s0_src_select_vec(nc_idx) && !s0_kill
  //judgment: is NC with data or not.
  //If true, it's from `io.lsq.nc_ldin` or `io.fast_rep_in`
  val s0_nc_with_data = s0_sel_src.isnc && !s0_kill
  s0_misalign_select := s0_src_select_vec(mab_idx) && !s0_kill

   // if is hardware prefetch or fast replay, don't send valid to tlb
  s0_tlb_valid := (
    s0_src_valid_vec(mab_idx) ||
    s0_src_valid_vec(super_rep_idx) ||
    s0_src_valid_vec(lsq_rep_idx) ||
    s0_src_valid_vec(vec_iss_idx) ||
    s0_src_valid_vec(int_iss_idx) ||
    s0_src_valid_vec(l2l_fwd_idx)
  ) && io.dcache.req.ready

  // which is S0's out is ready and dcache is ready
  val s0_try_ptr_chasing      = s0_src_select_vec(l2l_fwd_idx)
  val s0_do_try_ptr_chasing   = s0_try_ptr_chasing && s0_can_go && io.dcache.req.ready
  val s0_ptr_chasing_vaddr    = io.l2l_fwd_in.data(5, 0) +& io.ld_fast_imm(5, 0)
  val s0_ptr_chasing_canceled = WireInit(false.B)
  s0_kill := s0_ptr_chasing_canceled

  // prefetch related ctrl signal
  io.canAcceptLowConfPrefetch  := s0_src_ready_vec(low_pf_idx) && io.dcache.req.ready
  io.canAcceptHighConfPrefetch := s0_src_ready_vec(high_pf_idx) && io.dcache.req.ready

  // query DTLB
  io.tlb.req.valid                   := s0_tlb_valid
  io.tlb.req.bits.cmd                := Mux(s0_sel_src.prf,
                                         Mux(s0_sel_src.prf_wr, TlbCmd.write, TlbCmd.read),
                                         TlbCmd.read
                                       )
  io.tlb.req.bits.isPrefetch         := s0_sel_src.prf
  io.tlb.req.bits.vaddr              := s0_tlb_vaddr
  io.tlb.req.bits.fullva             := s0_tlb_fullva
  io.tlb.req.bits.checkfullva        := s0_src_select_vec(vec_iss_idx) || s0_src_select_vec(int_iss_idx)
  io.tlb.req.bits.hyperinst          := s0_tlb_hlv
  io.tlb.req.bits.hlvx               := s0_tlb_hlvx
  io.tlb.req.bits.size               := Mux(s0_sel_src.isvec, s0_sel_src.alignedType(2,0), LSUOpType.size(s0_sel_src.uop.fuOpType))
  io.tlb.req.bits.kill               := s0_kill || s0_tlb_no_query // if does not need to be translated, kill it
  io.tlb.req.bits.memidx.is_ld       := true.B
  io.tlb.req.bits.memidx.is_st       := false.B
  io.tlb.req.bits.memidx.idx         := s0_sel_src.uop.lqIdx.value
  io.tlb.req.bits.debug.robIdx       := s0_sel_src.uop.robIdx
  io.tlb.req.bits.no_translate       := s0_tlb_no_query  // hardware prefetch and fast replay does not need to be translated, need this signal for pmp check
  io.tlb.req.bits.debug.pc           := s0_sel_src.uop.pc
  io.tlb.req.bits.debug.isFirstIssue := s0_sel_src.isFirstIssue

  // query DCache
  io.dcache.req.valid             := s0_valid && !s0_sel_src.prf_i && !s0_nc_with_data
  io.dcache.req.bits.cmd          := Mux(s0_sel_src.prf_rd,
                                      MemoryOpConstants.M_PFR,
                                      Mux(s0_sel_src.prf_wr, MemoryOpConstants.M_PFW, MemoryOpConstants.M_XRD)
                                    )
  io.dcache.req.bits.vaddr        := s0_dcache_vaddr
  io.dcache.req.bits.vaddr_dup    := s0_dcache_vaddr
  io.dcache.req.bits.mask         := s0_sel_src.mask
  io.dcache.req.bits.data         := DontCare
  io.dcache.req.bits.isFirstIssue := s0_sel_src.isFirstIssue
  io.dcache.req.bits.instrtype    := Mux(s0_sel_src.prf, DCACHE_PREFETCH_SOURCE.U, LOAD_SOURCE.U)
  io.dcache.req.bits.debug_robIdx := s0_sel_src.uop.robIdx.value
  io.dcache.req.bits.replayCarry  := s0_sel_src.rep_carry
  io.dcache.req.bits.id           := DontCare // TODO: update cache meta
  io.dcache.req.bits.lqIdx        := s0_sel_src.uop.lqIdx
  io.dcache.pf_source             := Mux(s0_hw_prf_select, io.prefetch_req.bits.pf_source.value, L1_HW_PREFETCH_NULL)
  io.dcache.is128Req              := s0_is128bit

  // load flow priority mux
  def fromNullSource(): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out
  }

  def fromMisAlignBufferSource(src: LsPipelineBundle): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.vaddr         := src.vaddr
    out.mask          := src.mask
    out.uop           := src.uop
    out.try_l2l       := false.B
    out.has_rob_entry := false.B
    out.rep_carry     := src.replayCarry
    out.mshrid        := src.mshrid
    out.frm_mabuf     := true.B
    out.isFirstIssue  := false.B
    out.fast_rep      := false.B
    out.ld_rep        := false.B
    out.l2l_fwd       := false.B
    out.prf           := false.B
    out.prf_rd        := false.B
    out.prf_wr        := false.B
    out.sched_idx     := src.schedIndex
    out.isvec         := src.isvec
    out.is128bit      := src.is128bit
    out.vecActive     := true.B
    out
  }

  def fromFastReplaySource(src: LqWriteBundle): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.vaddr         := src.vaddr
    out.paddr         := src.paddr
    out.mask          := src.mask
    out.uop           := src.uop
    out.try_l2l       := false.B
    out.has_rob_entry := src.hasROBEntry
    out.rep_carry     := src.rep_info.rep_carry
    out.mshrid        := src.rep_info.mshr_id
    out.frm_mabuf     := src.isFrmMisAlignBuf
    out.isFirstIssue  := false.B
    out.fast_rep      := true.B
    out.ld_rep        := src.isLoadReplay
    out.l2l_fwd       := false.B
    out.prf           := LSUOpType.isPrefetch(src.uop.fuOpType) && !src.isvec
    out.prf_rd        := src.uop.fuOpType === LSUOpType.prefetch_r
    out.prf_wr        := src.uop.fuOpType === LSUOpType.prefetch_w
    out.prf_i         := false.B
    out.sched_idx     := src.schedIndex
    out.isvec         := src.isvec
    out.is128bit      := src.is128bit
    out.uop_unit_stride_fof := src.uop_unit_stride_fof
    out.reg_offset    := src.reg_offset
    out.vecActive     := src.vecActive
    out.is_first_ele  := src.is_first_ele
    out.usSecondInv   := src.usSecondInv
    out.mbIndex       := src.mbIndex
    out.elemIdx       := src.elemIdx
    out.elemIdxInsideVd := src.elemIdxInsideVd
    out.alignedType   := src.alignedType
    out.isnc          := src.nc
    out.data          := src.data
    out
  }

  // TODO: implement vector mmio
  def fromMmioSource(src: MemExuOutput) = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.mask          := 0.U
    out.uop           := src.uop
    out.try_l2l       := false.B
    out.has_rob_entry := false.B
    out.rep_carry     := 0.U.asTypeOf(out.rep_carry)
    out.mshrid        := 0.U
    out.frm_mabuf     := false.B
    out.isFirstIssue  := false.B
    out.fast_rep      := false.B
    out.ld_rep        := false.B
    out.l2l_fwd       := false.B
    out.prf           := false.B
    out.prf_rd        := false.B
    out.prf_wr        := false.B
    out.prf_i         := false.B
    out.sched_idx     := 0.U
    out.vecActive     := true.B
    out
  }

  def fromNcSource(src: LsPipelineBundle): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.vaddr := src.vaddr
    out.paddr := src.paddr
    out.mask := genVWmask(src.vaddr, src.uop.fuOpType(1,0))
    out.uop := src.uop
    out.has_rob_entry := true.B
    out.sched_idx := src.schedIndex
    out.isvec := src.isvec
    out.is128bit := src.is128bit
    out.vecActive := src.vecActive
    out.isnc := true.B
    out.data := src.data
    out
  }

  def fromNormalReplaySource(src: LsPipelineBundle): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.mask          := Mux(src.isvec, src.mask, genVWmask(src.vaddr, src.uop.fuOpType(1, 0)))
    out.uop           := src.uop
    out.try_l2l       := false.B
    out.has_rob_entry := true.B
    out.rep_carry     := src.replayCarry
    out.mshrid        := src.mshrid
    out.frm_mabuf     := false.B
    out.isFirstIssue  := false.B
    out.fast_rep      := false.B
    out.ld_rep        := true.B
    out.l2l_fwd       := false.B
    out.prf           := LSUOpType.isPrefetch(src.uop.fuOpType) && !src.isvec
    out.prf_rd        := src.uop.fuOpType === LSUOpType.prefetch_r
    out.prf_wr        := src.uop.fuOpType === LSUOpType.prefetch_w
    out.prf_i         := false.B
    out.sched_idx     := src.schedIndex
    out.isvec         := src.isvec
    out.is128bit      := src.is128bit
    out.uop_unit_stride_fof := src.uop_unit_stride_fof
    out.reg_offset    := src.reg_offset
    out.vecActive     := src.vecActive
    out.is_first_ele  := src.is_first_ele
    out.usSecondInv   := src.usSecondInv
    out.mbIndex       := src.mbIndex
    out.elemIdx       := src.elemIdx
    out.elemIdxInsideVd := src.elemIdxInsideVd
    out.alignedType   := src.alignedType
    out
  }

  // TODO: implement vector prefetch
  def fromPrefetchSource(src: L1PrefetchReq): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.mask          := 0.U
    out.uop           := DontCare
    out.try_l2l       := false.B
    out.has_rob_entry := false.B
    out.rep_carry     := 0.U.asTypeOf(out.rep_carry)
    out.mshrid        := 0.U
    out.frm_mabuf     := false.B
    out.isFirstIssue  := false.B
    out.fast_rep      := false.B
    out.ld_rep        := false.B
    out.l2l_fwd       := false.B
    out.prf           := true.B
    out.prf_rd        := !src.is_store
    out.prf_wr        := src.is_store
    out.prf_i         := false.B
    out.sched_idx     := 0.U
    out
  }

  def fromVecIssueSource(src: VecPipeBundle): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.mask          := src.mask
    out.uop           := src.uop
    out.try_l2l       := false.B
    out.has_rob_entry := true.B
    // TODO: VLSU, implement replay carry
    out.rep_carry     := 0.U.asTypeOf(out.rep_carry)
    out.mshrid        := 0.U
    out.frm_mabuf     := false.B
    // TODO: VLSU, implement first issue
//    out.isFirstIssue  := src.isFirstIssue
    out.fast_rep      := false.B
    out.ld_rep        := false.B
    out.l2l_fwd       := false.B
    out.prf           := false.B
    out.prf_rd        := false.B
    out.prf_wr        := false.B
    out.prf_i         := false.B
    out.sched_idx     := 0.U
    // Vector load interface
    out.isvec               := true.B
    // vector loads only access a single element at a time, so 128-bit path is not used for now
    out.is128bit            := is128Bit(src.alignedType)
    out.uop_unit_stride_fof := src.uop_unit_stride_fof
    // out.rob_idx_valid       := src.rob_idx_valid
    // out.inner_idx           := src.inner_idx
    // out.rob_idx             := src.rob_idx
    out.reg_offset          := src.reg_offset
    // out.offset              := src.offset
    out.vecActive           := src.vecActive
    out.is_first_ele        := src.is_first_ele
    // out.flowPtr             := src.flowPtr
    out.usSecondInv         := src.usSecondInv
    out.mbIndex             := src.mBIndex
    out.elemIdx             := src.elemIdx
    out.elemIdxInsideVd     := src.elemIdxInsideVd
    out.vecBaseVaddr        := src.basevaddr
    out.alignedType         := src.alignedType
    out
  }

  def fromIntIssueSource(src: MemExuInput): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    val addr           = io.ldin.bits.src(0) + SignExt(io.ldin.bits.uop.imm(11, 0), VAddrBits)
    out.mask          := genVWmask(addr, src.uop.fuOpType(1,0))
    out.uop           := src.uop
    out.try_l2l       := false.B
    out.has_rob_entry := true.B
    out.rep_carry     := 0.U.asTypeOf(out.rep_carry)
    out.mshrid        := 0.U
    out.frm_mabuf     := false.B
    out.isFirstIssue  := true.B
    out.fast_rep      := false.B
    out.ld_rep        := false.B
    out.l2l_fwd       := false.B
    out.prf           := LSUOpType.isPrefetch(src.uop.fuOpType)
    out.prf_rd        := src.uop.fuOpType === LSUOpType.prefetch_r
    out.prf_wr        := src.uop.fuOpType === LSUOpType.prefetch_w
    out.prf_i         := src.uop.fuOpType === LSUOpType.prefetch_i
    out.sched_idx     := 0.U
    out.vecActive     := true.B // true for scala load
    out
  }

  // TODO: implement vector l2l
  def fromLoadToLoadSource(src: LoadToLoadIO): FlowSource = {
    val out = WireInit(0.U.asTypeOf(new FlowSource))
    out.mask               := genVWmask(0.U, LSUOpType.ld)
    // When there's no valid instruction from RS and LSQ, we try the load-to-load forwarding.
    // Assume the pointer chasing is always ld.
    out.uop.fuOpType       := LSUOpType.ld
    out.try_l2l            := true.B
    // we dont care out.isFirstIssue and out.rsIdx and s0_sqIdx in S0 when trying pointchasing
    // because these signals will be updated in S1
    out.has_rob_entry      := false.B
    out.mshrid             := 0.U
    out.frm_mabuf          := false.B
    out.rep_carry          := 0.U.asTypeOf(out.rep_carry)
    out.isFirstIssue       := true.B
    out.fast_rep           := false.B
    out.ld_rep             := false.B
    out.l2l_fwd            := true.B
    out.prf                := false.B
    out.prf_rd             := false.B
    out.prf_wr             := false.B
    out.prf_i              := false.B
    out.sched_idx          := 0.U
    out
  }

  // set default
  val s0_src_selector = WireInit(s0_src_valid_vec)
  if (!EnableLoadToLoadForward) { s0_src_selector(l2l_fwd_idx) := false.B }
  val s0_src_format = Seq(
    fromMisAlignBufferSource(io.misalign_ldin.bits),
    fromNormalReplaySource(io.replay.bits),
    fromFastReplaySource(io.fast_rep_in.bits),
    fromMmioSource(io.lsq.uncache.bits),
    fromNcSource(io.lsq.nc_ldin.bits),
    fromNormalReplaySource(io.replay.bits),
    fromPrefetchSource(io.prefetch_req.bits),
    fromVecIssueSource(io.vecldin.bits),
    fromIntIssueSource(io.ldin.bits),
    (if (EnableLoadToLoadForward) fromLoadToLoadSource(io.l2l_fwd_in) else fromNullSource()),
    fromPrefetchSource(io.prefetch_req.bits)
  )
  s0_sel_src := ParallelPriorityMux(s0_src_selector, s0_src_format)

  // fast replay and hardware prefetch don't need to query tlb
  val int_issue_vaddr = io.ldin.bits.src(0) + SignExt(io.ldin.bits.uop.imm(11, 0), VAddrBits)
  val int_vec_vaddr = Mux(s0_src_valid_vec(vec_iss_idx), io.vecldin.bits.vaddr(VAddrBits - 1, 0), int_issue_vaddr)
  s0_tlb_vaddr := Mux(
    s0_src_valid_vec(mab_idx),
    io.misalign_ldin.bits.vaddr,
    Mux(
      s0_src_valid_vec(super_rep_idx) || s0_src_valid_vec(lsq_rep_idx),
      io.replay.bits.vaddr,
      int_vec_vaddr
    )
  )
  s0_dcache_vaddr := Mux(
    s0_src_select_vec(fast_rep_idx), io.fast_rep_in.bits.vaddr,
    Mux(s0_hw_prf_select, io.prefetch_req.bits.getVaddr(),
    Mux(s0_src_select_vec(nc_idx), io.lsq.nc_ldin.bits.vaddr, // not for dcache access, but for address alignment check
    s0_tlb_vaddr))
  )

  val s0_alignType = Mux(s0_sel_src.isvec, s0_sel_src.alignedType(1,0), s0_sel_src.uop.fuOpType(1, 0))

  val s0_addr_aligned = LookupTree(s0_alignType, List(
    "b00".U   -> true.B,                   //b
    "b01".U   -> (s0_dcache_vaddr(0)    === 0.U), //h
    "b10".U   -> (s0_dcache_vaddr(1, 0) === 0.U), //w
    "b11".U   -> (s0_dcache_vaddr(2, 0) === 0.U)  //d
  ))
  // address align check
  XSError(s0_sel_src.isvec && s0_dcache_vaddr(3, 0) =/= 0.U && s0_sel_src.alignedType(2), "unit-stride 128 bit element is not aligned!")

  val s0_check_vaddr_low = s0_dcache_vaddr(4, 0)
  val s0_check_vaddr_Up_low = LookupTree(s0_alignType, List(
    "b00".U -> 0.U,
    "b01".U -> 1.U,
    "b10".U -> 3.U,
    "b11".U -> 7.U
  )) + s0_check_vaddr_low
  //TODO vec?
  val s0_rs_cross16Bytes = s0_check_vaddr_Up_low(4) =/= s0_check_vaddr_low(4)
  val s0_misalignWith16Byte = !s0_rs_cross16Bytes && !s0_addr_aligned && !s0_hw_prf_select
  val s0_misalignNeedWakeUp = s0_sel_src.frm_mabuf && io.misalign_ldin.bits.misalignNeedWakeUp
  val s0_finalSplit = s0_sel_src.frm_mabuf && io.misalign_ldin.bits.isFinalSplit
  s0_is128bit := s0_sel_src.is128bit || s0_misalignWith16Byte

  // only first issue of int / vec load intructions need to check full vaddr
  s0_tlb_fullva := Mux(s0_src_valid_vec(mab_idx),
    io.misalign_ldin.bits.fullva,
    Mux(s0_src_select_vec(vec_iss_idx),
      io.vecldin.bits.vaddr,
      Mux(
        s0_src_select_vec(int_iss_idx),
        io.ldin.bits.src(0) + SignExt(io.ldin.bits.uop.imm(11, 0), XLEN),
        s0_dcache_vaddr
      )
    )
  )

  s0_tlb_hlv := Mux(
    s0_src_valid_vec(mab_idx),
    LSUOpType.isHlv(io.misalign_ldin.bits.uop.fuOpType),
    Mux(
      s0_src_valid_vec(super_rep_idx) || s0_src_valid_vec(lsq_rep_idx),
      LSUOpType.isHlv(io.replay.bits.uop.fuOpType),
      Mux(
        s0_src_valid_vec(int_iss_idx),
        LSUOpType.isHlv(io.ldin.bits.uop.fuOpType),
        false.B
      )
    )
  )
  s0_tlb_hlvx := Mux(
    s0_src_valid_vec(mab_idx),
    LSUOpType.isHlvx(io.misalign_ldin.bits.uop.fuOpType),
    Mux(
      s0_src_valid_vec(super_rep_idx) || s0_src_valid_vec(lsq_rep_idx),
      LSUOpType.isHlvx(io.replay.bits.uop.fuOpType),
      Mux(
        s0_src_valid_vec(int_iss_idx),
        LSUOpType.isHlvx(io.ldin.bits.uop.fuOpType),
        false.B
      )
    )
  )

  // accept load flow if dcache ready (tlb is always ready)
  // TODO: prefetch need writeback to loadQueueFlag
  s0_out               := DontCare
  s0_out.vaddr         := Mux(s0_nc_with_data, s0_sel_src.vaddr, s0_dcache_vaddr)
  s0_out.fullva        := Mux(s0_sel_src.frm_mabuf, s0_out.vaddr, s0_tlb_fullva)
  s0_out.mask          := s0_sel_src.mask
  s0_out.uop           := s0_sel_src.uop
  s0_out.isFirstIssue  := s0_sel_src.isFirstIssue
  s0_out.hasROBEntry   := s0_sel_src.has_rob_entry
  s0_out.isPrefetch    := s0_sel_src.prf
  s0_out.isHWPrefetch  := s0_hw_prf_select
  s0_out.isFastReplay  := s0_sel_src.fast_rep
  s0_out.isLoadReplay  := s0_sel_src.ld_rep
  s0_out.isFastPath    := s0_sel_src.l2l_fwd
  s0_out.mshrid        := s0_sel_src.mshrid
  s0_out.isvec           := s0_sel_src.isvec
  s0_out.is128bit        := s0_is128bit
  s0_out.isFrmMisAlignBuf    := s0_sel_src.frm_mabuf
  s0_out.uop_unit_stride_fof := s0_sel_src.uop_unit_stride_fof
  s0_out.paddr         :=
    Mux(s0_src_select_vec(nc_idx), io.lsq.nc_ldin.bits.paddr,
    Mux(s0_src_select_vec(fast_rep_idx), io.fast_rep_in.bits.paddr,
    Mux(s0_src_select_vec(int_iss_idx) && s0_sel_src.prf_i, 0.U,
    io.prefetch_req.bits.paddr))) // only for nc, fast_rep, prefetch
  s0_out.tlbNoQuery    := s0_tlb_no_query
  // s0_out.rob_idx_valid   := s0_rob_idx_valid
  // s0_out.inner_idx       := s0_inner_idx
  // s0_out.rob_idx         := s0_rob_idx
  s0_out.reg_offset      := s0_sel_src.reg_offset
  // s0_out.offset          := s0_offset
  s0_out.vecActive             := s0_sel_src.vecActive
  s0_out.usSecondInv    := s0_sel_src.usSecondInv
  s0_out.is_first_ele   := s0_sel_src.is_first_ele
  s0_out.elemIdx        := s0_sel_src.elemIdx
  s0_out.elemIdxInsideVd := s0_sel_src.elemIdxInsideVd
  s0_out.alignedType    := s0_sel_src.alignedType
  s0_out.mbIndex        := s0_sel_src.mbIndex
  s0_out.vecBaseVaddr   := s0_sel_src.vecBaseVaddr
  // s0_out.flowPtr         := s0_sel_src.flowPtr
  s0_out.uop.exceptionVec(loadAddrMisaligned) := (!s0_addr_aligned || s0_sel_src.uop.exceptionVec(loadAddrMisaligned)) && s0_sel_src.vecActive && !s0_misalignWith16Byte
  s0_out.isMisalign := (!s0_addr_aligned || s0_sel_src.uop.exceptionVec(loadAddrMisaligned)) && s0_sel_src.vecActive
  s0_out.forward_tlDchannel := s0_src_select_vec(super_rep_idx)
  when(io.tlb.req.valid && s0_sel_src.isFirstIssue) {
    s0_out.uop.debugInfo.tlbFirstReqTime := GTimer()
  }.otherwise{
    s0_out.uop.debugInfo.tlbFirstReqTime := s0_sel_src.uop.debugInfo.tlbFirstReqTime
  }
  s0_out.schedIndex     := s0_sel_src.sched_idx
  //for Svpbmt Nc
  s0_out.nc := s0_sel_src.isnc
  s0_out.data := s0_sel_src.data
  s0_out.misalignWith16Byte    := s0_misalignWith16Byte
  s0_out.misalignNeedWakeUp := s0_misalignNeedWakeUp
  s0_out.isFinalSplit := s0_finalSplit

  // load fast replay
  io.fast_rep_in.ready := (s0_can_go && io.dcache.req.ready && s0_src_ready_vec(fast_rep_idx))

  // mmio
  io.lsq.uncache.ready := s0_mmio_fire
  io.lsq.nc_ldin.ready := s0_src_ready_vec(nc_idx) && s0_can_go

  // load flow source ready
  // cache missed load has highest priority
  // always accept cache missed load flow from load replay queue
  io.replay.ready := (s0_can_go && io.dcache.req.ready && (s0_src_ready_vec(lsq_rep_idx) && !s0_rep_stall || s0_src_select_vec(super_rep_idx)))

  // accept load flow from rs when:
  // 1) there is no lsq-replayed load
  // 2) there is no fast replayed load
  // 3) there is no high confidence prefetch request
  io.vecldin.ready := s0_can_go && io.dcache.req.ready && s0_src_ready_vec(vec_iss_idx)
  io.ldin.ready := s0_can_go && io.dcache.req.ready && s0_src_ready_vec(int_iss_idx)
  io.misalign_ldin.ready := s0_can_go && io.dcache.req.ready && s0_src_ready_vec(mab_idx)

  // for hw prefetch load flow feedback, to be added later
  // io.prefetch_in.ready := s0_hw_prf_select

  // dcache replacement extra info
  // TODO: should prefetch load update replacement?
  io.dcache.replacementUpdated := Mux(s0_src_select_vec(lsq_rep_idx) || s0_src_select_vec(super_rep_idx), io.replay.bits.replacementUpdated, false.B)

  // load wakeup
  // TODO: vector load wakeup? frm_mabuf wakeup?
  val s0_wakeup_selector = Seq(
    s0_misalign_wakeup_fire,
    s0_src_valid_vec(super_rep_idx),
    s0_src_valid_vec(fast_rep_idx),
    s0_mmio_fire,
    s0_nc_fire,
    s0_src_valid_vec(lsq_rep_idx),
    s0_src_valid_vec(int_iss_idx)
  )
  val s0_wakeup_format = Seq(
    io.misalign_ldin.bits.uop,
    io.replay.bits.uop,
    io.fast_rep_in.bits.uop,
    io.lsq.uncache.bits.uop,
    io.lsq.nc_ldin.bits.uop,
    io.replay.bits.uop,
    io.ldin.bits.uop,
  )
  val s0_wakeup_uop = ParallelPriorityMux(s0_wakeup_selector, s0_wakeup_format)
  io.wakeup.valid := s0_fire && !s0_sel_src.isvec && !s0_sel_src.frm_mabuf && (
    s0_src_valid_vec(super_rep_idx) ||
    s0_src_valid_vec(fast_rep_idx) ||
    s0_src_valid_vec(lsq_rep_idx) ||
    (s0_src_valid_vec(int_iss_idx) && !s0_sel_src.prf &&
    !s0_src_valid_vec(vec_iss_idx) && !s0_src_valid_vec(high_pf_idx))
  ) || s0_mmio_fire || s0_nc_fire || s0_misalign_wakeup_fire
  io.wakeup.bits := s0_wakeup_uop

  // prefetch.i(Zicbop)
  io.ifetchPrefetch.valid := RegNext(s0_src_select_vec(int_iss_idx) && s0_sel_src.prf_i)
  io.ifetchPrefetch.bits.vaddr := RegEnable(s0_out.vaddr, 0.U, s0_src_select_vec(int_iss_idx) && s0_sel_src.prf_i)

  XSDebug(io.dcache.req.fire,
    p"[DCACHE LOAD REQ] pc ${Hexadecimal(s0_sel_src.uop.pc)}, vaddr ${Hexadecimal(s0_dcache_vaddr)}\n"
  )
  XSDebug(s0_valid,
    p"S0: pc ${Hexadecimal(s0_out.uop.pc)}, lId ${Hexadecimal(s0_out.uop.lqIdx.asUInt)}, " +
    p"vaddr ${Hexadecimal(s0_out.vaddr)}, mask ${Hexadecimal(s0_out.mask)}\n")

  // Pipeline
  // --------------------------------------------------------------------------------
  // stage 1
  // --------------------------------------------------------------------------------
  // TLB resp (send paddr to dcache)
  val s1_valid      = RegInit(false.B)
  val s1_in         = Wire(new LqWriteBundle)
  val s1_out        = Wire(new LqWriteBundle)
  val s1_kill       = Wire(Bool())
  val s1_can_go     = s2_ready
  val s1_fire       = s1_valid && !s1_kill && s1_can_go
  val s1_vecActive        = RegEnable(s0_out.vecActive, true.B, s0_fire)
  val s1_nc_with_data = RegNext(s0_nc_with_data)

  s1_ready := !s1_valid || s1_kill || s2_ready
  when (s0_fire) { s1_valid := true.B }
  .elsewhen (s1_fire) { s1_valid := false.B }
  .elsewhen (s1_kill) { s1_valid := false.B }
  s1_in   := RegEnable(s0_out, s0_fire)

  val s1_fast_rep_dly_kill = RegEnable(io.fast_rep_in.bits.lateKill, io.fast_rep_in.valid) && s1_in.isFastReplay
  val s1_fast_rep_dly_err =  RegEnable(io.fast_rep_in.bits.delayedLoadError, io.fast_rep_in.valid) && s1_in.isFastReplay
  val s1_l2l_fwd_dly_err  = RegEnable(io.l2l_fwd_in.dly_ld_err, io.l2l_fwd_in.valid) && s1_in.isFastPath
  val s1_dly_err          = s1_fast_rep_dly_err || s1_l2l_fwd_dly_err
  val s1_vaddr_hi         = Wire(UInt())
  val s1_vaddr_lo         = Wire(UInt())
  val s1_vaddr            = Wire(UInt())
  val s1_paddr_dup_lsu    = Wire(UInt())
  val s1_gpaddr_dup_lsu   = Wire(UInt())
  val s1_paddr_dup_dcache = Wire(UInt())
  val s1_exception        = ExceptionNO.selectByFu(s1_out.uop.exceptionVec, LduCfg).asUInt.orR   // af & pf exception were modified below.
  val s1_tlb_miss         = io.tlb.resp.bits.miss && io.tlb.resp.valid && s1_valid
  val s1_tlb_fast_miss    = io.tlb.resp.bits.fastMiss && io.tlb.resp.valid && s1_valid
  val s1_tlb_hit          = !io.tlb.resp.bits.miss && io.tlb.resp.valid && s1_valid
  val s1_pbmt             = Mux(s1_tlb_hit, io.tlb.resp.bits.pbmt.head, 0.U(Pbmt.width.W))
  val s1_nc               = s1_in.nc
  val s1_prf              = s1_in.isPrefetch
  val s1_hw_prf           = s1_in.isHWPrefetch
  val s1_sw_prf           = s1_prf && !s1_hw_prf
  val s1_tlb_memidx       = io.tlb.resp.bits.memidx

  s1_vaddr_hi         := s1_in.vaddr(VAddrBits - 1, 6)
  s1_vaddr_lo         := s1_in.vaddr(5, 0)
  s1_vaddr            := Cat(s1_vaddr_hi, s1_vaddr_lo)
  s1_paddr_dup_lsu    := Mux(s1_in.tlbNoQuery, s1_in.paddr, io.tlb.resp.bits.paddr(0))
  s1_paddr_dup_dcache := Mux(s1_in.tlbNoQuery, s1_in.paddr, io.tlb.resp.bits.paddr(1))
  s1_gpaddr_dup_lsu   := Mux(s1_in.isFastReplay, s1_in.paddr, io.tlb.resp.bits.gpaddr(0))

  when (s1_tlb_memidx.is_ld && io.tlb.resp.valid && !s1_tlb_miss && s1_tlb_memidx.idx === s1_in.uop.lqIdx.value) {
    // printf("load idx = %d\n", s1_tlb_memidx.idx)
    s1_out.uop.debugInfo.tlbRespTime := GTimer()
  }

  io.tlb.req_kill   := s1_kill || s1_dly_err
  io.tlb.req.bits.pmp_addr := s1_in.paddr
  io.tlb.resp.ready := true.B

  io.dcache.s1_paddr_dup_lsu    <> s1_paddr_dup_lsu
  io.dcache.s1_paddr_dup_dcache <> s1_paddr_dup_dcache
  io.dcache.s1_kill             := s1_kill || s1_dly_err || s1_tlb_miss || s1_exception
  io.dcache.s1_kill_data_read   := s1_kill || s1_dly_err || s1_tlb_fast_miss

  // store to load forwarding
  io.sbuffer.valid := s1_valid && !(s1_exception || s1_tlb_miss || s1_kill || s1_dly_err || s1_prf)
  io.sbuffer.vaddr := s1_vaddr
  io.sbuffer.paddr := s1_paddr_dup_lsu
  io.sbuffer.uop   := s1_in.uop
  io.sbuffer.sqIdx := s1_in.uop.sqIdx
  io.sbuffer.mask  := s1_in.mask
  io.sbuffer.pc    := s1_in.uop.pc // FIXME: remove it

  io.ubuffer.valid := s1_valid && s1_nc_with_data && !(s1_exception || s1_tlb_miss || s1_kill || s1_dly_err || s1_prf)
  io.ubuffer.vaddr := s1_vaddr
  io.ubuffer.paddr := s1_paddr_dup_lsu
  io.ubuffer.uop   := s1_in.uop
  io.ubuffer.sqIdx := s1_in.uop.sqIdx
  io.ubuffer.mask  := s1_in.mask
  io.ubuffer.pc    := s1_in.uop.pc // FIXME: remove it

  io.lsq.forward.valid     := s1_valid && !(s1_exception || s1_tlb_miss || s1_kill || s1_dly_err || s1_prf)
  io.lsq.forward.vaddr     := s1_vaddr
  io.lsq.forward.paddr     := s1_paddr_dup_lsu
  io.lsq.forward.uop       := s1_in.uop
  io.lsq.forward.sqIdx     := s1_in.uop.sqIdx
  io.lsq.forward.sqIdxMask := 0.U
  io.lsq.forward.mask      := s1_in.mask
  io.lsq.forward.pc        := s1_in.uop.pc // FIXME: remove it

  // st-ld violation query
    // if store unit is 128-bits memory access, need match 128-bit
  val s1_nuke_paddr_match = VecInit((0 until StorePipelineWidth).map{
    case index => {
      val stMathType = io.stld_nuke_query(index).bits.matchType
      val stAddr = io.stld_nuke_query(index).bits.paddr
      val isMatch128Bit = StLdNukeMatchType.isQuadWord(stMathType) || ((s1_in.isvec || s1_in.misalignWith16Byte) && s1_in.is128bit)
      PriorityMux(Seq(
        StLdNukeMatchType.isCacheLine(stMathType) -> (s1_paddr_dup_lsu(PAddrBits-1, blockOffBits) === stAddr(PAddrBits-1, blockOffBits)),
        isMatch128Bit                             -> (s1_paddr_dup_lsu(PAddrBits-1, 4) === stAddr(PAddrBits-1, 4)),
        StLdNukeMatchType.isNormal(stMathType)    -> (s1_paddr_dup_lsu(PAddrBits-1, 3) === stAddr(PAddrBits-1, 3)))
      )
    }
  })
  val s1_nuke = VecInit((0 until StorePipelineWidth).map(w => {
                       io.stld_nuke_query(w).valid && // query valid
                       isAfter(s1_in.uop.robIdx, io.stld_nuke_query(w).bits.robIdx) && // older store
                       s1_nuke_paddr_match(w) && // paddr match
                       (s1_in.mask & io.stld_nuke_query(w).bits.mask).orR // data mask contain
                      })).asUInt.orR && !s1_tlb_miss

  s1_out                   := s1_in
  s1_out.vaddr             := s1_vaddr
  s1_out.fullva            := Mux(s1_in.isFrmMisAlignBuf, s1_in.vaddr, io.tlb.resp.bits.fullva)
  s1_out.vaNeedExt         := io.tlb.resp.bits.excp(0).vaNeedExt
  s1_out.isHyper           := io.tlb.resp.bits.excp(0).isHyper
  s1_out.paddr             := s1_paddr_dup_lsu
  s1_out.gpaddr            := s1_gpaddr_dup_lsu
  s1_out.isForVSnonLeafPTE := io.tlb.resp.bits.isForVSnonLeafPTE
  s1_out.tlbMiss           := s1_tlb_miss
  s1_out.ptwBack           := io.tlb.resp.bits.ptwBack
  s1_out.rep_info.debug    := s1_in.uop.debugInfo
  s1_out.rep_info.nuke     := s1_nuke && !s1_sw_prf
  s1_out.delayedLoadError  := s1_dly_err
  s1_out.nc := (s1_nc || Pbmt.isNC(s1_pbmt)) && !s1_prf
  s1_out.mmio := Pbmt.isIO(s1_pbmt)

  when (!s1_dly_err) {
    // current ori test will cause the case of ldest == 0, below will be modifeid in the future.
    // af & pf exception were modified
    // if is tlbNoQuery request, don't trigger exception from tlb resp
    s1_out.uop.exceptionVec(loadPageFault)   := io.tlb.resp.bits.excp(0).pf.ld && s1_vecActive && !s1_tlb_miss && !s1_in.tlbNoQuery
    s1_out.uop.exceptionVec(loadGuestPageFault)   := io.tlb.resp.bits.excp(0).gpf.ld && !s1_tlb_miss && !s1_in.tlbNoQuery
    s1_out.uop.exceptionVec(loadAccessFault) := io.tlb.resp.bits.excp(0).af.ld && s1_vecActive && !s1_tlb_miss && !s1_in.tlbNoQuery
    when (RegNext(io.tlb.req.bits.checkfullva) &&
      (s1_out.uop.exceptionVec(loadPageFault) ||
        s1_out.uop.exceptionVec(loadGuestPageFault) ||
        s1_out.uop.exceptionVec(loadAccessFault))) {
      s1_out.uop.exceptionVec(loadAddrMisaligned) := false.B
      s1_out.isMisalign := false.B
    }
  } .otherwise {
    s1_out.uop.exceptionVec(loadPageFault)      := false.B
    s1_out.uop.exceptionVec(loadGuestPageFault) := false.B
    s1_out.uop.exceptionVec(loadAddrMisaligned) := false.B
    s1_out.uop.exceptionVec(loadAccessFault)    := false.B
    s1_out.uop.exceptionVec(hardwareError)      := s1_dly_err && s1_vecActive
    s1_out.isMisalign := false.B
  }

  // pointer chasing
  val s1_try_ptr_chasing       = GatedValidRegNext(s0_do_try_ptr_chasing, false.B)
  val s1_ptr_chasing_vaddr     = RegEnable(s0_ptr_chasing_vaddr, s0_do_try_ptr_chasing)
  val s1_fu_op_type_not_ld     = WireInit(false.B)
  val s1_not_fast_match        = WireInit(false.B)
  val s1_addr_mismatch         = WireInit(false.B)
  val s1_addr_misaligned       = WireInit(false.B)
  val s1_fast_mismatch         = WireInit(false.B)
  val s1_ptr_chasing_canceled  = WireInit(false.B)
  val s1_cancel_ptr_chasing    = WireInit(false.B)

  val s1_redirect_reg = Wire(Valid(new Redirect))
  s1_redirect_reg.bits := RegEnable(io.redirect.bits, io.redirect.valid)
  s1_redirect_reg.valid := GatedValidRegNext(io.redirect.valid)

  s1_kill := s1_fast_rep_dly_kill ||
    s1_cancel_ptr_chasing ||
    s1_in.uop.robIdx.needFlush(io.redirect) ||
    (s1_in.uop.robIdx.needFlush(s1_redirect_reg) && !GatedValidRegNext(s0_try_ptr_chasing)) ||
    RegEnable(s0_kill, false.B, io.ldin.valid ||
      io.vecldin.valid || io.replay.valid ||
      io.l2l_fwd_in.valid || io.fast_rep_in.valid ||
      io.misalign_ldin.valid || io.lsq.nc_ldin.valid
    )

  if (EnableLoadToLoadForward) {
    // Sometimes, we need to cancel the load-load forwarding.
    // These can be put at S0 if timing is bad at S1.
    // Case 0: CACHE_SET(base + offset) != CACHE_SET(base) (lowest 6-bit addition has an overflow)
    s1_addr_mismatch     := s1_ptr_chasing_vaddr(6) ||
                             RegEnable(io.ld_fast_imm(11, 6).orR, s0_do_try_ptr_chasing)
    // Case 1: the address is not 64-bit aligned or the fuOpType is not LD
    s1_addr_misaligned := s1_ptr_chasing_vaddr(2, 0).orR
    s1_fu_op_type_not_ld := io.ldin.bits.uop.fuOpType =/= LSUOpType.ld
    // Case 2: this load-load uop is cancelled
    s1_ptr_chasing_canceled := !io.ldin.valid
    // Case 3: fast mismatch
    s1_fast_mismatch := RegEnable(!io.ld_fast_match, s0_do_try_ptr_chasing)

    when (s1_try_ptr_chasing) {
      s1_cancel_ptr_chasing := s1_addr_mismatch ||
                               s1_addr_misaligned ||
                               s1_fu_op_type_not_ld ||
                               s1_ptr_chasing_canceled ||
                               s1_fast_mismatch

      s1_in.uop           := io.ldin.bits.uop
      s1_in.isFirstIssue  := io.ldin.bits.isFirstIssue
      s1_vaddr_lo         := s1_ptr_chasing_vaddr(5, 0)
      s1_paddr_dup_lsu    := Cat(io.tlb.resp.bits.paddr(0)(PAddrBits - 1, 6), s1_vaddr_lo)
      s1_paddr_dup_dcache := Cat(io.tlb.resp.bits.paddr(0)(PAddrBits - 1, 6), s1_vaddr_lo)

      // recored tlb time when get the data to ensure the correctness of the latency calculation (although it should not record in here, because it does not use tlb)
      s1_in.uop.debugInfo.tlbFirstReqTime := GTimer()
      s1_in.uop.debugInfo.tlbRespTime     := GTimer()
    }
    when (!s1_cancel_ptr_chasing) {
      s0_ptr_chasing_canceled := s1_try_ptr_chasing &&
        !io.replay.fire && !io.fast_rep_in.fire &&
        !(s0_src_valid_vec(high_pf_idx) && io.canAcceptHighConfPrefetch) &&
        !io.misalign_ldin.fire &&
        !io.lsq.nc_ldin.valid
      when (s1_try_ptr_chasing) {
        io.ldin.ready := true.B
      }
    }
  }

  // pre-calcuate sqIdx mask in s0, then send it to lsq in s1 for forwarding
  val s1_sqIdx_mask = RegEnable(UIntToMask(s0_out.uop.sqIdx.value, StoreQueueSize), s0_fire)
  // to enable load-load, sqIdxMask must be calculated based on ldin.uop
  // If the timing here is not OK, load-load forwarding has to be disabled.
  // Or we calculate sqIdxMask at RS??
  io.lsq.forward.sqIdxMask := s1_sqIdx_mask
  if (EnableLoadToLoadForward) {
    when (s1_try_ptr_chasing) {
      io.lsq.forward.sqIdxMask := UIntToMask(io.ldin.bits.uop.sqIdx.value, StoreQueueSize)
    }
  }

  io.forward_mshr.valid  := s1_valid && s1_out.forward_tlDchannel
  io.forward_mshr.mshrid := s1_out.mshrid
  io.forward_mshr.paddr  := s1_out.paddr

  val loadTrigger = Module(new MemTrigger(MemType.LOAD))
  loadTrigger.io.fromCsrTrigger.tdataVec             := io.fromCsrTrigger.tdataVec
  loadTrigger.io.fromCsrTrigger.tEnableVec           := io.fromCsrTrigger.tEnableVec
  loadTrigger.io.fromCsrTrigger.triggerCanRaiseBpExp := io.fromCsrTrigger.triggerCanRaiseBpExp
  loadTrigger.io.fromCsrTrigger.debugMode            := io.fromCsrTrigger.debugMode
  loadTrigger.io.fromLoadStore.vaddr                 := s1_vaddr
  loadTrigger.io.fromLoadStore.isVectorUnitStride    := s1_in.isvec && s1_in.is128bit
  loadTrigger.io.fromLoadStore.mask                  := s1_in.mask

  val s1_trigger_action = loadTrigger.io.toLoadStore.triggerAction
  val s1_trigger_debug_mode = TriggerAction.isDmode(s1_trigger_action)
  val s1_trigger_breakpoint = TriggerAction.isExp(s1_trigger_action)
  s1_out.uop.trigger                  := s1_trigger_action
  s1_out.uop.exceptionVec(breakPoint) := s1_trigger_breakpoint
  s1_out.vecVaddrOffset := Mux(
    s1_trigger_debug_mode || s1_trigger_breakpoint,
    loadTrigger.io.toLoadStore.triggerVaddr - s1_in.vecBaseVaddr,
    s1_in.vaddr + genVFirstUnmask(s1_in.mask).asUInt - s1_in.vecBaseVaddr
  )
  s1_out.vecTriggerMask := Mux(s1_trigger_debug_mode || s1_trigger_breakpoint, loadTrigger.io.toLoadStore.triggerMask, 0.U)

  XSDebug(s1_valid,
    p"S1: pc ${Hexadecimal(s1_out.uop.pc)}, lId ${Hexadecimal(s1_out.uop.lqIdx.asUInt)}, tlb_miss ${io.tlb.resp.bits.miss}, " +
    p"paddr ${Hexadecimal(s1_out.paddr)}, mmio ${s1_out.mmio}\n")

  // Pipeline
  // --------------------------------------------------------------------------------
  // stage 2
  // --------------------------------------------------------------------------------
  // s2: DCache resp
  val s2_valid  = RegInit(false.B)
  val s2_in     = Wire(new LqWriteBundle)
  val s2_out    = Wire(new LqWriteBundle)
  val s2_kill   = Wire(Bool())
  val s2_can_go = s3_ready
  val s2_fire   = s2_valid && !s2_kill && s2_can_go
  val s2_vecActive = RegEnable(s1_out.vecActive, true.B, s1_fire)
  val s2_isvec  = RegEnable(s1_out.isvec, false.B, s1_fire)
  val s2_data_select  = genRdataOH(s2_out.uop)
  val s2_data_select_by_offset = genDataSelectByOffset(s2_out.paddr(3, 0))
  val s2_frm_mabuf = s2_in.isFrmMisAlignBuf
  val s2_pbmt = RegEnable(s1_pbmt, s1_fire)
  val s2_trigger_debug_mode = RegEnable(s1_trigger_debug_mode, false.B, s1_fire)
  val s2_nc_with_data = RegNext(s1_nc_with_data)
  val s2_mmio_req = Wire(Valid(new MemExuOutput))
  s2_mmio_req.valid := RegNextN(io.lsq.uncache.fire, 2, Some(false.B))
  s2_mmio_req.bits  := RegNextN(io.lsq.uncache.bits, 2)

  val s3_misalign_wakeup_req = Wire(Valid(new LqWriteBundle))
  val s3_misalign_wakeup_req_bits = WireInit(0.U.asTypeOf(new LqWriteBundle))
  connectSamePort(s3_misalign_wakeup_req_bits, io.misalign_ldin.bits)
  s3_misalign_wakeup_req.valid := RegNextN(io.misalign_ldin.bits.misalignNeedWakeUp && io.misalign_ldin.fire, 3, Some(false.B))
  s3_misalign_wakeup_req.bits  := RegNextN(s3_misalign_wakeup_req_bits, 3)

  s2_kill := s2_in.uop.robIdx.needFlush(io.redirect)
  s2_ready := !s2_valid || s2_kill || s3_ready
  when (s1_fire) { s2_valid := true.B }
  .elsewhen (s2_fire) { s2_valid := false.B }
  .elsewhen (s2_kill) { s2_valid := false.B }
  s2_in := RegEnable(s1_out, s1_fire)

  val s2_pmp = WireInit(io.pmp)
  val s2_isMisalign = WireInit(s2_in.isMisalign)

  val s2_prf    = s2_in.isPrefetch
  val s2_hw_prf = s2_in.isHWPrefetch
  val s2_exception_vec = WireInit(s2_in.uop.exceptionVec)

  // exception that may cause load addr to be invalid / illegal
  // if such exception happen, that inst and its exception info
  // will be force writebacked to rob

  // The response signal of `pmp/pma` is credible only after the physical address is actually generated.
  // Therefore, the response signals of pmp/pma generated after an address translation has produced an `access fault` or a `page fault` are completely unreliable.
  val s2_un_access_exception =  s2_vecActive && (
    s2_in.uop.exceptionVec(loadAccessFault) ||
    s2_in.uop.exceptionVec(loadPageFault)   ||
    s2_in.uop.exceptionVec(loadGuestPageFault)
  )
  // This real physical address is located in uncache space.
  val s2_actually_uncache = !s2_in.tlbMiss && !s2_un_access_exception && Pbmt.isPMA(s2_pbmt) && s2_pmp.mmio || s2_in.nc || s2_in.mmio
  val s2_uncache = !s2_prf && s2_actually_uncache
  val s2_memBackTypeMM = !s2_pmp.mmio
  when (!s2_in.delayedLoadError) {
    s2_exception_vec(loadAccessFault) := s2_vecActive && (
      s2_in.uop.exceptionVec(loadAccessFault) ||
      s2_pmp.ld ||
      s2_isvec && s2_uncache ||
      io.dcache.resp.bits.tag_error && GatedValidRegNext(io.csrCtrl.cache_error_enable)
    )
  }

  // soft prefetch will not trigger any exception (but ecc error interrupt may
  // be triggered)
  val s2_tlb_unrelated_exceps = s2_in.uop.exceptionVec(breakPoint)
  when (!s2_in.delayedLoadError && (s2_prf || s2_in.tlbMiss && !s2_tlb_unrelated_exceps)) {
    s2_exception_vec := 0.U.asTypeOf(s2_exception_vec.cloneType)
    s2_isMisalign := false.B
  }
  val s2_exception = s2_vecActive &&
                    (s2_trigger_debug_mode || ExceptionNO.selectByFu(s2_exception_vec, LduCfg).asUInt.orR)
  val s2_mis_align = s2_valid && GatedValidRegNext(io.csrCtrl.hd_misalign_ld_enable) &&
                     s2_out.isMisalign && !s2_in.misalignWith16Byte && !s2_exception_vec(breakPoint) && !s2_trigger_debug_mode && !s2_uncache
  val (s2_fwd_frm_d_chan, s2_fwd_data_frm_d_chan, s2_d_corrupt) = io.tl_d_channel.forward(s1_valid && s1_out.forward_tlDchannel, s1_out.mshrid, s1_out.paddr)
  val (s2_fwd_data_valid, s2_fwd_frm_mshr, s2_fwd_data_frm_mshr, s2_mshr_corrupt) = io.forward_mshr.forward()
  val s2_fwd_frm_d_chan_or_mshr = s2_fwd_data_valid && (s2_fwd_frm_d_chan || s2_fwd_frm_mshr)

  // writeback access fault caused by ecc error / bus error
  // * ecc data error is slow to generate, so we will not use it until load stage 3
  // * in load stage 3, an extra signal io.load_error will be used to
  // * if pbmt =/= 0, mmio is up to pbmt; otherwise, it's up to pmp
  val s2_tlb_hit = RegNext(s1_tlb_hit)
  val s2_mmio = !s2_prf &&
    !s2_exception && !s2_in.tlbMiss &&
    Mux(Pbmt.isUncache(s2_pbmt), s2_in.mmio, s2_tlb_hit && s2_pmp.mmio)

  val s2_full_fwd      = Wire(Bool())
  val s2_mem_amb       = s2_in.uop.storeSetHit &&
                         io.lsq.forward.addrInvalid && RegNext(io.lsq.forward.valid)

  val s2_tlb_miss      = s2_in.tlbMiss
  val s2_fwd_fail      = io.lsq.forward.dataInvalid && RegNext(io.lsq.forward.valid)
  val s2_dcache_miss   = io.dcache.resp.bits.miss &&
                         !s2_fwd_frm_d_chan_or_mshr &&
                         !s2_full_fwd && !s2_in.nc

  val s2_mq_nack       = io.dcache.s2_mq_nack &&
                         !s2_fwd_frm_d_chan_or_mshr &&
                         !s2_full_fwd && !s2_in.nc

  val s2_bank_conflict = io.dcache.s2_bank_conflict &&
                         !s2_fwd_frm_d_chan_or_mshr &&
                         !s2_full_fwd && !s2_in.nc

  val s2_wpu_pred_fail = io.dcache.s2_wpu_pred_fail &&
                        !s2_fwd_frm_d_chan_or_mshr &&
                        !s2_full_fwd && !s2_in.nc

  val s2_rar_nack      = io.lsq.ldld_nuke_query.req.valid &&
                         !io.lsq.ldld_nuke_query.req.ready

  val s2_raw_nack      = io.lsq.stld_nuke_query.req.valid &&
                         !io.lsq.stld_nuke_query.req.ready
  // st-ld violation query
  //  NeedFastRecovery Valid when
  //  1. Fast recovery query request Valid.
  //  2. Load instruction is younger than requestors(store instructions).
  //  3. Physical address match.
  //  4. Data contains.
  val s2_nuke_paddr_match = VecInit((0 until StorePipelineWidth).map{
    case index => {
      val stMathType = io.stld_nuke_query(index).bits.matchType
      val stAddr = io.stld_nuke_query(index).bits.paddr
      val isMatch128Bit = StLdNukeMatchType.isQuadWord(stMathType) || ((s2_in.isvec || s2_in.misalignWith16Byte) && s2_in.is128bit)
      PriorityMux(Seq(
        StLdNukeMatchType.isCacheLine(stMathType) -> (s2_in.paddr(PAddrBits-1, blockOffBits) === stAddr(PAddrBits-1, blockOffBits)),
        isMatch128Bit                             -> (s2_in.paddr(PAddrBits-1, 4) === stAddr(PAddrBits-1, 4)),
        StLdNukeMatchType.isNormal(stMathType)    -> (s2_in.paddr(PAddrBits-1, 3) === stAddr(PAddrBits-1, 3)))
      )
    }
  })
  val s2_nuke          = VecInit((0 until StorePipelineWidth).map(w => {
                          io.stld_nuke_query(w).valid && // query valid
                          isAfter(s2_in.uop.robIdx, io.stld_nuke_query(w).bits.robIdx) && // older store
                          s2_nuke_paddr_match(w) && // paddr match
                          (s2_in.mask & io.stld_nuke_query(w).bits.mask).orR // data mask contain
                        })).asUInt.orR && !s2_tlb_miss || s2_in.rep_info.nuke

  val s2_cache_handled   = io.dcache.resp.bits.handled

  //if it is NC with data, it should handle the replayed situation.
  //else s2_uncache will enter uncache buffer.
  val s2_troublem        = !s2_exception &&
                           (!s2_uncache || s2_nc_with_data) &&
                           !s2_prf &&
                           !s2_in.delayedLoadError

  io.dcache.resp.ready  := true.B
  val s2_dcache_should_resp = !(s2_in.tlbMiss || s2_exception || s2_in.delayedLoadError || s2_uncache || s2_prf)
  assert(!(s2_valid && (s2_dcache_should_resp && !io.dcache.resp.valid)), "DCache response got lost")

  // fast replay require
  val s2_dcache_fast_rep = (s2_mq_nack || !s2_dcache_miss && (s2_bank_conflict || s2_wpu_pred_fail))
  val s2_nuke_fast_rep   = !s2_mq_nack &&
                           !s2_dcache_miss &&
                           !s2_bank_conflict &&
                           !s2_wpu_pred_fail &&
                           s2_nuke

  val s2_fast_rep = !s2_in.isFastReplay &&
                    !s2_mem_amb &&
                    !s2_tlb_miss &&
                    !s2_fwd_fail &&
                    (s2_dcache_fast_rep || s2_nuke_fast_rep) &&
                    s2_troublem

  // need allocate new entry
  val s2_can_query = !(s2_dcache_fast_rep || s2_nuke) && s2_troublem

  val s2_data_fwded = s2_dcache_miss && s2_full_fwd

  // For misaligned, we will keep the misaligned exception at S2 and before.
  // Here a judgement is made as to whether a misaligned exception needs to actually be generated.
  // We will generate misaligned exceptions at mmio.
  val s2_real_exceptionVec = WireInit(s2_exception_vec)
  s2_real_exceptionVec(loadAddrMisaligned) := (s2_out.isMisalign || s2_out.isFrmMisAlignBuf) && s2_uncache && !s2_isvec
  s2_real_exceptionVec(loadAccessFault) := s2_exception_vec(loadAccessFault) ||
    s2_fwd_frm_d_chan && s2_d_corrupt ||
    s2_fwd_data_valid && s2_fwd_frm_mshr && s2_mshr_corrupt
  val s2_real_exception = s2_vecActive &&
    (s2_trigger_debug_mode || ExceptionNO.selectByFu(s2_real_exceptionVec, LduCfg).asUInt.orR)

  val s2_fwd_vp_match_invalid = io.lsq.forward.matchInvalid || io.sbuffer.matchInvalid || io.ubuffer.matchInvalid
  val s2_vp_match_fail = s2_fwd_vp_match_invalid && s2_troublem
  val s2_safe_wakeup = !s2_out.rep_info.need_rep && !s2_mmio && (!s2_in.nc || s2_nc_with_data) && !s2_mis_align && !s2_real_exception // don't need to replay and is not a mmio\misalign no data
  val s2_safe_writeback = s2_real_exception || s2_safe_wakeup || s2_vp_match_fail

  // ld-ld violation require
  /**
    * In order to ensure timing, the RAR enqueue conditions need to be compromised, worst source of timing from pmp and missQueue.
    *   * if LoadQueueRARSize == VirtualLoadQueueSize, just need to exclude prefetching.
    *   * if LoadQueueRARSize < VirtualLoadQueueSize, need to consider the situation of s2_can_query
    */
  if (LoadQueueRARSize == VirtualLoadQueueSize) {
    io.lsq.ldld_nuke_query.req.valid           := s2_valid && !s2_prf
  } else {
    io.lsq.ldld_nuke_query.req.valid           := s2_valid && s2_can_query
  }
  io.lsq.ldld_nuke_query.req.bits.uop        := s2_in.uop
  io.lsq.ldld_nuke_query.req.bits.mask       := s2_in.mask
  io.lsq.ldld_nuke_query.req.bits.paddr      := s2_in.paddr
  io.lsq.ldld_nuke_query.req.bits.data_valid := Mux(s2_full_fwd || s2_fwd_data_valid || s2_nc_with_data, true.B, !s2_dcache_miss)
  io.lsq.ldld_nuke_query.req.bits.is_nc := s2_nc_with_data

  // st-ld violation require
  io.lsq.stld_nuke_query.req.valid           := s2_valid && s2_can_query
  io.lsq.stld_nuke_query.req.bits.uop        := s2_in.uop
  io.lsq.stld_nuke_query.req.bits.mask       := s2_in.mask
  io.lsq.stld_nuke_query.req.bits.paddr      := s2_in.paddr
  io.lsq.stld_nuke_query.req.bits.data_valid := Mux(s2_full_fwd || s2_fwd_data_valid || s2_nc_with_data, true.B, !s2_dcache_miss)
  io.lsq.stld_nuke_query.req.bits.is_nc := s2_nc_with_data

  // merge forward result
  // lsq has higher priority than sbuffer
  val s2_fwd_mask = Wire(Vec((VLEN/8), Bool()))
  val s2_fwd_data = Wire(Vec((VLEN/8), UInt(8.W)))
  s2_full_fwd := ((~s2_fwd_mask.asUInt).asUInt & s2_in.mask) === 0.U && !io.lsq.forward.dataInvalid
  // generate XLEN/8 Muxs
  for (i <- 0 until VLEN / 8) {
    s2_fwd_mask(i) := io.lsq.forward.forwardMask(i) || io.sbuffer.forwardMask(i) || io.ubuffer.forwardMask(i)
    s2_fwd_data(i) :=
      Mux(io.lsq.forward.forwardMask(i), io.lsq.forward.forwardData(i),
      Mux(s2_nc_with_data, io.ubuffer.forwardData(i),
      io.sbuffer.forwardData(i)))
  }

  XSDebug(s2_fire, "[FWD LOAD RESP] pc %x fwd %x(%b) + %x(%b)\n",
    s2_in.uop.pc,
    io.lsq.forward.forwardData.asUInt, io.lsq.forward.forwardMask.asUInt,
    s2_in.forwardData.asUInt, s2_in.forwardMask.asUInt
  )

  //
  s2_out                     := s2_in
  s2_out.uop.fpWen           := s2_in.uop.fpWen
  s2_out.nc                  := s2_in.nc
  s2_out.mmio                := s2_mmio
  s2_out.memBackTypeMM       := s2_memBackTypeMM
  s2_out.isMisalign          := s2_isMisalign
  s2_out.uop.flushPipe       := false.B
  s2_out.uop.exceptionVec    := s2_real_exceptionVec
  s2_out.forwardMask         := s2_fwd_mask
  s2_out.forwardData         := s2_fwd_data
  s2_out.handledByMSHR       := s2_cache_handled
  s2_out.miss                := s2_dcache_miss && s2_troublem
  s2_out.feedbacked          := io.feedback_fast.valid
  s2_out.uop.vpu.vstart      := Mux(s2_in.isLoadReplay || s2_in.isFastReplay, s2_in.uop.vpu.vstart, s2_in.vecVaddrOffset >> s2_in.uop.vpu.veew)

  // Generate replay signal caused by:
  // * st-ld violation check
  // * tlb miss
  // * dcache replay
  // * forward data invalid
  // * dcache miss
  s2_out.rep_info.mem_amb         := s2_mem_amb && s2_troublem
  s2_out.rep_info.tlb_miss        := s2_tlb_miss && s2_troublem
  s2_out.rep_info.fwd_fail        := s2_fwd_fail && s2_troublem
  s2_out.rep_info.dcache_rep      := s2_mq_nack && s2_troublem
  s2_out.rep_info.dcache_miss     := s2_dcache_miss && s2_troublem
  s2_out.rep_info.bank_conflict   := s2_bank_conflict && s2_troublem
  s2_out.rep_info.wpu_fail        := s2_wpu_pred_fail && s2_troublem
  s2_out.rep_info.rar_nack        := s2_rar_nack && s2_troublem
  s2_out.rep_info.raw_nack        := s2_raw_nack && s2_troublem
  s2_out.rep_info.nuke            := s2_nuke && s2_troublem
  s2_out.rep_info.full_fwd        := s2_data_fwded
  s2_out.rep_info.data_inv_sq_idx := io.lsq.forward.dataInvalidSqIdx
  s2_out.rep_info.addr_inv_sq_idx := io.lsq.forward.addrInvalidSqIdx
  s2_out.rep_info.rep_carry       := io.dcache.resp.bits.replayCarry
  s2_out.rep_info.mshr_id         := io.dcache.resp.bits.mshr_id
  s2_out.rep_info.last_beat       := s2_in.paddr(log2Up(refillBytes))
  s2_out.rep_info.debug           := s2_in.uop.debugInfo
  s2_out.rep_info.tlb_id          := io.tlb_hint.id
  s2_out.rep_info.tlb_full        := io.tlb_hint.full

  // if forward fail, replay this inst from fetch
  val debug_fwd_fail_rep = s2_fwd_fail && !s2_troublem && !s2_in.tlbMiss
  // if ld-ld violation is detected, replay from this inst from fetch
  val debug_ldld_nuke_rep = false.B // s2_ldld_violation && !s2_mmio && !s2_is_prefetch && !s2_in.tlbMiss

  // to be removed
  io.feedback_fast.valid                 := false.B
  io.feedback_fast.bits.hit              := false.B
  io.feedback_fast.bits.flushState       := s2_in.ptwBack
  io.feedback_fast.bits.robIdx           := s2_in.uop.robIdx
  io.feedback_fast.bits.sqIdx            := s2_in.uop.sqIdx
  io.feedback_fast.bits.lqIdx            := s2_in.uop.lqIdx
  io.feedback_fast.bits.sourceType       := RSFeedbackType.lrqFull
  io.feedback_fast.bits.dataInvalidSqIdx := DontCare

  io.ldCancel.ld1Cancel := false.B

  // fast wakeup
  val s1_fast_uop_valid = WireInit(false.B)
  s1_fast_uop_valid :=
    !io.dcache.s1_disable_fast_wakeup &&
    s1_valid &&
    !s1_kill &&
    !io.tlb.resp.bits.miss &&
    !io.lsq.forward.dataInvalidFast
  io.fast_uop.valid := GatedValidRegNext(s1_fast_uop_valid) && (s2_valid && !s2_out.rep_info.need_rep && !s2_uncache && !(s2_prf && !s2_hw_prf)) && !s2_isvec && !s2_frm_mabuf
  io.fast_uop.bits := RegEnable(s1_out.uop, s1_fast_uop_valid)

  //
  io.s2_ptr_chasing                    := RegEnable(s1_try_ptr_chasing && !s1_cancel_ptr_chasing, false.B, s1_fire)

  // RegNext prefetch train for better timing
  // ** Now, prefetch train is valid at load s3 **
  val s2_prefetch_train_valid = WireInit(false.B)
  s2_prefetch_train_valid              := s2_valid && !s2_actually_uncache && (!s2_in.tlbMiss || s2_hw_prf)
  io.prefetch_train.valid              := GatedValidRegNext(s2_prefetch_train_valid)
  io.prefetch_train.bits.fromLsPipelineBundle(s2_in, latch = true, enable = s2_prefetch_train_valid)
  io.prefetch_train.bits.miss          := RegEnable(io.dcache.resp.bits.miss, s2_prefetch_train_valid) // TODO: use trace with bank conflict?
  io.prefetch_train.bits.meta_prefetch := RegEnable(io.dcache.resp.bits.meta_prefetch, s2_prefetch_train_valid)
  io.prefetch_train.bits.meta_access   := RegEnable(io.dcache.resp.bits.meta_access, s2_prefetch_train_valid)
  io.prefetch_train.bits.isFinalSplit      := false.B
  io.prefetch_train.bits.misalignWith16Byte := false.B
  io.prefetch_train.bits.misalignNeedWakeUp := false.B
  io.prefetch_train.bits.updateAddrValid := false.B
  io.prefetch_train.bits.isMisalign := false.B
  io.prefetch_train.bits.hasException := false.B
  io.s1_prefetch_spec := s1_fire
  io.s2_prefetch_spec := s2_prefetch_train_valid

  val s2_prefetch_train_l1_valid = WireInit(false.B)
  s2_prefetch_train_l1_valid              := s2_valid && !s2_actually_uncache
  io.prefetch_train_l1.valid              := GatedValidRegNext(s2_prefetch_train_l1_valid)
  io.prefetch_train_l1.bits.fromLsPipelineBundle(s2_in, latch = true, enable = s2_prefetch_train_l1_valid)
  io.prefetch_train_l1.bits.miss          := RegEnable(io.dcache.resp.bits.miss, s2_prefetch_train_l1_valid)
  io.prefetch_train_l1.bits.meta_prefetch := RegEnable(io.dcache.resp.bits.meta_prefetch, s2_prefetch_train_l1_valid)
  io.prefetch_train_l1.bits.meta_access   := RegEnable(io.dcache.resp.bits.meta_access, s2_prefetch_train_l1_valid)
  io.prefetch_train_l1.bits.isFinalSplit      := false.B
  io.prefetch_train_l1.bits.misalignWith16Byte := false.B
  io.prefetch_train_l1.bits.misalignNeedWakeUp := false.B
  io.prefetch_train_l1.bits.updateAddrValid := false.B
  io.prefetch_train_l1.bits.hasException := false.B
  io.prefetch_train_l1.bits.isMisalign := false.B
  if (env.FPGAPlatform){
    io.dcache.s0_pc := DontCare
    io.dcache.s1_pc := DontCare
    io.dcache.s2_pc := DontCare
  }else{
    io.dcache.s0_pc := s0_out.uop.pc
    io.dcache.s1_pc := s1_out.uop.pc
    io.dcache.s2_pc := s2_out.uop.pc
  }
  io.dcache.s2_kill := s2_pmp.ld || s2_pmp.st || s2_actually_uncache || s2_kill

  val s1_ld_left_fire = s1_valid && !s1_kill && s2_ready
  val s2_ld_valid_dup = RegInit(0.U(6.W))
  s2_ld_valid_dup := 0x0.U(6.W)
  when (s1_ld_left_fire && !s1_out.isHWPrefetch) { s2_ld_valid_dup := 0x3f.U(6.W) }
  when (s1_kill || s1_out.isHWPrefetch) { s2_ld_valid_dup := 0x0.U(6.W) }
  assert(RegNext((s2_valid === s2_ld_valid_dup(0)) || RegNext(s1_out.isHWPrefetch)))

  // Pipeline
  // --------------------------------------------------------------------------------
  // stage 3
  // --------------------------------------------------------------------------------
  // writeback and update load queue
  val s3_valid        = GatedValidRegNext(s2_valid && !s2_out.isHWPrefetch && !s2_out.uop.robIdx.needFlush(io.redirect))
  val s3_in           = RegEnable(s2_out, s2_fire)
  val s3_out          = Wire(Valid(new MemExuOutput))
  val s3_dcache_rep   = RegEnable(s2_dcache_fast_rep && s2_troublem, false.B, s2_fire)
  val s3_ld_valid_dup = RegEnable(s2_ld_valid_dup, s2_fire)
  val s3_fast_rep     = Wire(Bool())
  val s3_nc_with_data = RegNext(s2_nc_with_data)
  val s3_troublem     = GatedValidRegNext(s2_troublem)
  val s3_kill         = s3_in.uop.robIdx.needFlush(io.redirect)
  val s3_vecout       = Wire(new OnlyVecExuOutput)
  val s3_vecActive    = RegEnable(s2_out.vecActive, true.B, s2_fire)
  val s3_isvec        = RegEnable(s2_out.isvec, false.B, s2_fire)
  val s3_vec_alignedType = RegEnable(s2_out.alignedType, s2_fire)
  val s3_vec_mBIndex     = RegEnable(s2_out.mbIndex, s2_fire)
  val s3_frm_mabuf       = s3_in.isFrmMisAlignBuf
  val s3_mmio_req     = RegNext(s2_mmio_req)
  val s3_pdest        = RegNext(Mux(s2_valid, s2_out.uop.pdest, s2_mmio_req.bits.uop.pdest))
  val s3_rfWen        = RegEnable(Mux(s2_valid, s2_out.uop.rfWen, s2_mmio_req.bits.uop.rfWen), s2_valid || s2_mmio_req.valid)
  val s3_fpWen        = RegEnable(Mux(s2_valid, s2_out.uop.fpWen, s2_mmio_req.bits.uop.fpWen), s2_valid || s2_mmio_req.valid)
  val s3_data_select  = RegEnable(s2_data_select, 0.U(s2_data_select.getWidth.W), s2_fire)
  val s3_data_select_by_offset = RegEnable(s2_data_select_by_offset, 0.U.asTypeOf(s2_data_select_by_offset), s2_fire)
  val s3_hw_err   =
      if (EnableAccurateLoadError) {
        io.dcache.resp.bits.error_delayed && GatedValidRegNext(io.csrCtrl.cache_error_enable) && s3_troublem
      } else {
        WireInit(false.B)
      }
  val s3_safe_wakeup  = RegEnable(s2_safe_wakeup, s2_fire)
  val s3_safe_writeback = RegEnable(s2_safe_writeback, s2_fire) || s3_hw_err
  val s3_exception = RegEnable(s2_real_exception, s2_fire)
  val s3_mis_align = RegEnable(s2_mis_align, s2_fire) && !s3_exception
  val s3_misalign_can_go = RegEnable(!isAfter(s2_out.uop.lqIdx, io.lsq.lqDeqPtr) || io.misalign_allow_spec, s2_fire)
  val s3_trigger_debug_mode = RegEnable(s2_trigger_debug_mode, false.B, s2_fire)

  // TODO: Fix vector load merge buffer nack
  val s3_vec_mb_nack  = Wire(Bool())
  s3_vec_mb_nack     := false.B
  XSError(s3_valid && s3_vec_mb_nack, "Merge buffer should always accept vector loads!")

  s3_ready := !s3_valid || s3_kill || io.ldout.ready


  // forwrad last beat
  val s3_fast_rep_canceled = io.replay.valid && io.replay.bits.forward_tlDchannel || io.misalign_ldin.valid || !io.dcache.req.ready

  val s3_can_enter_lsq_valid = s3_valid && (!s3_fast_rep || s3_fast_rep_canceled) && !s3_in.feedbacked
  io.lsq.ldin.valid := s3_can_enter_lsq_valid
  // TODO: check this --by hx
  // io.lsq.ldin.valid := s3_valid && (!s3_fast_rep || !io.fast_rep_out.ready) && !s3_in.feedbacked && !s3_in.lateKill
  io.lsq.ldin.bits := s3_in
  io.lsq.ldin.bits.miss := s3_in.miss

  // connect to misalignBuffer
  val toMisalignBufferValid = s3_can_enter_lsq_valid && s3_mis_align && !s3_frm_mabuf
  io.misalign_enq.req.valid := toMisalignBufferValid && s3_misalign_can_go
  io.misalign_enq.req.bits  := s3_in
  io.misalign_enq.revoke := false.B

  /* <------- DANGEROUS: Don't change sequence here ! -------> */
  io.lsq.ldin.bits.nc_with_data := s3_nc_with_data
  io.lsq.ldin.bits.data_wen_dup := s3_ld_valid_dup.asBools
  io.lsq.ldin.bits.replacementUpdated := io.dcache.resp.bits.replacementUpdated
  io.lsq.ldin.bits.missDbUpdated := GatedValidRegNext(s2_fire && s2_in.hasROBEntry && !s2_in.tlbMiss && !s2_in.missDbUpdated)
  io.lsq.ldin.bits.updateAddrValid := !s3_mis_align && (!s3_frm_mabuf || s3_in.isFinalSplit) || s3_exception
  io.lsq.ldin.bits.hasException := false.B

  io.s3_dly_ld_err := false.B // s3_dly_ld_err && s3_valid
  io.lsq.ldin.bits.dcacheRequireReplay  := s3_dcache_rep

  val s3_vp_match_fail = GatedValidRegNext(s2_fwd_vp_match_invalid) && s3_troublem
  val s3_rep_frm_fetch = s3_vp_match_fail
  val s3_ldld_rep_inst =
      io.lsq.ldld_nuke_query.resp.valid &&
      io.lsq.ldld_nuke_query.resp.bits.rep_frm_fetch &&
      GatedValidRegNext(io.csrCtrl.ldld_vio_check_enable)
  val s3_flushPipe = s3_ldld_rep_inst

  val s3_lrq_rep_info = WireInit(s3_in.rep_info)
  s3_lrq_rep_info.misalign_nack := toMisalignBufferValid && !(io.misalign_enq.req.ready && s3_misalign_can_go)
  val s3_lrq_sel_rep_cause = PriorityEncoderOH(s3_lrq_rep_info.cause.asUInt)
  val s3_replayqueue_rep_cause = WireInit(0.U.asTypeOf(s3_in.rep_info.cause))

  val s3_mab_rep_info = WireInit(s3_in.rep_info)
  val s3_mab_sel_rep_cause = PriorityEncoderOH(s3_mab_rep_info.cause.asUInt)
  val s3_misalign_rep_cause = WireInit(0.U.asTypeOf(s3_in.rep_info.cause))

  s3_misalign_rep_cause := VecInit(s3_mab_sel_rep_cause.asBools)

  when (s3_exception || s3_hw_err || s3_rep_frm_fetch || s3_frm_mabuf) {
    s3_replayqueue_rep_cause := 0.U.asTypeOf(s3_lrq_rep_info.cause.cloneType)
  } .otherwise {
    s3_replayqueue_rep_cause := VecInit(s3_lrq_sel_rep_cause.asBools)

  }
  io.lsq.ldin.bits.rep_info.cause := s3_replayqueue_rep_cause


  // Int load, if hit, will be writebacked at s3
  s3_out.valid                := s3_valid && s3_safe_writeback && !toMisalignBufferValid
  s3_out.bits.uop             := s3_in.uop
  s3_out.bits.uop.fpWen       := s3_in.uop.fpWen
  s3_out.bits.uop.exceptionVec(loadAccessFault) := s3_in.uop.exceptionVec(loadAccessFault) && s3_vecActive
  s3_out.bits.uop.exceptionVec(hardwareError) := (s3_in.uop.exceptionVec(hardwareError) || s3_hw_err) && s3_vecActive
  s3_out.bits.uop.flushPipe   := false.B
  s3_out.bits.uop.replayInst  := false.B
  s3_out.bits.data            := s3_in.data
  s3_out.bits.isFromLoadUnit  := true.B
  s3_out.bits.debug.isMMIO    := s3_in.mmio
  s3_out.bits.debug.isNCIO    := s3_in.nc && !s3_in.memBackTypeMM
  s3_out.bits.debug.isPerfCnt := false.B
  s3_out.bits.debug.paddr     := s3_in.paddr
  s3_out.bits.debug.vaddr     := s3_in.vaddr

  // Vector load, writeback to merge buffer
  // TODO: Add assertion in merge buffer, merge buffer must accept vec load writeback
  s3_vecout.isvec             := s3_isvec
  s3_vecout.vecdata           := 0.U // Data will be assigned later
  s3_vecout.mask              := s3_in.mask
  // s3_vecout.rob_idx_valid     := s3_in.rob_idx_valid
  // s3_vecout.inner_idx         := s3_in.inner_idx
  // s3_vecout.rob_idx           := s3_in.rob_idx
  // s3_vecout.offset            := s3_in.offset
  s3_vecout.reg_offset        := s3_in.reg_offset
  s3_vecout.vecActive         := s3_vecActive
  s3_vecout.is_first_ele      := s3_in.is_first_ele
  // s3_vecout.uopQueuePtr       := DontCare // uopQueuePtr is already saved in flow queue
  // s3_vecout.flowPtr           := s3_in.flowPtr
  s3_vecout.elemIdx           := s3_in.elemIdx // elemIdx is already saved in flow queue // TODO:
  s3_vecout.elemIdxInsideVd   := s3_in.elemIdxInsideVd
  s3_vecout.trigger           := s3_in.uop.trigger
  s3_vecout.vstart            := s3_in.uop.vpu.vstart
  s3_vecout.vecTriggerMask    := s3_in.vecTriggerMask
  val s3_usSecondInv          = s3_in.usSecondInv

  val s3_frm_mis_flush     = s3_frm_mabuf &&
    (io.misalign_ldout.bits.rep_info.fwd_fail || io.misalign_ldout.bits.rep_info.mem_amb || io.misalign_ldout.bits.rep_info.nuke
      || io.misalign_ldout.bits.rep_info.rar_nack || io.misalign_ldout.bits.rep_info.raw_nack)

  io.rollback.valid := s3_valid && (s3_rep_frm_fetch || s3_flushPipe || s3_frm_mis_flush) && !s3_exception
  io.rollback.bits             := DontCare
  io.rollback.bits.isRVC       := s3_out.bits.uop.preDecodeInfo.isRVC
  io.rollback.bits.robIdx      := s3_out.bits.uop.robIdx
  io.rollback.bits.ftqIdx      := s3_out.bits.uop.ftqPtr
  io.rollback.bits.ftqOffset   := s3_out.bits.uop.ftqOffset
  io.rollback.bits.level       := Mux(s3_rep_frm_fetch || s3_frm_mis_flush, RedirectLevel.flush, RedirectLevel.flushAfter)
  io.rollback.bits.cfiUpdate.target := s3_out.bits.uop.pc
  io.rollback.bits.debug_runahead_checkpoint_id := s3_out.bits.uop.debugInfo.runahead_checkpoint_id
  /* <------- DANGEROUS: Don't change sequence here ! -------> */

  io.lsq.ldin.bits.uop := s3_out.bits.uop
//  io.lsq.ldin.bits.uop.exceptionVec(loadAddrMisaligned) := Mux(s3_in.onlyMisalignException, false.B, s3_in.uop.exceptionVec(loadAddrMisaligned))

  val s3_revoke = s3_exception || io.lsq.ldin.bits.rep_info.need_rep || s3_mis_align || (s3_frm_mabuf && io.misalign_ldout.bits.rep_info.need_rep)
  io.lsq.ldld_nuke_query.revoke := s3_revoke
  io.lsq.stld_nuke_query.revoke := s3_revoke

  // feedback slow
  s3_fast_rep := RegNext(s2_fast_rep)

  val s3_fb_no_waiting = !s3_in.isLoadReplay &&
                        (!(s3_fast_rep && !s3_fast_rep_canceled)) &&
                        !s3_in.feedbacked

  // feedback: scalar load will send feedback to RS
  //           vector load will send signal to VL Merge Buffer, then send feedback at granularity of uops
  io.feedback_slow.valid                 := s3_valid && s3_fb_no_waiting && !s3_isvec && !s3_frm_mabuf
  io.feedback_slow.bits.hit              := !s3_lrq_rep_info.need_rep || io.lsq.ldin.ready
  io.feedback_slow.bits.flushState       := s3_in.ptwBack
  io.feedback_slow.bits.robIdx           := s3_in.uop.robIdx
  io.feedback_slow.bits.sqIdx            := s3_in.uop.sqIdx
  io.feedback_slow.bits.lqIdx            := s3_in.uop.lqIdx
  io.feedback_slow.bits.sourceType       := RSFeedbackType.lrqFull
  io.feedback_slow.bits.dataInvalidSqIdx := DontCare

  // TODO: vector wakeup?
  io.ldCancel.ld2Cancel := s3_valid && !s3_safe_wakeup && !s3_isvec

  val s3_ld_wb_meta = Mux(s3_valid, s3_out.bits, s3_mmio_req.bits)

  // data from load queue refill
  val s3_ld_raw_data_frm_mmio = RegNextN(io.lsq.ld_raw_data, 3)
  val s3_merged_data_frm_mmio = s3_ld_raw_data_frm_mmio.mergedData()
  val s3_picked_data_frm_mmio = LookupTree(s3_ld_raw_data_frm_mmio.addrOffset, List(
    "b000".U -> s3_merged_data_frm_mmio(63,  0),
    "b001".U -> s3_merged_data_frm_mmio(63,  8),
    "b010".U -> s3_merged_data_frm_mmio(63, 16),
    "b011".U -> s3_merged_data_frm_mmio(63, 24),
    "b100".U -> s3_merged_data_frm_mmio(63, 32),
    "b101".U -> s3_merged_data_frm_mmio(63, 40),
    "b110".U -> s3_merged_data_frm_mmio(63, 48),
    "b111".U -> s3_merged_data_frm_mmio(63, 56)
  ))
  val s3_ld_data_frm_mmio = rdataHelper(s3_ld_raw_data_frm_mmio.uop, s3_picked_data_frm_mmio)

  /* data from pipe, which forward from respectively
   *  dcache hit: [D channel, mshr, sbuffer, sq]
   *  nc_with_data: [sq]
   */

  val s2_ld_data_frm_nc = shiftDataToHigh(s2_out.paddr, s2_out.data)
  val s2_ld_raw_data_frm_pipe = Wire(new LoadDataFromDcacheBundle)
  s2_ld_raw_data_frm_pipe.respDcacheData       := Mux(s2_nc_with_data, s2_ld_data_frm_nc, io.dcache.resp.bits.data)
  s2_ld_raw_data_frm_pipe.forward_D            := s2_fwd_frm_d_chan && !s2_nc_with_data
  s2_ld_raw_data_frm_pipe.forwardData_D        := s2_fwd_data_frm_d_chan
  s2_ld_raw_data_frm_pipe.forward_mshr         := s2_fwd_frm_mshr && !s2_nc_with_data
  s2_ld_raw_data_frm_pipe.forwardData_mshr     := s2_fwd_data_frm_mshr
  s2_ld_raw_data_frm_pipe.forward_result_valid := s2_fwd_data_valid

  s2_ld_raw_data_frm_pipe.forwardMask          := s2_fwd_mask
  s2_ld_raw_data_frm_pipe.forwardData          := s2_fwd_data
  s2_ld_raw_data_frm_pipe.uop                  := s2_out.uop
  s2_ld_raw_data_frm_pipe.addrOffset           := s2_out.paddr(3, 0)

  val s2_ld_raw_data_frm_tlD = s2_ld_raw_data_frm_pipe.mergeTLData()
  val s2_merged_data_frm_pipe = s2_ld_raw_data_frm_pipe.mergeLsqFwdData(s2_ld_raw_data_frm_tlD)
  val s3_merged_data_frm_pipe = RegEnable(s2_merged_data_frm_pipe, s2_fire)

  // duplicate reg for ldout and vecldout
  private val LdDataDup = 3
  require(LdDataDup >= 2)

  val s3_data_frm_pipe = VecInit((0 until LdDataDup).map(i => {
    VecInit(Seq(
      s3_merged_data_frm_pipe(63,      0),
      s3_merged_data_frm_pipe(71,      8),
      s3_merged_data_frm_pipe(79,     16),
      s3_merged_data_frm_pipe(87,     24),
      s3_merged_data_frm_pipe(95,     32),
      s3_merged_data_frm_pipe(103,    40),
      s3_merged_data_frm_pipe(111,    48),
      s3_merged_data_frm_pipe(119,    56),
      s3_merged_data_frm_pipe(127,    64),
      s3_merged_data_frm_pipe(127,    72),
      s3_merged_data_frm_pipe(127,    80),
      s3_merged_data_frm_pipe(127,    88),
      s3_merged_data_frm_pipe(127,    96),
      s3_merged_data_frm_pipe(127,   104),
      s3_merged_data_frm_pipe(127,   112),
      s3_merged_data_frm_pipe(127,   120),
    ))
  }))
  val s3_picked_data_frm_pipe = VecInit((0 until LdDataDup).map(i => {
    Mux1H(s3_data_select_by_offset, s3_data_frm_pipe(i))
  }))
  val s3_ld_data_frm_pipe = VecInit((0 until LdDataDup).map(i => {
    newRdataHelper(s3_data_select, s3_picked_data_frm_pipe(i))
  }))

  // FIXME: add 1 cycle delay ?
  // io.lsq.uncache.ready := !s3_valid
  val s3_ldout_valid  = s3_mmio_req.valid ||
                        s3_out.valid && RegNext(!s2_out.isvec && !s2_out.isFrmMisAlignBuf)
  val s3_outexception = ExceptionNO.selectByFu(s3_out.bits.uop.exceptionVec, LduCfg).asUInt.orR && s3_vecActive
  io.ldout.valid       := s3_ldout_valid
  io.ldout.bits        := s3_ld_wb_meta
  io.ldout.bits.data   := Mux(s3_valid, s3_ld_data_frm_pipe(0), s3_ld_data_frm_mmio)
  io.ldout.bits.uop.rfWen := s3_rfWen
  io.ldout.bits.uop.fpWen := s3_fpWen
  io.ldout.bits.uop.pdest := s3_pdest
  io.ldout.bits.uop.exceptionVec := ExceptionNO.selectByFu(s3_ld_wb_meta.uop.exceptionVec, LduCfg)
  io.ldout.bits.isFromLoadUnit := true.B
  io.ldout.bits.uop.fuType := Mux(
                                  s3_valid && s3_isvec,
                                  FuType.vldu.U,
                                  FuType.ldu.U
  )

  XSError(s3_valid && s3_vecout.isvec && s3_in.vecActive && !s3_vecout.mask.orR, "In vecActive, mask complement should not be 0")
  // TODO: check this --hx
  // io.ldout.valid       := s3_out.valid && !s3_out.bits.uop.robIdx.needFlush(io.redirect) && !s3_vecout.isvec ||
  //   io.lsq.uncache.valid && !io.lsq.uncache.bits.uop.robIdx.needFlush(io.redirect) && !s3_out.valid && !io.lsq.uncache.bits.isVls
  //  io.ldout.bits.data   := Mux(s3_out.valid, s3_ld_data_frm_pipe, s3_ld_data_frm_mmio)
  //  io.ldout.valid       := s3_out.valid && !s3_out.bits.uop.robIdx.needFlush(io.redirect) ||
  //                         s3_mmio_req.valid && !s3_mmio_req.bits.uop.robIdx.needFlush(io.redirect) && !s3_out.valid

  // s3 load fast replay
  io.fast_rep_out.valid := s3_valid && s3_fast_rep
  io.fast_rep_out.bits := s3_in
  io.fast_rep_out.bits.lateKill := s3_rep_frm_fetch
  io.fast_rep_out.bits.delayedLoadError := s3_hw_err

  val vecFeedback = s3_valid && s3_fb_no_waiting && s3_lrq_rep_info.need_rep && !io.lsq.ldin.ready && s3_isvec

  // vector output
  io.vecldout.bits.alignedType := s3_vec_alignedType
  // vec feedback
  io.vecldout.bits.vecFeedback := vecFeedback
  // TODO: VLSU, uncache data logic
  val vecdata = rdataVecHelper(s3_vec_alignedType(1,0), s3_picked_data_frm_pipe(1))
  io.vecldout.bits.vecdata.get := Mux(
    s3_in.misalignWith16Byte,
    s3_picked_data_frm_pipe(1),
    Mux(
      s3_in.is128bit,
      s3_merged_data_frm_pipe,
      vecdata
    )
  )
  io.vecldout.bits.isvec := s3_vecout.isvec
  io.vecldout.bits.elemIdx := s3_vecout.elemIdx
  io.vecldout.bits.elemIdxInsideVd.get := s3_vecout.elemIdxInsideVd
  io.vecldout.bits.mask := s3_vecout.mask
  io.vecldout.bits.hasException := s3_exception
  io.vecldout.bits.reg_offset.get := s3_vecout.reg_offset
  io.vecldout.bits.usSecondInv := s3_usSecondInv
  io.vecldout.bits.mBIndex := s3_vec_mBIndex
  io.vecldout.bits.hit := !s3_lrq_rep_info.need_rep || io.lsq.ldin.ready
  io.vecldout.bits.sourceType := RSFeedbackType.lrqFull
  io.vecldout.bits.trigger := s3_vecout.trigger
  io.vecldout.bits.flushState := DontCare
  io.vecldout.bits.exceptionVec := ExceptionNO.selectByFu(s3_out.bits.uop.exceptionVec, VlduCfg)
  io.vecldout.bits.vaddr := s3_in.fullva
  io.vecldout.bits.vaNeedExt := s3_in.vaNeedExt
  io.vecldout.bits.gpaddr := s3_in.gpaddr
  io.vecldout.bits.isForVSnonLeafPTE := s3_in.isForVSnonLeafPTE
  io.vecldout.bits.mmio := DontCare
  io.vecldout.bits.vstart := s3_vecout.vstart
  io.vecldout.bits.vecTriggerMask := s3_vecout.vecTriggerMask
  io.vecldout.bits.nc := DontCare

  io.vecldout.valid := s3_out.valid && !s3_out.bits.uop.robIdx.needFlush(io.redirect) && s3_vecout.isvec && !s3_mis_align && !s3_frm_mabuf //||
  // TODO: check this, why !io.lsq.uncache.bits.isVls before?
  // Now vector instruction don't support mmio.
    // io.lsq.uncache.valid && !io.lsq.uncache.bits.uop.robIdx.needFlush(io.redirect) && !s3_out.valid && io.lsq.uncache.bits.isVls
    //io.lsq.uncache.valid && !io.lsq.uncache.bits.uop.robIdx.needFlush(io.redirect) && !s3_out.valid && !io.lsq.uncache.bits.isVls

  io.misalign_ldout.valid     := s3_valid && (!s3_fast_rep || s3_fast_rep_canceled) && s3_frm_mabuf || s3_misalign_wakeup_req.valid
  io.misalign_ldout.bits      := Mux(s3_misalign_wakeup_req.valid, s3_misalign_wakeup_req.bits, io.lsq.ldin.bits)
  io.misalign_ldout.bits.data := s3_picked_data_frm_pipe(2)
  io.misalign_ldout.bits.rep_info.cause := Mux(s3_misalign_wakeup_req.valid, 0.U.asTypeOf(s3_in.rep_info.cause), s3_misalign_rep_cause)

  // fast load to load forward
  if (EnableLoadToLoadForward) {
    io.l2l_fwd_out.valid      := s3_valid && !s3_in.mmio && !s3_in.nc && !s3_lrq_rep_info.need_rep
    io.l2l_fwd_out.data       := Mux(s3_in.vaddr(3), s3_merged_data_frm_pipe(127, 64), s3_merged_data_frm_pipe(63, 0))
    io.l2l_fwd_out.dly_ld_err := s3_hw_err || // ecc delayed error
                                 s3_ldld_rep_inst ||
                                 s3_rep_frm_fetch
  } else {
    io.l2l_fwd_out.valid := false.B
    io.l2l_fwd_out.data := DontCare
    io.l2l_fwd_out.dly_ld_err := DontCare
  }

  // s1
  io.debug_ls.s1_robIdx := s1_in.uop.robIdx.value
  io.debug_ls.s1_isLoadToLoadForward := s1_fire && s1_try_ptr_chasing && !s1_ptr_chasing_canceled
  io.debug_ls.s1_isTlbFirstMiss := s1_fire && s1_tlb_miss && s1_in.isFirstIssue
  // s2
  io.debug_ls.s2_robIdx := s2_in.uop.robIdx.value
  io.debug_ls.s2_isBankConflict := s2_fire && (!s2_kill && s2_bank_conflict)
  io.debug_ls.s2_isDcacheFirstMiss := s2_fire && io.dcache.resp.bits.miss && s2_in.isFirstIssue
  io.debug_ls.s2_isForwardFail := s2_fire && s2_fwd_fail
  // s3
  io.debug_ls.s3_robIdx := s3_in.uop.robIdx.value
  io.debug_ls.s3_isReplayFast := s3_valid && s3_fast_rep && !s3_fast_rep_canceled
  io.debug_ls.s3_isReplayRS :=  RegNext(io.feedback_fast.valid && !io.feedback_fast.bits.hit) || (io.feedback_slow.valid && !io.feedback_slow.bits.hit)
  io.debug_ls.s3_isReplaySlow := io.lsq.ldin.valid && io.lsq.ldin.bits.rep_info.need_rep
  io.debug_ls.s3_isReplay := s3_valid && s3_lrq_rep_info.need_rep // include fast+slow+rs replay
  io.debug_ls.replayCause := s3_lrq_rep_info.cause
  io.debug_ls.replayCnt := 1.U

  // Topdown
  io.lsTopdownInfo.s1.robIdx          := s1_in.uop.robIdx.value
  io.lsTopdownInfo.s1.vaddr_valid     := s1_valid && s1_in.hasROBEntry
  io.lsTopdownInfo.s1.vaddr_bits      := s1_vaddr
  io.lsTopdownInfo.s2.robIdx          := s2_in.uop.robIdx.value
  io.lsTopdownInfo.s2.paddr_valid     := s2_fire && s2_in.hasROBEntry && !s2_in.tlbMiss
  io.lsTopdownInfo.s2.paddr_bits      := s2_in.paddr
  io.lsTopdownInfo.s2.first_real_miss := io.dcache.resp.bits.real_miss
  io.lsTopdownInfo.s2.cache_miss_en   := s2_fire && s2_in.hasROBEntry && !s2_in.tlbMiss && !s2_in.missDbUpdated

  // perf cnt
  XSPerfAccumulate("s0_in_valid",                  io.ldin.valid)
  XSPerfAccumulate("s0_in_block",                  io.ldin.valid && !io.ldin.fire)
  XSPerfAccumulate("s0_vecin_valid",               io.vecldin.valid)
  XSPerfAccumulate("s0_vecin_block",               io.vecldin.valid && !io.vecldin.fire)
  XSPerfAccumulate("s0_in_fire_first_issue",       s0_valid && s0_sel_src.isFirstIssue)
  XSPerfAccumulate("s0_lsq_replay_issue",          io.replay.fire)
  XSPerfAccumulate("s0_lsq_replay_vecissue",       io.replay.fire && io.replay.bits.isvec)
  XSPerfAccumulate("s0_ldu_fire_first_issue",      io.ldin.fire && s0_sel_src.isFirstIssue)
  XSPerfAccumulate("s0_fast_replay_issue",         io.fast_rep_in.fire)
  XSPerfAccumulate("s0_fast_replay_vecissue",      io.fast_rep_in.fire && io.fast_rep_in.bits.isvec)
  XSPerfAccumulate("s0_stall_out",                 s0_valid && !s0_can_go)
  XSPerfAccumulate("s0_stall_dcache",              s0_valid && !io.dcache.req.ready)
  XSPerfAccumulate("s0_addr_spec_success",         s0_fire && s0_dcache_vaddr(VAddrBits-1, 12) === io.ldin.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("s0_addr_spec_failed",          s0_fire && s0_dcache_vaddr(VAddrBits-1, 12) =/= io.ldin.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("s0_addr_spec_success_once",    s0_fire && s0_dcache_vaddr(VAddrBits-1, 12) === io.ldin.bits.src(0)(VAddrBits-1, 12) && s0_sel_src.isFirstIssue)
  XSPerfAccumulate("s0_addr_spec_failed_once",     s0_fire && s0_dcache_vaddr(VAddrBits-1, 12) =/= io.ldin.bits.src(0)(VAddrBits-1, 12) && s0_sel_src.isFirstIssue)
  XSPerfAccumulate("s0_vec_addr_vlen_aligned",     s0_fire && s0_sel_src.isvec && s0_dcache_vaddr(3, 0) === 0.U)
  XSPerfAccumulate("s0_vec_addr_vlen_unaligned",   s0_fire && s0_sel_src.isvec && s0_dcache_vaddr(3, 0) =/= 0.U)
  XSPerfAccumulate("s0_forward_tl_d_channel",      s0_out.forward_tlDchannel)
  XSPerfAccumulate("s0_hardware_prefetch_fire",    s0_fire && s0_hw_prf_select)
  XSPerfAccumulate("s0_software_prefetch_fire",    s0_fire && s0_sel_src.prf && s0_src_select_vec(int_iss_idx))
  XSPerfAccumulate("s0_hardware_prefetch_blocked", io.prefetch_req.valid && !s0_hw_prf_select)
  XSPerfAccumulate("s0_hardware_prefetch_total",   io.prefetch_req.valid)

  XSPerfAccumulate("s3_rollback_total",             io.rollback.valid)
  XSPerfAccumulate("s3_rep_frm_fetch_rollback",     io.rollback.valid && s3_rep_frm_fetch)
  XSPerfAccumulate("s3_flushPipe_rollback",         io.rollback.valid && s3_flushPipe)
  XSPerfAccumulate("s3_frm_mis_flush_rollback",     io.rollback.valid && s3_frm_mis_flush)

  XSPerfAccumulate("s1_in_valid",                  s1_valid)
  XSPerfAccumulate("s1_in_fire",                   s1_fire)
  XSPerfAccumulate("s1_in_fire_first_issue",       s1_fire && s1_in.isFirstIssue)
  XSPerfAccumulate("s1_tlb_miss",                  s1_fire && s1_tlb_miss)
  XSPerfAccumulate("s1_tlb_miss_first_issue",      s1_fire && s1_tlb_miss && s1_in.isFirstIssue)
  XSPerfAccumulate("s1_stall_out",                 s1_valid && !s1_can_go)
  XSPerfAccumulate("s1_dly_err",                   s1_valid && s1_fast_rep_dly_err)

  XSPerfAccumulate("s2_in_valid",                  s2_valid)
  XSPerfAccumulate("s2_in_fire",                   s2_fire)
  XSPerfAccumulate("s2_in_fire_first_issue",       s2_fire && s2_in.isFirstIssue)
  XSPerfAccumulate("s2_dcache_miss",               s2_fire && io.dcache.resp.bits.miss)
  XSPerfAccumulate("s2_dcache_miss_first_issue",   s2_fire && io.dcache.resp.bits.miss && s2_in.isFirstIssue)
  XSPerfAccumulate("s2_dcache_real_miss_first_issue",   s2_fire && io.dcache.resp.bits.miss && s2_in.isFirstIssue)
  XSPerfAccumulate("s2_full_forward",              s2_fire && s2_full_fwd)
  XSPerfAccumulate("s2_dcache_miss_full_forward",  s2_fire && s2_dcache_miss)
  XSPerfAccumulate("s2_fwd_frm_d_can",             s2_valid && s2_fwd_frm_d_chan)
  XSPerfAccumulate("s2_fwd_frm_d_chan_or_mshr",    s2_valid && s2_fwd_frm_d_chan_or_mshr)
  XSPerfAccumulate("s2_stall_out",                 s2_fire && !s2_can_go)
  XSPerfAccumulate("s2_prefetch",                  s2_fire && s2_prf)
  XSPerfAccumulate("s2_prefetch_ignored",          s2_fire && s2_prf && io.dcache.s2_mq_nack) // ignore prefetch for mshr full / miss req port conflict
  XSPerfAccumulate("s2_prefetch_miss",             s2_fire && s2_prf && io.dcache.resp.bits.miss) // prefetch req miss in l1
  XSPerfAccumulate("s2_prefetch_hit",              s2_fire && s2_prf && !io.dcache.resp.bits.miss) // prefetch req hit in l1
  XSPerfAccumulate("s2_prefetch_accept",           s2_fire && s2_prf && io.dcache.resp.bits.miss && !io.dcache.s2_mq_nack) // prefetch a missed line in l1, and l1 accepted it
  XSPerfAccumulate("s2_forward_req",               s2_fire && s2_in.forward_tlDchannel)
  XSPerfAccumulate("s2_successfully_forward_channel_D", s2_fire && s2_fwd_frm_d_chan && s2_fwd_data_valid)
  XSPerfAccumulate("s2_successfully_forward_mshr",      s2_fire && s2_fwd_frm_mshr && s2_fwd_data_valid)

  XSPerfAccumulate("load_to_load_forward",                      s1_try_ptr_chasing && !s1_ptr_chasing_canceled)
  XSPerfAccumulate("load_to_load_forward_try",                  s1_try_ptr_chasing)
  XSPerfAccumulate("load_to_load_forward_fail",                 s1_cancel_ptr_chasing)
  XSPerfAccumulate("load_to_load_forward_fail_cancelled",       s1_cancel_ptr_chasing && s1_ptr_chasing_canceled)
  XSPerfAccumulate("load_to_load_forward_fail_wakeup_mismatch", s1_cancel_ptr_chasing && !s1_ptr_chasing_canceled && s1_not_fast_match)
  XSPerfAccumulate("load_to_load_forward_fail_op_not_ld",       s1_cancel_ptr_chasing && !s1_ptr_chasing_canceled && !s1_not_fast_match && s1_fu_op_type_not_ld)
  XSPerfAccumulate("load_to_load_forward_fail_addr_align",      s1_cancel_ptr_chasing && !s1_ptr_chasing_canceled && !s1_not_fast_match && !s1_fu_op_type_not_ld && s1_addr_misaligned)
  XSPerfAccumulate("load_to_load_forward_fail_set_mismatch",    s1_cancel_ptr_chasing && !s1_ptr_chasing_canceled && !s1_not_fast_match && !s1_fu_op_type_not_ld && !s1_addr_misaligned && s1_addr_mismatch)

  XSPerfAccumulate("nc_ld_writeback", io.ldout.valid && s3_nc_with_data)
  XSPerfAccumulate("nc_ld_exception", s3_valid && s3_nc_with_data && s3_in.uop.exceptionVec.reduce(_ || _))
  XSPerfAccumulate("nc_ldld_vio", s3_valid && s3_nc_with_data && s3_ldld_rep_inst)
  XSPerfAccumulate("nc_stld_vio", s3_valid && s3_nc_with_data && s3_in.rep_info.nuke)
  XSPerfAccumulate("nc_ldld_vioNack", s3_valid && s3_nc_with_data && s3_in.rep_info.rar_nack)
  XSPerfAccumulate("nc_stld_vioNack", s3_valid && s3_nc_with_data && s3_in.rep_info.raw_nack)
  XSPerfAccumulate("nc_stld_fwd", s3_valid && s3_nc_with_data && RegNext(s2_full_fwd))
  XSPerfAccumulate("nc_stld_fwdNotReady", s3_valid && s3_nc_with_data && RegNext(s2_mem_amb || s2_fwd_fail))
  XSPerfAccumulate("nc_stld_fwdAddrMismatch", s3_valid && s3_nc_with_data && s3_vp_match_fail)

  // bug lyq: some signals in perfEvents are no longer suitable for the current MemBlock design
  // hardware performance counter
  val perfEvents = Seq(
    ("load_s0_in_fire         ", s0_fire                                                        ),
    ("load_to_load_forward    ", s1_fire && s1_try_ptr_chasing && !s1_ptr_chasing_canceled      ),
    ("stall_dcache            ", s0_valid && s0_can_go && !io.dcache.req.ready                  ),
    ("load_s1_in_fire         ", s0_fire                                                        ),
    ("load_s1_tlb_miss        ", s1_fire && io.tlb.resp.bits.miss                               ),
    ("load_s2_in_fire         ", s1_fire                                                        ),
    ("load_s2_dcache_miss     ", s2_fire && io.dcache.resp.bits.miss                            ),
  )
  generatePerfEvent()

  if (backendParams.debugEn){
    dontTouch(s0_src_valid_vec)
    dontTouch(s0_src_ready_vec)
    dontTouch(s0_src_select_vec)
    dontTouch(s3_ld_data_frm_pipe)
    s3_data_select_by_offset.map(x=> dontTouch(x))
    s3_data_frm_pipe.map(x=> dontTouch(x))
    s3_picked_data_frm_pipe.map(x=> dontTouch(x))
  }

  XSDebug(io.ldout.fire, "ldout %x\n", io.ldout.bits.uop.pc)
  // end
}

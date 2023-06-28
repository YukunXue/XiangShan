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

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import utility._
import xiangshan.ExceptionNO._
import xiangshan._
import xiangshan.backend.fu.PMPRespBundle
import xiangshan.backend.rob.DebugLsInfoBundle

class StoreUnit(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val redirect = Flipped(ValidIO(new Redirect))

    val stin = Flipped(Decoupled(new ExuInput))
    val issue = Valid(new ExuInput)
    val tlb = new TlbRequestIO()
    val pmp = Flipped(new PMPRespBundle())
    val rsIdx = Input(UInt(log2Up(IssQueSize).W))
    val isFirstIssue = Input(Bool())
    val lsq = ValidIO(new LsPipelineBundle)
    val lsq_replenish = Output(new LsPipelineBundle())
    val feedbackSlow = ValidIO(new RSFeedback)
    val reExecuteQuery = Valid(new LoadReExecuteQueryIO)
    val stout = DecoupledIO(new ExuOutput) // writeback store

    // store mask, send to sq in store_s0
    val storeMaskOut = Valid(new StoreMaskBundle)
    val debug_ls = Output(new DebugLsInfoBundle)
  })


  val s1_ready, s2_ready, s3_ready = WireInit(false.B)

  // s0: generate addr, use addr to query DCache and DTLB
  val s0_valid = io.stin.valid
  val s0_in = io.stin.bits
  val s0_isFirstIssue = io.isFirstIssue
  val s0_rsIdx = io.rsIdx
  val s0_out = Wire(new LsPipelineBundle)
  val s0_can_go = s1_ready
  val s0_fire = s0_valid && s0_can_go

  // generate addr
  // val saddr = s0_in.bits.src(0) + SignExt(s0_in.bits.uop.ctrl.imm(11,0), VAddrBits)
  val imm12 = WireInit(s0_in.uop.ctrl.imm(11,0))
  val saddr_lo = s0_in.src(0)(11,0) + Cat(0.U(1.W), imm12)
  val saddr_hi = Mux(saddr_lo(12),
    Mux(imm12(11), s0_in.src(0)(VAddrBits-1, 12), s0_in.src(0)(VAddrBits-1, 12)+1.U),
    Mux(imm12(11), s0_in.src(0)(VAddrBits-1, 12)+SignExt(1.U, VAddrBits-12), s0_in.src(0)(VAddrBits-1, 12)),
  )
  val s0_saddr = Cat(saddr_hi, saddr_lo(11,0))

  io.tlb.req.valid := s0_valid
  io.tlb.req.bits.vaddr := s0_saddr
  io.tlb.req.bits.cmd := TlbCmd.write
  io.tlb.req.bits.size := LSUOpType.size(s0_in.uop.ctrl.fuOpType)
  io.tlb.req.bits.kill := DontCare
  io.tlb.req.bits.memidx.is_ld := false.B
  io.tlb.req.bits.memidx.is_st := true.B
  io.tlb.req.bits.memidx.idx := s0_in.uop.sqIdx.value
  io.tlb.req.bits.debug.robIdx := s0_in.uop.robIdx
  io.tlb.req.bits.no_translate := false.B
  io.tlb.req.bits.debug.pc := s0_in.uop.cf.pc
  io.tlb.req.bits.debug.isFirstIssue := s0_isFirstIssue
  io.tlb.req_kill := false.B

  s0_out.valid := s0_valid
  s0_out := DontCare
  s0_out.vaddr := s0_saddr 
  // Now data use its own io
  // s1_out.data := genWdata(s1_in.src(1), s1_in.uop.ctrl.fuOpType(1,0))
  s0_out.data := s0_in.src(1) // FIXME: remove data from pipeline
  s0_out.uop := s0_in.uop
  s0_out.miss := DontCare
  s0_out.rsIdx := s0_rsIdx
  s0_out.mask := genWmask(s0_saddr, s0_in.uop.ctrl.fuOpType(1,0))
  s0_out.isFirstIssue := s0_isFirstIssue
  s0_out.wlineflag := s0_in.uop.ctrl.fuOpType === LSUOpType.cbo_zero
  when(s0_valid && s0_isFirstIssue) {
    s0_out.uop.debugInfo.tlbFirstReqTime := GTimer()
  }  

  // exception check
  val s0_addrAligned = LookupTree(s0_in.uop.ctrl.fuOpType(1,0), List(
    "b00".U   -> true.B,              //b
    "b01".U   -> (s0_out.vaddr(0) === 0.U),   //h
    "b10".U   -> (s0_out.vaddr(1,0) === 0.U), //w
    "b11".U   -> (s0_out.vaddr(2,0) === 0.U)  //d
  ))
  s0_out.uop.cf.exceptionVec(storeAddrMisaligned) := !s0_addrAligned

  io.storeMaskOut.valid := s0_valid
  io.storeMaskOut.bits.mask := s0_out.mask 
  io.storeMaskOut.bits.sqIdx := s0_out.uop.sqIdx 


  // s1: TLB resp (send paddr to dcache)
  val s1_valid = RegInit(false.B)
  val s1_in = RegEnable(s0_out, s0_fire)
  val s1_out = Wire(new LsPipelineBundle)
  val s1_can_go = s2_ready
  val s1_fire = s1_valid && s1_can_go

  // mmio cbo decoder
  val s1_mmio_cbo = s1_in.uop.ctrl.fuOpType === LSUOpType.cbo_clean ||
                    s1_in.uop.ctrl.fuOpType === LSUOpType.cbo_flush ||
                    s1_in.uop.ctrl.fuOpType === LSUOpType.cbo_inval
  val s1_paddr = io.tlb.resp.bits.paddr(0)
  val s1_tlb_miss = io.tlb.resp.bits.miss
  val s1_mmio = s1_mmio_cbo
  val s1_exception = ExceptionNO.selectByFu(s1_out.uop.cf.exceptionVec, staCfg).asUInt.orR

  s1_ready = true.B
  io.tlb.resp.ready := true.B // TODO: why dtlbResp needs a ready?

  when (s0_fire) {
    s1_valid := true.B && !s0_out.uop.robIdx.needFlush(io.redirect)
  } .elsewhen (s1_fire) {
    s1_valid := false.B
  }

  // st-ld violation dectect request.
  io.reExecuteQuery.valid := s1_valid && !s1_tlb_miss
  io.reExecuteQuery.bits.robIdx := s1_in.uop.robIdx
  io.reExecuteQuery.bits.paddr := s1_paddr
  io.reExecuteQuery.bits.mask := s1_in.mask

  // Send TLB feedback to store issue queue
  // Store feedback is generated in store_s1, sent to RS in store_s2
  io.rsFeedback.valid := s1_valid
  io.rsFeedback.bits.hit := !s1_tlb_miss
  io.rsFeedback.bits.flushState := io.tlb.resp.bits.ptwBack
  io.rsFeedback.bits.rsIdx := s1_in.rsIdx
  io.rsFeedback.bits.sourceType := RSFeedbackType.tlbMiss
  XSDebug(io.rsFeedback.valid,
    "S1 Store: tlbHit: %d robIdx: %d\n",
    io.rsFeedback.bits.hit,
    io.rsFeedback.bits.rsIdx
  )
  io.rsFeedback.bits.dataInvalidSqIdx := DontCare

  // issue
  io.issue.valid := s1_valid && !s1_tlb_miss
  io.issue.bits := RegEnable(s0_in, s0_valid)

  // get paddr from dtlb, check if rollback is needed
  // writeback store inst to lsq
  s1_out := s1_in
  s1_out.bits.paddr := s1_paddr
  s1_out.bits.miss := false.B
  s1_out.bits.mmio := s1_mmio
  s1_out.bits.atomic := s1_mmio
  s1_out.bits.uop.cf.exceptionVec(storePageFault) := io.tlb.resp.bits.excp(0).pf.st
  s1_out.bits.uop.cf.exceptionVec(storeAccessFault) := io.tlb.resp.bits.excp(0).af.st

  io.lsq.valid := s1_valid
  io.lsq.bits := s1_out
  io.lsq.bits.miss := s1_tlb_miss

  // write below io.out.bits assign sentence to prevent overwriting values
  val s1_tlb_memidx = io.tlb.resp.bits.memidx
  when(s1_tlb_memidx.is_st && io.dtlbResp.valid && !s1_tlb_miss && s1_tlb_memidx.idx === s1_out.uop.sqIdx.value) {
    // printf("Store idx = %d\n", s1_tlb_memidx.idx)
    s1_out.uop.debugInfo.tlbRespTime := GTimer()
  }

  // s2: mmio check
  val s2_valid = RegInit(false.B)
  val s2_in = RegEnable(s1_out, s1_fire)
  val s2_out = Wire(new LsPipelineBundle)
  val s2_can_go = s3_ready
  val s2_fire = s2_valid && s2_can_go

  s2_ready := s3_ready
  when (s1_fire) {
    s2_valid := true.B && !s1_out.uop.robIdx.needFlush(io.redirect)
  } .elsewhen (s2_fire) {
    s2_valid := false.B
  }

  val s2_pmp = WireInit(io.pmp)
  val s2_static_pm = RegNext(io.tlb.resp.bits.static_pm)
  when (s2_static_pm.valid) {
    s2_pmp.ld := false.B
    s2_pmp.st := false.B
    s2_pmp.instr := false.B
    s2_pmp.mmio := s2_static_pm.bits
  }

  val s2_exception = ExceptionNO.selectByFu(s1_in.uop.cf.exceptionVec, staCfg).asUInt.orR
  val s2_mmio = s2_in.mmio || s2_pmp.mmio

  s2_out := s2_in
  s2_out.mmio := s2_mmio && s2_exception
  s2_out.atomic := s2_in.bits.atomic || s2_pmp.atomic
  s2_out.uop.cf.exceptionVec(storeAccessFault) := s2_in.uop.cf.exceptionVec(storeAccessFault) || s2_pmp.st

  // feedback tlb miss to RS in store_s2
  io.feedbackSlow.valid := RegNext(io.rsFeedback.valid && !s1_out.uop.robIdx.needFlush(io.redirect))
  io.feedbackSlow.bits := RegNext(io.rsFeedback.bits)

  // mmio and exception
  io.lsq_replenish := s2_out

  // s3: store write back
  val s3_valid = RegInit(false.B)
  val s3_in = RegEnable(s2_out, s2_fire)
  val s3_out = Wire(new LsPipelineBundle)
  val s3_can_go = s3_ready
  val s3_fire = s3_valid && s3_can_go

  s3_ready := io.stout.ready
  when (s2_fire) {
    s3_valid := (!s2_mmio || s2_exception) && !uop.robIdx.needFlush(io.redirect)
  } .elsewhen (s3_fire) {
    s3_valid := false.B
  }

  // wb: writeback
  val SelectGroupSize = RollbackGroupSize
  val lgSelectGroupSize = log2Ceil(SelectGroupSize)
  val TotalSelectCycles = scala.math.ceil(log2Ceil(LoadQueueRAWSize).toFloat / lgSelectGroupSize).toInt + 1

  val stout = Wire(new ExuOutput)
  stout := DontCare
  stout.uop := s3_in.uop
  stout.data := DontCare
  stout.redirectValid := false.B
  stout.redirect := DontCare
  stout.debug.isMMIO := s3_in.mmio
  stout.debug.paddr := s3_in.paddr
  stout.debug.vaddr := s3_in.vaddr
  stout.debug.isPerfCnt := false.B
  stout.fflags := DontCare

  // delay TotalSelectCycles - 2 cycle(s)
  var valid = s3_valid
  var bits = stout
  for (i <- 0 until TotalSelectCycles - 2) {
    valid = RegNext(valid && !bits.uop.robIdx.needFlush(io.redirect))
    bits = RegNext(bits)
  }
  io.stout.valid := valid && !bits.uop.robIdx.needFlush(io.redirect)
  io.stout.bits := bits

  io.debug_ls := DontCare
  io.debug_ls.s1.isTlbFirstMiss := io.tlb.resp.valid && io.tlb.resp.bits.miss && io.tlb.resp.bits.debug.isFirstIssue
  io.debug_ls.s1_robIdx := s1_in.bits.uop.robIdx.value

  private def printPipeLine(pipeline: LsPipelineBundle, cond: Bool, name: String): Unit = {
    XSDebug(cond,
      p"$name" + p" pc ${Hexadecimal(pipeline.uop.cf.pc)} " +
        p"addr ${Hexadecimal(pipeline.vaddr)} -> ${Hexadecimal(pipeline.paddr)} " +
        p"op ${Binary(pipeline.uop.ctrl.fuOpType)} " +
        p"data ${Hexadecimal(pipeline.data)} " +
        p"mask ${Hexadecimal(pipeline.mask)}\n"
    )
  }

  printPipeLine(s0_out, s0_valid, "S0")
  printPipeLine(s1_out, s1_valid, "S1")

  // perf cnt
  XSPerfAccumulate("in_valid",                s0_valid)
  XSPerfAccumulate("in_fire",                 s0_fire)
  XSPerfAccumulate("in_fire_first_issue",     s0_fire && s0_isFirstIssue)
  XSPerfAccumulate("addr_spec_success",       s0_fire && s0_saddr(VAddrBits-1, 12) === s0_in.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_failed",        s0_fire && s0_saddr(VAddrBits-1, 12) =/= s0_in.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_success_once",  s0_fire && s0_saddr(VAddrBits-1, 12) === s0_in.src(0)(VAddrBits-1, 12) && s0_isFirstIssue)
  XSPerfAccumulate("addr_spec_failed_once",   s0_fire && s0_saddr(VAddrBits-1, 12) =/= s0_in.src(0)(VAddrBits-1, 12) && s0_isFirstIssue) 

  XSPerfAccumulate("in_valid",                s1_valid)
  XSPerfAccumulate("in_fire",                 s1_fire)
  XSPerfAccumulate("in_fire_first_issue",     s1_fire && s1_in.isFirstIssue)
  XSPerfAccumulate("tlb_miss",                s1_fire && s1_tlb_miss)
  XSPerfAccumulate("tlb_miss_first_issue",    s1_fire && s1_tlb_miss && s1_in.isFirstIssue)
  // end
}
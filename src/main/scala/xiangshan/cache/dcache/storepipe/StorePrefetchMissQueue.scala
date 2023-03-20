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

package xiangshan.cache

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils.{HasPerfEvents, XSDebug, XSPerfAccumulate}

class StorePrefetchMissQueue(implicit p: Parameters) extends DCacheModule with HasPerfEvents{
    val io = IO(new DCacheBundle {
        // enq0: odd
        // enq1: even
        val enq = Vec(StorePipelineWidth, Flipped(DecoupledIO(new MissReq)))
        val mshr_release_info = Flipped(Valid(new MissEntryReleaseInfo))
        val deq = DecoupledIO(new MissReq)
    })
    require(StorePipelineWidth == 2)
    val QueueSize = 16
    val valids = RegInit(VecInit(List.tabulate(QueueSize){_ => false.B}))
    val datas = RegInit(VecInit(List.tabulate(QueueSize){_ => 0.U.asTypeOf(new MissReq)}))
    val cancel_mask = Wire(Vec(QueueSize, Bool()))

    def get_enq_mask(odd: Boolean): Vec[Bool] = {
        WireInit(VecInit(List.tabulate(QueueSize){i => ((i % 2) == (if (odd) 1 else 0)).B}))
    }
    def same_cache_line_addr(paddr0: UInt, paddr1: UInt): Bool = {
        require(paddr0.getWidth == PAddrBits)
        get_block_addr(paddr0) === get_block_addr(paddr1)
    }
    def filter_by_cache_line_addr(valid_vec: Vec[Bool], data_vec: Vec[MissReq], incoming_paddr: UInt) : Bool = {
        val match_vec = (valid_vec zip (data_vec.map(_.addr))).map{
            case(v, e_paddr) => v && same_cache_line_addr(e_paddr, incoming_paddr)
        }
        VecInit(match_vec).asUInt.orR
    }
    // enq
    for (i <- 0 until StorePipelineWidth) {
        val odd = i == 0
        val enq_mask = get_enq_mask(odd).asUInt
        val enq_valids = (~(valids.asUInt) & enq_mask)
        val full = !(enq_valids.orR)
        val enq_idx = PriorityEncoder(enq_valids)

        val enq_req = io.enq(i)
        val enq_cancel = io.mshr_release_info.valid && same_cache_line_addr(io.mshr_release_info.bits.paddr, enq_req.bits.addr)
        val enq_filter = filter_by_cache_line_addr(valids, datas, enq_req.bits.addr)
        enq_req.ready := !full && !enq_cancel && !enq_filter

        when(enq_req.fire) {
            valids(enq_idx) := true.B
            datas(enq_idx) := enq_req.bits
        }
    }

    // deq
    val deq_valids = (valids zip cancel_mask).map{case (v, cancel) => v && !cancel}
    val deq_decoupled = Wire(Vec(QueueSize, Decoupled(new MissReq)))

    (deq_valids zip deq_decoupled zip datas zip valids).foreach{case (((deq_valid, out_decouple), data), v) => {
        out_decouple.valid := deq_valid
        out_decouple.bits := data
        when(out_decouple.fire) {
            v := false.B
        }
    }}
    rrArbiter(deq_decoupled, io.deq)

    // cancel
    val cancel_vec = (valids zip datas).map{case (v, data) => {
        v && io.mshr_release_info.valid && same_cache_line_addr(io.mshr_release_info.bits.paddr, data.addr)
    }}
    cancel_mask := cancel_vec
    (valids zip cancel_vec).foreach{case (v, cancel) => {
        when(cancel) {
            v := false.B
        }
    }}

    XSPerfAccumulate("deq_fire", io.deq.fire)
    (0 until StorePipelineWidth).map{case i => XSPerfAccumulate(s"enq${i}_fire", io.enq(i).fire)}
    XSPerfAccumulate("mshr_release", io.mshr_release_info.valid)

    val perfValidCount = RegNext(PopCount(valids))
    val perfEvents = Seq(
        ("queue_1_4_valid", (perfValidCount < (QueueSize.U/4.U))),
        ("queue_2_4_valid", (perfValidCount > (QueueSize.U/4.U)) & (perfValidCount <= (QueueSize.U/2.U))),
        ("queue_3_4_valid", (perfValidCount > (QueueSize.U/2.U)) & (perfValidCount <= (QueueSize.U*3.U/4.U))),
        ("queue_4_4_valid", (perfValidCount > (QueueSize.U*3.U/4.U)))
    )
    generatePerfEvent()

}


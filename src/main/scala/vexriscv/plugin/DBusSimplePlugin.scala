package vexriscv.plugin

import vexriscv._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.avalon.{AvalonMMConfig, AvalonMM}


case class DBusSimpleCmd() extends Bundle{
  val wr = Bool
  val address = UInt(32 bits)
  val data = Bits(32 bit)
  val size = UInt(2 bit)
}

case class DBusSimpleRsp() extends Bundle with IMasterSlave{
  val ready = Bool
  val error = Bool
  val data = Bits(32 bit)

  override def asMaster(): Unit = {
    out(ready,error,data)
  }
}


object DBusSimpleBus{
  def getAxi4Config() = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    useId = false,
    useRegion = false,
    useBurst = false,
    useLock = false,
    useQos = false,
    useLen = false,
    useResp = true
  )

  def getAvalonConfig() = AvalonMMConfig.pipelined(
    addressWidth = 32,
    dataWidth = 32).copy(
    useByteEnable = true,
    useResponse = true,
    maximumPendingReadTransactions = 1
  )
}

case class DBusSimpleBus() extends Bundle with IMasterSlave{
  val cmd = Stream(DBusSimpleCmd())
  val rsp = DBusSimpleRsp()

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def toAxi4Shared(stageCmd : Boolean = true): Axi4Shared = {
    val axi = Axi4Shared(DBusSimpleBus.getAxi4Config())
    val pendingWritesMax = 7
    val pendingWrites = CounterUpDown(
      stateCount = pendingWritesMax + 1,
      incWhen = axi.sharedCmd.fire && axi.sharedCmd.write,
      decWhen = axi.writeRsp.fire
    )

    val cmdPreFork = if (stageCmd) cmd.stage.stage().s2mPipe() else cmd
    val (cmdFork, dataFork) = StreamFork2(cmdPreFork.haltWhen((pendingWrites =/= 0 && !cmdPreFork.wr) || pendingWrites === pendingWritesMax))
    axi.sharedCmd.arbitrationFrom(cmdFork)
    axi.sharedCmd.write := cmdFork.wr
    axi.sharedCmd.prot := "010"
    axi.sharedCmd.cache := "1111"
    axi.sharedCmd.size := cmdFork.size.resized
    axi.sharedCmd.addr := cmdFork.address

    val dataStage = dataFork.throwWhen(!dataFork.wr)
    axi.writeData.arbitrationFrom(dataStage)
    axi.writeData.last := True
    axi.writeData.data := dataStage.data
    axi.writeData.strb := (dataStage.size.mux(
      U(0) -> B"0001",
      U(1) -> B"0011",
      default -> B"1111"
    ) << dataStage.address(1 downto 0)).resized


    rsp.ready := axi.r.valid
    rsp.error := !axi.r.isOKAY()
    rsp.data := axi.r.data

    axi.r.ready := True
    axi.b.ready := True


    //TODO remove
    val axi2 = Axi4Shared(DBusSimpleBus.getAxi4Config())
    axi.arw >-> axi2.arw
    axi.w >> axi2.w
    axi.r << axi2.r
    axi.b << axi2.b
//    axi2 << axi
    axi2
  }

  def toAxi4(stageCmd : Boolean = true) = this.toAxi4Shared(stageCmd).toAxi4()



  def toAvalon(stageCmd : Boolean = true): AvalonMM = {
    val avalonConfig = DBusSimpleBus.getAvalonConfig()
    val mm = AvalonMM(avalonConfig)
    val cmdStage = if(stageCmd) cmd.stage else cmd
    mm.read := cmdStage.valid && !cmdStage.wr
    mm.write := cmdStage.valid && cmdStage.wr
    mm.address := (cmdStage.address >> 2) @@ U"00"
    mm.writeData := cmdStage.size.mux (
      U(0) -> cmdStage.data(7 downto 0) ## cmdStage.data(7 downto 0) ## cmdStage.data(7 downto 0) ## cmdStage.data(7 downto 0),
      U(1) -> cmdStage.data(15 downto 0) ## cmdStage.data(15 downto 0),
      default -> cmdStage.data(31 downto 0)
    )
    mm.byteEnable := (cmdStage.size.mux (
      U(0) -> B"0001",
      U(1) -> B"0011",
      default -> B"1111"
    ) << cmdStage.address(1 downto 0)).resized


    cmdStage.ready := mm.waitRequestn
    rsp.ready :=mm.readDataValid
    rsp.error := mm.response =/= AvalonMM.Response.OKAY
    rsp.data := mm.readData

    mm
  }
}


class DBusSimplePlugin(catchAddressMisaligned : Boolean, catchAccessFault : Boolean, earlyInjection : Boolean = false) extends Plugin[VexRiscv]{

  var dBus  : DBusSimpleBus = null


  object MEMORY_ENABLE extends Stageable(Bool)
  object MEMORY_READ_DATA extends Stageable(Bits(32 bits))
  object MEMORY_ADDRESS_LOW extends Stageable(UInt(2 bits))
  object ALIGNEMENT_FAULT extends Stageable(Bool)

  var memoryExceptionPort : Flow[ExceptionCause] = null
  override def setup(pipeline: VexRiscv): Unit = {
    import Riscv._
    import pipeline.config._

    val decoderService = pipeline.service(classOf[DecoderService])

    val stdActions = List[(Stageable[_ <: BaseType],Any)](
      SRC1_CTRL         -> Src1CtrlEnum.RS,
      SRC_USE_SUB_LESS  -> False,
      MEMORY_ENABLE     -> True,
      RS1_USE          -> True
    ) ++ (if(catchAccessFault || catchAddressMisaligned) List(IntAluPlugin.ALU_CTRL -> IntAluPlugin.AluCtrlEnum.ADD_SUB) else Nil) //Used for access fault bad address in memory stage

    val loadActions = stdActions ++ List(
      SRC2_CTRL -> Src2CtrlEnum.IMI,
      REGFILE_WRITE_VALID -> True,
      BYPASSABLE_EXECUTE_STAGE -> False,
      BYPASSABLE_MEMORY_STAGE  -> Bool(earlyInjection)
    )

    val storeActions = stdActions ++ List(
      SRC2_CTRL -> Src2CtrlEnum.IMS,
      RS2_USE -> True
    )

    decoderService.addDefault(MEMORY_ENABLE, False)
    decoderService.add(
      List(LB, LH, LW, LBU, LHU, LWU).map(_ -> loadActions) ++
      List(SB, SH, SW).map(_ -> storeActions)
    )


    if(catchAccessFault || catchAddressMisaligned) {
      val exceptionService = pipeline.service(classOf[ExceptionService])
      memoryExceptionPort = exceptionService.newExceptionPort(pipeline.memory)
    }
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    dBus = master(DBusSimpleBus()).setName("dBus")

    //Emit dBus.cmd request
    execute plug new Area{
      import execute._

      insert(ALIGNEMENT_FAULT) := {
        if (catchAddressMisaligned)
          (dBus.cmd.size === 2 && dBus.cmd.address(1 downto 0) =/= 0) || (dBus.cmd.size === 1 && dBus.cmd.address(0 downto 0) =/= 0)
        else
          False
      }

      dBus.cmd.valid := arbitration.isValid && input(MEMORY_ENABLE) && !arbitration.isStuckByOthers && !arbitration.removeIt && !input(ALIGNEMENT_FAULT)
      dBus.cmd.wr := input(INSTRUCTION)(5)
      dBus.cmd.address := input(SRC_ADD).asUInt
      dBus.cmd.size := input(INSTRUCTION)(13 downto 12).asUInt
      dBus.cmd.payload.data := dBus.cmd.size.mux (
        U(0) -> input(RS2)(7 downto 0) ## input(RS2)(7 downto 0) ## input(RS2)(7 downto 0) ## input(RS2)(7 downto 0),
        U(1) -> input(RS2)(15 downto 0) ## input(RS2)(15 downto 0),
        default -> input(RS2)(31 downto 0)
      )
      when(arbitration.isValid && input(MEMORY_ENABLE) && !dBus.cmd.ready && !input(ALIGNEMENT_FAULT)){
        arbitration.haltItself := True
      }

      insert(MEMORY_ADDRESS_LOW) := dBus.cmd.address(1 downto 0)

      //formal
      val formalMask = dBus.cmd.size.mux(
        U(0) -> B"0001",
        U(1) -> B"0011",
        default -> B"1111"
      ) |<< dBus.cmd.address(1 downto 0)
      insert(FORMAL_MEM_ADDR) := dBus.cmd.address & U"xFFFFFFFC"
      insert(FORMAL_MEM_WMASK) := (dBus.cmd.valid &&  dBus.cmd.wr) ? formalMask | B"0000"
      insert(FORMAL_MEM_RMASK) := (dBus.cmd.valid && !dBus.cmd.wr) ? formalMask | B"0000"
      insert(FORMAL_MEM_WDATA) := dBus.cmd.payload.data
    }

    //Collect dBus.rsp read responses
    memory plug new Area {
      import memory._


      insert(MEMORY_READ_DATA) := dBus.rsp.data
      arbitration.haltItself setWhen(arbitration.isValid && input(MEMORY_ENABLE) && !input(INSTRUCTION)(5) && !dBus.rsp.ready)

      if(catchAccessFault || catchAddressMisaligned){
        if(!catchAccessFault){
          memoryExceptionPort.code := (input(INSTRUCTION)(5) ? U(6) | U(4)).resized
          memoryExceptionPort.valid := input(ALIGNEMENT_FAULT)
        } else if(!catchAddressMisaligned){
          memoryExceptionPort.valid := dBus.rsp.ready && dBus.rsp.error
          memoryExceptionPort.code  := 5
        } else {
          memoryExceptionPort.valid := dBus.rsp.ready && dBus.rsp.error
          memoryExceptionPort.code  := 5
          when(input(ALIGNEMENT_FAULT)){
            memoryExceptionPort.code := (input(INSTRUCTION)(5) ? U(6) | U(4)).resized
            memoryExceptionPort.valid := True
          }
        }
        when(!(arbitration.isValid && input(MEMORY_ENABLE))){
          memoryExceptionPort.valid := False
        }
        memoryExceptionPort.badAddr := input(REGFILE_WRITE_DATA).asUInt  //Drived by IntAluPlugin
      }


      assert(!(dBus.rsp.ready && input(MEMORY_ENABLE) && arbitration.isValid && arbitration.isStuck),"DBusSimplePlugin doesn't allow memory stage stall when read happend")
    }

    //Reformat read responses, REGFILE_WRITE_DATA overriding
    val injectionStage = if(earlyInjection) memory else writeBack
    injectionStage plug new Area {
      import injectionStage._


      val rspShifted = MEMORY_READ_DATA()
      rspShifted := input(MEMORY_READ_DATA)
      switch(input(MEMORY_ADDRESS_LOW)){
        is(1){rspShifted(7 downto 0) := input(MEMORY_READ_DATA)(15 downto 8)}
        is(2){rspShifted(15 downto 0) := input(MEMORY_READ_DATA)(31 downto 16)}
        is(3){rspShifted(7 downto 0) := input(MEMORY_READ_DATA)(31 downto 24)}
      }

      val rspFormated = input(INSTRUCTION)(13 downto 12).mux(
        0 -> B((31 downto 8) -> (rspShifted(7) && !input(INSTRUCTION)(14)),(7 downto 0) -> rspShifted(7 downto 0)),
        1 -> B((31 downto 16) -> (rspShifted(15) && ! input(INSTRUCTION)(14)),(15 downto 0) -> rspShifted(15 downto 0)),
        default -> rspShifted //W
      )

      when(arbitration.isValid && input(MEMORY_ENABLE)) {
        output(REGFILE_WRITE_DATA) := rspFormated
      }

      if(!earlyInjection)
        assert(!(arbitration.isValid && input(MEMORY_ENABLE) && !input(INSTRUCTION)(5) && arbitration.isStuck),"DBusSimplePlugin doesn't allow memory stage stall when read happend")

      //formal
      insert(FORMAL_MEM_RDATA) := input(MEMORY_READ_DATA)
    }
  }
}

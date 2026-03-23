package platform.handheld

import chisel3._
import lib.mem.MemoryMap
import lib.video.ColorARGB
import platform.handheld.boot.{CartridgeUtility, Logo}
import net.gamebub.framework.interface._

class HandheldBoot extends Module with HandheldModule {
    val io = IO(new HandheldIo {
        val clocks = new ClocksFixedV0(sysDivider = 56, sdramDivider = 14)
        val video = new VideoV0(
            videoWidth = 240,
            videoHeight = 160,
            colorDepth = 5,
            framePeriod = 1.0 / 64.0,
        )
        val audio = new AudioV0()
        val host = new HostV0(overlayColorDepth = ColorARGB.argb1555())
        val pmod = new PmodV0()
        val input = new InputV0()
        val cartridge = new CartridgePortV0()
        val link = new LinkPortV0()
        val sram = new SramV0()
        val sdram = new SdramV0(sdramBurst = false)
    })

    stubUnused()

    // Logo animation
    val logo = Module(new Logo(io.video))
    io.video := logo.io.video_

    // Cartridge utility
    val cartridgeUtility = Module(new CartridgeUtility)
    io.cartridge <> cartridgeUtility.io.cartridge

    io.host.mem <> MemoryMap(
        addressWidth = 24,
        dataWidth = 32,
        entries = Seq(
            0x0.U(4.W) -> logo.io.registers,
            0x2.U(4.W) -> cartridgeUtility.io.registers,
            0x3.U(4.W) -> cartridgeUtility.io.memInterface,
        ))

    private def stubUnused(): Unit = {
        io.input.vibrate := HandheldVibrate.Off
        io.audio.left := 0.S
        io.audio.right := 0.S

        // PMOD unused
        io.pmod.out := DontCare
        io.pmod.dir := 0.U(4.W)

        // Link unused
        io.link.soOut := DontCare
        io.link.siOut := DontCare
        io.link.sdOut := DontCare
        io.link.scOut := DontCare
        io.link.soDir := false.B
        io.link.siDir := false.B
        io.link.sdDir := false.B
        io.link.scDir := false.B

        // SRAM unused
        io.sram.mem.enable := false.B
        io.sram.mem.write := false.B
        io.sram.mem.address := DontCare
        io.sram.mem.dataWrite := DontCare
        io.sram.mem.writeStrobe := DontCare

        // SDRAM unused
        io.sdram.mem.enable := false.B
        io.sdram.mem.isWrite := false.B
        io.sdram.mem.address := DontCare
        io.sdram.mem.dataWrite := DontCare
        io.sdram.mem.writeStrobe := DontCare
    }
}
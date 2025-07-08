package platform.handheld

import chisel3._
import lib.mem.MemoryMap
import lib.video.ColorARGB
import platform.handheld.boot.{CartridgeUtility, Logo}

class HandheldBoot extends Module with HandheldModule {
    val io = IO(new HandheldIo)
    def framebufferW = 240
    def framebufferH = 160
    def clockSystemHz = 16 * 1024 * 1024
    def clockSdramHz = clockSystemHz * 4
    def targetFramePeriod = 1.0 / 64.0
    override def overlayColorDepth = ColorARGB.argb1555()

    stubUnused()

    // Logo animation
    val logo = Module(new Logo(framebufferW, framebufferH))
    io.framebufferX := logo.io.framebufferX
    io.framebufferY := logo.io.framebufferY
    io.framebufferData := logo.io.framebufferData
    io.framebufferWriteEnable := logo.io.framebufferWriteEnable
    io.vblank := logo.io.vblank

    // Cartridge utility
    val cartridgeUtility = Module(new CartridgeUtility)
    io.cartridgeEnabled := cartridgeUtility.io.cartridgeEnabled
    io.cartridge <> cartridgeUtility.io.cartridge

    io.mcuInterface <> MemoryMap(
        addressWidth = 24,
        dataWidth = 32,
        entries = Seq(
            0x0.U(4.W) -> logo.io.registers,
            0x2.U(4.W) -> cartridgeUtility.io.registers,
            0x3.U(4.W) -> cartridgeUtility.io.memInterface,
        ))

    private def stubUnused(): Unit = {
        io.vibrate := false.B
        io.audioLeft := 0.S
        io.audioRight := 0.S

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
        io.sram.enable := false.B
        io.sram.write := false.B
        io.sram.address := DontCare
        io.sram.dataWrite := DontCare
        io.sram.writeStrobe := DontCare

        // SDRAM unused
        io.sdram.enable := false.B
        io.sdram.isWrite := false.B
        io.sdram.address := DontCare
        io.sdram.dataWrite := DontCare
        io.sdram.writeStrobe := DontCare
    }
}
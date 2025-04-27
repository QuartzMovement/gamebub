package platform.handheld

import chisel3._
import lib.mem.MemoryMap
import platform.handheld.boot.Logo

class HandheldBoot extends Module with HandheldModule {
    val io = IO(new HandheldIo)
    def framebufferW = 240
    def framebufferH = 160
    def clockSystemHz = 16 * 1024 * 1024
    def clockSdramHz = clockSystemHz * 4
    def targetFramePeriod = 1.0 / 64.0

    stubUnused()

    // Logo animation
    val logo = Module(new Logo(framebufferW, framebufferH))
    io.framebufferX := logo.io.framebufferX
    io.framebufferY := logo.io.framebufferY
    io.framebufferData := logo.io.framebufferData
    io.framebufferWriteEnable := logo.io.framebufferWriteEnable
    io.vblank := logo.io.vblank

    io.mcuInterface <> MemoryMap(
        addressWidth = 24,
        dataWidth = 32,
        entries = Seq(
            "b0000".U(4.W) -> logo.io.registers,
        ))

    private def stubUnused(): Unit = {
        io.vibrate := false.B
        io.audioLeft := 0.S
        io.audioRight := 0.S

        // Cartridge unused
        io.cartridgeEnabled := false.B
        io.cartridge.bank0Dir := false.B
        io.cartridge.bank1Dir := false.B
        io.cartridge.bank2Dir := false.B
        io.cartridge.bank3Dir := false.B
        io.cartridge.pin30Dir := false.B
        io.cartridge.pin31Dir := false.B
        io.cartridge.bank0Out := DontCare
        io.cartridge.bank1Out := DontCare
        io.cartridge.bank2Out := DontCare
        io.cartridge.bank3Out := DontCare
        io.cartridge.pin30Out := DontCare
        io.cartridge.pin31Out := DontCare

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
#include <iostream>
#include <cstdio>
#include <stdexcept>
#include <cstdlib>

#include "audio.hpp"
#include "common.hpp"
#include "simulator.hpp"

#include "VSimGba___024root.h"

Simulator::Simulator(std::filesystem::path rom_path, std::filesystem::path bios_path)
    : framebuffer(width(), height())
{
    this->top = new VSimGba;
    this->rom = read_file(rom_path);

    if (bios_path.empty()) {
        std::cerr << "ERROR: must specify bios path\n";
        std::exit(1);
    }
    auto bios = read_file(bios_path);
    if (bios.size() != 16 * 1024) {
        std::cerr << "ERROR: incorrect bios size: " << bios.size() << "\n";
        std::exit(1);
    }
    // Note: assumes little-endian
    memcpy(
        &this->top->rootp->SimGba__DOT__biosRom_ext__DOT__Memory,
        bios.data(),
        16 * 1024
    );
}

Simulator::~Simulator()
{
    top->final();
    delete top;
}

void Simulator::reset()
{
    top->io_enable = true;
    top->reset = 1;
    simulate_cycles(1);
    top->reset = 0;
}

void Simulator::set_joypad_state(JoypadState state)
{
    top->io_keypad_start = state.start;
    top->io_keypad_select = state.select;
    top->io_keypad_b = state.b;
    top->io_keypad_a = state.a;
    top->io_keypad_down = state.down;
    top->io_keypad_up = state.up;
    top->io_keypad_left = state.left;
    top->io_keypad_right = state.right;
    top->io_keypad_l = state.l;
    top->io_keypad_r = state.r;
}

void Simulator::simulate_cycles(uint64_t num_cycles)
{
    for (uint64_t i = 0; i < num_cycles; i++) {
        this->stepFramebuffer();
        this->stepAudio();

        top->clock = 0;
        top->eval();
        top->clock = 1;
        top->eval();

        if (top->io_emuCartRom_enable) {
            int cart_address = top->io_emuCartRom_address;
            // Only works on little endian system
            if (cart_address < (this->rom.size() >> 1)) {
                auto rom_words = reinterpret_cast<uint16_t*>(this->rom.data());
                top->io_emuCartRom_dataRead = rom_words[cart_address];
            }
            top->io_emuCartRom_done = 1;
//            fprintf(stderr, "[%llu] rom read addr=0x%x\n", this->cycles, cart_address);
        }

        this->cycles++;

//        fprintf(stderr, "cycle=%llu", this->cycles);
//        if (cycles == 473799) {
//            size_t size = 256 * 1024;
//            std::vector<uint8_t> dump;
//            dump.resize(size);
//            memcpy(
//                dump.data(),
//                reinterpret_cast<uint8_t*>(&this->top->rootp->SimGba__DOT__gba__DOT__ewram__DOT__mem_ext__DOT__Memory),
//                size
//            );
//            write_file("/tmp/dump.bin", dump);
//            exit(0);
//        }
    }
}

void Simulator::stepFramebuffer()
{
    framebuffer.update(top->io_ppu_hblank, top->io_ppu_vblank);

    if (top->io_ppu_valid) {
      framebuffer.pushBGR(top->io_ppu_pixel);
    }
}

void Simulator::simulate_frame()
{
    simulate_cycles(280896);

// Testing: save OBJ vram
//    size_t size = 16 * 1024;
//    std::vector<uint8_t> dump;
//    dump.resize(size * 2);
//    memcpy(
//        dump.data(),
//        reinterpret_cast<uint8_t*>(&this->top->rootp->SimGba__DOT__gba__DOT__ppu__DOT__vram__DOT__memObjLo__DOT__mem_mem_0_ext__DOT__Memory),
//        size
//    );
//    memcpy(
//            dump.data() + size,
//            reinterpret_cast<uint8_t*>(&this->top->rootp->SimGba__DOT__gba__DOT__ppu__DOT__vram__DOT__memObjHi__DOT__mem_mem_0_ext__DOT__Memory),
//            size
//        );
//    write_file("/tmp/dump_obj.bin", dump);
}

void Simulator::stepAudio()
{
    // TODO
}

std::vector<int16_t>& Simulator::getAudioSampleBuffer()
{
    return audioSampleBuffer;
}

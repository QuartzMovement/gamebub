#include <iostream>
#include <cstdio>

#include "audio.hpp"
#include "common.hpp"
#include "simulator.hpp"

Simulator::Simulator(std::filesystem::path rom_path)
    : framebuffer(width(), height())
{
    this->top = new VGBA;
    this->rom = read_file(rom_path);
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
}

void Simulator::simulate_cycles(uint64_t num_cycles)
{
    for (uint64_t i = 0; i < num_cycles; i++) {
        this->stepFramebuffer();
        this->stepAudio();

        bool cart_request = top->io_cartRom_request;
        int cart_address = top->io_cartRom_address;

        top->clock = 0;
        top->eval();
        top->clock = 1;
        top->eval();

        top->io_cartRom_done = cart_request;
        // Only works on little endian system
        if (cart_address <= this->rom.size() - 4) {
            auto rom_words = reinterpret_cast<uint32_t*>(this->rom.data());
            top->io_cartRom_dataRead = rom_words[cart_address >> 2];
        }
        if (cart_request) {
            fprintf(stderr, " @@@ (ROM) addr=0x%08X   data=0x%08X\n\n", cart_address, top->io_cartRom_dataRead);
        } else {
            fprintf(stderr, "\n\n");
        }
        top->eval();

        this->cycles++;
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
}

void Simulator::stepAudio()
{
    // TODO
}

std::vector<int16_t>& Simulator::getAudioSampleBuffer()
{
    return audioSampleBuffer;
}

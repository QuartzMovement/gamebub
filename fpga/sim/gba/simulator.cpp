#include <iostream>

#include "audio.hpp"
#include "common.hpp"
#include "simulator.hpp"

Simulator::Simulator(std::filesystem::path rom_path)
    : framebuffer(width(), height())
{
    this->top = new VGBA;
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

        top->clock = 0;
        top->eval();
        top->clock = 1;
        top->eval();

        this->cycles++;
    }
}

void Simulator::stepFramebuffer()
{
    // TODO
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

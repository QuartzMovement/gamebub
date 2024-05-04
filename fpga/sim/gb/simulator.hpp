#pragma once

#include <filesystem>

#include "cartridge.hpp"
#include "input.hpp"
#include "VSimGameboy.h"

class Simulator {
public:
    Simulator(std::filesystem::path rom_path);
    ~Simulator();

    void set_joypad_state(JoypadState state);
    void simulate_cycles(uint64_t cycles);
    void simulate_frame();
    void reset();
    std::vector<uint8_t>& getFramebuffer();
    std::vector<int16_t>& getAudioSampleBuffer();

    static int width() { return 160; }
    static int height() { return 144; }
    static int clockHz() { return 4 * 1024 * 1024; }
    static int audioSampleHz() { return 256 * 1024; }

private:
    void stepFramebuffer();
    void stepAudio();

    std::unique_ptr<Cartridge> cart;
    std::vector<uint8_t> framebuffer0;
    std::vector<uint8_t> framebuffer1;
    bool activeFramebuffer = false;
    uint64_t cycles = 0;
    VSimGameboy* top = nullptr;
    size_t framebufferIndex = 0;
    bool prev_vblank = false;
    bool prev_hblank = false;
    bool prev_lcd_enabled = false;
    std::vector<int16_t> audioSampleBuffer;
    int audioTimer = 0;
};

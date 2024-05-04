#include "framebuffer.hpp"

Framebuffer::Framebuffer(int width, int height) {
    this->width = width;
    this->height = height;

    this->page0.resize(width * height * 4, 0xFF);
    this->page1.resize(width * height * 4, 0xFF);
}

void Framebuffer::update(bool hblank, bool vblank) {
    if (vblank && !prev_vblank) {
        index = 0;
        activePage = !activePage;
    }
    prev_hblank = hblank;
    prev_vblank = vblank;
}

void Framebuffer::clear() {
    std::fill(page0.begin(), page0.end(), 0xFF);
    std::fill(page1.begin(), page1.end(), 0xFF);
    index = 0;
}

void Framebuffer::pushBGR(uint16_t pixel) {
    std::vector<uint8_t>& buffer = writeBuffer();
    if (index >= buffer.size() - 4) {
        // TODO: make this a fatal error (framebuffer overrun).
        return;
    }

    uint8_t r = (pixel >> 0) & 0x1F;
    uint8_t g = (pixel >> 5) & 0x1F;
    uint8_t b = (pixel >> 10) & 0x1F;
    buffer[index++] = (b << 3) | (b >> 2);
    buffer[index++] = (g << 3) | (g >> 2);
    buffer[index++] = (r << 3) | (r >> 2);
    buffer[index++] = 0xFF;
}

std::vector<uint8_t>& Framebuffer::writeBuffer() {
    return activePage ? page1 : page0;
}

std::vector<uint8_t>& Framebuffer::renderBuffer() {
    return activePage ? page0 : page1;
}

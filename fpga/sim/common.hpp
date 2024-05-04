#pragma once

#include <vector>
#include <filesystem>
#include <iostream>
#include <fstream>

static std::vector<uint8_t> read_file(std::filesystem::path path) {
    std::vector<uint8_t> buffer;
    std::ifstream in(path, std::ios::binary);
    in.seekg(0, std::ios::end);
    size_t size = in.tellg();
    in.seekg(0, std::ios::beg);
    buffer.resize(size);
    in.read(reinterpret_cast<char*>(buffer.data()), size);
    return buffer;
}

static void write_file(std::filesystem::path path, std::vector<uint8_t>& buffer) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<char*>(buffer.data()), buffer.size());
}
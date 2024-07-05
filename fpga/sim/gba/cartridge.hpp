#pragma once

#include <memory>
#include <vector>
#include <filesystem>

class Cartridge {
public:
    Cartridge(std::filesystem::path rom_path);
    ~Cartridge();

    std::vector<uint8_t> rom;
    int config_backup_type;
    int config_backup_size;
    bool config_backup_autodetect;

private:
    std::filesystem::path rom_path;

    void detect_backup_type();
};
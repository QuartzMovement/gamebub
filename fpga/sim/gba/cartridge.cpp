#include "cartridge.hpp"
#include "common.hpp"

#include <iostream>
#include <fstream>
#include <cstring>
#include <cstdio>

Cartridge::Cartridge(std::filesystem::path rom_path) : rom_path(rom_path) {
    // Load the ROM.
    this->rom = read_file(rom_path);

    detect_backup_type();

    // Pad to 32 MiB (for now)
    this->rom.resize(32 * 1024 * 1024, 0xFF);
}

Cartridge::~Cartridge() {
}

bool find_string(std::vector<uint8_t>& rom, const char* string) {
    size_t len = strlen(string);
    for (size_t i = 0; i <= rom.size() - len; i += 4) {
        if (memcmp(rom.data() + i, string, len) == 0) {
            return true;
        }
    }
    return false;
}

void Cartridge::detect_backup_type() {
    config_backup_type = 0;
    config_backup_size = 0;
    config_backup_autodetect = false;

    if (find_string(rom, "EEPROM_V")) {
        printf("Detected EEPROM (auto)\n");
        config_backup_type = 3;
        config_backup_autodetect = true;
    } else if (find_string(rom, "SRAM_V") || find_string(rom, "SRAM_F_V")) {
        printf("Detected SRAM\n");
        config_backup_type = 1;
    } else if (find_string(rom, "FLASH_V") || find_string(rom, "FLASH512_V")) {
        printf("Detected Flash 64kB\n");
        config_backup_type = 2;
        config_backup_size = 0;
    } else if (find_string(rom, "FLASH1M_V")) {
        printf("Detected Flash 128kB\n");
        config_backup_type = 2;
        config_backup_size = 1;
    } else {
        printf("Detected no backup\n");
    }
}
<p align="center">
    <img src="./docs/assets/logo.svg" width="300px"> 
</p>

**Game Bub** is an open-source FPGA retro emulation handheld, with support for Game Boy, Game Boy Color, and Game Boy Advance games.

---

<!-- TODO: link to announcement blog post -->

<!-- TODO: insert a picture -->

## Features
* Physical Game Boy / Color / Advance cartridge support
* Can load and play ROMs from a microSD card (emulated cartridge)
* Built-in support for common cartridge peripherals (rumble, real-time clock, accelerometer, gyroscope)
* Multiplayer link cable functionality
* Custom, from-scratch Game Boy and Game Boy Advance FPGA cores with great game compatibility
* Video output to TV or monitor via custom dock
* Extensible hardware, designed for future improvements

## Building

Interested in purchasing a Game Bub kit to build your own? There are no immediate plans to offer kits, but if you're interested, [fill out this form](https://forms.gle/m1FFUqpCde7x5u5AA).

For information on manufacturing and assembling your own, see [docs/building.md](docs/building.md).

## Architecture

For an in-depth description of the project architecture, see [docs/architecture.md](docs/architecture.md).

The Game Bub handheld consists of a Xilinx XC7A100T FPGA to do the main emulation and I/O, and an ESP32-S3 microcontroller to do auxiliary tasks (configuring the FPGA, rendering the UI, loading ROM files from a microSD card and sending it to the FPGA).

### Directory Structure

* `pcb`: PCB design files for Handheld, Dock, and others
* `fpga`: FPGA source code (HDL), written in [Chisel](https://github.com/chipsalliance/chisel)
* `firmware/handheld`: Microcontroller firmware

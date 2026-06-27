[![Game Bub trailer](./docs/assets/video-poster.jpg)](https://www.youtube.com/watch?v=_WQGJFFGHmE)

**Game Bub** is an open-source FPGA retro emulation handheld, with support for Game Boy, Game Boy Color, and Game Boy Advance games.

Check out the [announcement blog post](https://eli.lipsitz.net/posts/introducing-gamebub/) for an in-depth look at the development process!


## Features
* Play physical Game Boy / Color / Advance cartridges
* Load and play ROM files from a microSD card (with built-in support for rumble, clock, accelerometer, gyroscope)
* Multiplayer link cable functionality
* Custom, from-scratch Game Boy and Game Boy Advance FPGA cores with great game compatibility
* 15+ hour battery life 
* Video output to TV or monitor via custom dock
* Extensible hardware, designed for future improvements

## Building

Building a Game Bub handheld requires manufacturing PCBs, 3D printing the shell and buttons, and assembling components from a variety of sources. For information on manufacturing and assembling your own, see [docs/building.md](docs/building.md).

Are you instead interested in purchasing a complete Game Bub kit? There are no immediate plans to offer kits, but if you're interested, [fill out this form](https://forms.gle/m1FFUqpCde7x5u5AA).

For other inquiries, contact me directly at eli@lipsitz.net.

## Architecture

For an in-depth description of the project architecture, see [docs/architecture.md](docs/architecture.md).

The Game Bub handheld consists of a Xilinx XC7A100T FPGA to do the main emulation and I/O, and an ESP32-S3 microcontroller to do auxiliary tasks (configuring the FPGA, rendering the UI, loading ROM files from a microSD card and sending it to the FPGA).

### Directory Structure

* `fpga`: FPGA source code (HDL), written in [Chisel](https://github.com/chipsalliance/chisel)
* `firmware`: Microcontroller firmware

## License

Unless otherwise specified:

* Firmware (in `firmware/`) and scripts (in `scripts/`) are licensed under GPLv3 (`GPL-3.0-only`).
* FPGA source code (in `fpga/`) is licensed under the CERN Open Hardware License Version 2 - Strongly Reciprocal (`CERN-OHL-S-2.0`)
* PCB (schematic and layout), mechanical, and hardware design files are licensed under the CERN Open Hardware License Version 2 - Strongly Reciprocal (`CERN-OHL-S-2.0`)

At a high level, this means that you can copy, share, and modify the source code, as long as you provide proper attribution and share your source code / design files with the same license. However, the "Game Bub" name and logo are trademarked, and you may not use them for your product without permission.

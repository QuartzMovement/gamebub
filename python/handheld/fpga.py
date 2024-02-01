import time
import deflate

from machine import Pin, SPI

class FPGA:
    def __init__(
        self,
        pin_power: Pin,
        pin_done: Pin,
        pin_program_b: Pin,
        pin_init_b: Pin,
        pin_spi_cs: Pin,
        program_spi: "Callable[[], SPI]",
        fpga_spi: "Callable[[], SPI]",
    ) -> None:
        self._pin_power = pin_power
        self._pin_done = pin_done
        self._pin_program_b = pin_program_b
        self._pin_init_b = pin_init_b
        self._pin_spi_cs = pin_spi_cs
        self._program_spi = program_spi
        self._fpga_spi = fpga_spi

    def program(self, bitstream='/top_handheld.bit.gz') -> None:
        # TODO check and see if timings can be reduced
        print("Powering on FPGA")
        self._pin_power.value(1)
        self._pin_spi_cs.value(1)
        time.sleep_ms(100)
        self._pin_program_b.value(0)
        time.sleep_ms(50)

        print("init_b (should be 0):", self._pin_init_b.value())
        self._pin_program_b.value(1)
        while self._pin_init_b.value() == 0:
            print("waiting for init_b...")
            time.sleep_ms(50)

        print("FPGA is in program mode.")
        f = raw_file = open(bitstream, 'rb')
        if bitstream.endswith('.gz'):
            f = deflate.DeflateIO(f)
        f.read(129) # discard header

        i = 0
        chunk_size = 16 * 1024
        duration_read = 0
        duration_write = 0
        while True:
            print(f"Sending byte ", chunk_size * i)
            i += 1
            start_time = time.time_ns()
            data = f.read(chunk_size)
            end_time = time.time_ns()
            duration_read += (end_time - start_time)
            if len(data) == 0:
                break
            start_time = time.time_ns()
            self._program_spi().write(data)
            end_time = time.time_ns()
            duration_write += (end_time - start_time)
        #
        print(f"Done! read time (sec) = {duration_read / 1_000_000_000}, write time = {duration_write / 1_000_000_000}")
        time.sleep_ms(100)
        print("Done pin (should be 1):", self._pin_done.value())

    def spi_write(self, address: int, data: bytes) -> None:
        command = 0x00
        self._pin_spi_cs.value(0)
        self._fpga_spi().write(bytes([command, (address >> 24) & 0xFF, (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF]) + data)
        self._pin_spi_cs.value(1)

    def spi_read(self, address: int, nbytes: int) -> bytes:
        command = 0x01
        self._pin_spi_cs.value(0)
        self._fpga_spi().write(bytes([command, (address >> 24) & 0xFF, (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF]))
        data = self._fpga_spi().read(nbytes)
        self._pin_spi_cs.value(1)
        return data

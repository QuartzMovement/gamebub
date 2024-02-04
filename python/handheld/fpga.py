import time
import deflate

from machine import Pin, SPI

class FPGA:
    SPI_DUMMY_BYTES: int = 4

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
        self._program_spi()  # grab SPI bus

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

    @staticmethod
    def _spi_command(read: bool, word_size: int, byte_swap: bool, auto_increment: bool) -> int:
        return (0
            | (int(read) << 0)
            | ({8: 0, 16: 1, 32: 2, 64: 3}[word_size] << 1)
            | (int(byte_swap) << 3)
            | (int(auto_increment) << 4)
        )

    def spi_write(self, command: int, address: int, data: bytes) -> None:
        self._fpga_spi()  # ensure bus is acquired / clock is set before pulling CS low
        self._pin_spi_cs.value(0)
        self._fpga_spi().write(bytes([command]))
        self._fpga_spi().write(address.to_bytes(4, 'big'))
        self._fpga_spi().write(data)
        self._pin_spi_cs.value(1)

    def spi_read(self, command: int, address: int, nbytes: int) -> bytes:
        self._fpga_spi()  # ensure bus is acquired / clock is set before pulling CS low
        self._pin_spi_cs.value(0)
        self._fpga_spi().write(bytes([command]))
        self._fpga_spi().write(address.to_bytes(4, 'big'))
        self._fpga_spi().read(self.SPI_DUMMY_BYTES)
        data = self._fpga_spi().read(nbytes)
        self._pin_spi_cs.value(1)
        return data

    def spi_write_u16(self, address: int, data: int) -> None:
        """Write a single 16-bit value."""
        command = self._spi_command(read=False, word_size=16, byte_swap=False, auto_increment=True)
        self.spi_write(command, address, data.to_bytes(2, 'big'))

    def spi_read_u16(self, address: int) -> int:
        """Read a single 16-bit value."""
        command = self._spi_command(read=True, word_size=16, byte_swap=False, auto_increment=True)
        data = self.spi_read(command, address, 2)
        return int.from_bytes(data, 'big')

    def sram_write(self, address: int, data: bytes) -> None:
        """Write a sequence of bytes to SRAM. Address is relative to SRAM start."""
        mem_address = 0x8000_0000 | address
        command = self._spi_command(read=False, word_size=16, byte_swap=True, auto_increment=True)
        self.spi_write(command, mem_address, data)

    def sram_read(self, address: int, nbytes: int) -> bytes:
        """Read a sequence of bytes from SRAM. Address is relative to SRAM start."""
        mem_address = 0x8000_0000 | address
        command = self._spi_command(read=True, word_size=16, byte_swap=True, auto_increment=True)
        return self.spi_read(command, mem_address, nbytes)


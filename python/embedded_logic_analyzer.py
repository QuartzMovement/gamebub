import base64
from serial import Serial
import sys
import time
import json

REG_SAMPLE_WIDTH = 0x0
REG_SAMPLE_DEPTH = 0x4
REG_LOG_INFO = 0x8
REG_ARM = 0x10
REG_FORCE_TRIGGER = 0x14
SIGNAL_BASE = 0x40_0000
LOG_BASE = 0x80_0000

class Signal:
    name: str
    offset: int
    width: int

class EmbeddedLogicAnalyzer:
    def __init__(self, serial: Serial, base_address: int, metadata) -> None:
        self.serial = serial
        self.base_address = base_address
        
        self.signals = []
        for raw in metadata["signals"]:
            signal = Signal()
            signal.name = raw["name"]
            signal.offset = raw["offset"]
            signal.width = raw["width"]

    def run_command(self, command: str) -> str:
        self.serial.write(b">" + command.encode() + b"\n")
        while True:
            response = self.serial.readline()
            if not response.startswith(b"<"):
                # Ignore a non-response line
                print(response)
                continue
            return response[1:].rstrip()

    def fpga_read(self, address: int, length: int) -> bytes:
        max_clock_mhz = 10
        word_size = 32
        command = f"fpga_read,{hex(address)},{word_size},{max_clock_mhz},{hex(length)}"
        output = self.run_command(command)
        if not output.startswith(b"ok"):
            raise Exception("Read failed: " + output.decode())
        return base64.b64decode(output[3:])

    def fpga_write(self, address: int, data: bytes) -> None:
        max_clock_mhz = 40
        word_size = 32
        data = base64.b64encode(data)
        command = f"fpga_write,{hex(address)},{word_size},{max_clock_mhz}," + data.decode()
        output = self.run_command(command)
        if not output.startswith(b"ok"):
            raise Exception("Write failed: " + output.decode())

    def read_register(self, address: int) -> int:
        data = self.fpga_read(self.base_address + address, 4)
        return int.from_bytes(data, "little")

    def write_register(self, address: int, value: int) -> None:
        data = value.to_bytes(4, byteorder="little")
        self.fpga_write(self.base_address + address, data)


def main(args: list[str]) -> None:
    serial_path = args[1]
    serial = Serial(serial_path)

    metadata = {"signals":[{"name":".bundleVal.anotherUInt","offset":0,"width":6},{"name":".uintVal","offset":6,"width":16},{"name":".boolVal","offset":22,"width":1}]}
    ela = EmbeddedLogicAnalyzer(serial, 0x3000_0000, metadata)
    print(ela.run_command("get_hwinfo"))

    sample_width = ela.read_register(REG_SAMPLE_WIDTH)
    sample_depth = ela.read_register(REG_SAMPLE_DEPTH)


    # signal 0 match 12345 (level)
    ela.write_register(SIGNAL_BASE + (1 * 0x10) + 0x4, 12345) #match0 = 12345
    ela.write_register(SIGNAL_BASE + (1 * 0x10) + 0x0, 0b11)  #enable trigger, match0 only
    # OR signal 1 match 14 (level)
    ela.write_register(SIGNAL_BASE + (0 * 0x10) + 0x4, 14)    #match0 = 14
    ela.write_register(SIGNAL_BASE + (0 * 0x10) + 0x0, 0b11)  #enable trigger, match0 only
    # OR signal 2 falling edge (prev 0, prev prev 1)
    ela.write_register(SIGNAL_BASE + (2 * 0x10) + 0x8, 1)
    ela.write_register(SIGNAL_BASE + (2 * 0x10) + 0x4, 0)
    ela.write_register(SIGNAL_BASE + (2 * 0x10) + 0x0, 0b111)
    ela.write_register(REG_ARM, 1)

    for i in range(0, 2):
        log_info = ela.read_register(REG_LOG_INFO)
        log_write_index = log_info & 0xFFFFFF
        log_write_wrapped = (log_info >> 24) & 1
        log_is_recording = (log_info >> 25) & 1
        print("log write index: ", log_write_index)
        print("log write wrapped: ", log_write_wrapped)
        print("log is recording: ", log_is_recording)
        print("====")
        time.sleep(0.5)

        # if i == 5:
            # ela.write_register(REG_FORCE_TRIGGER, 1)


    start_time = time.time()
    # can read in chunks of 1024 bytes
    full_log = bytearray()
    for i in range(0, 2048 * 4, 1024):
        full_log += ela.fpga_read(ela.base_address + LOG_BASE + i, 1024)
    duration = time.time() - start_time
    print("log: ", duration)

    log_length = sample_depth if log_write_wrapped else (log_write_index - 1)
    log_start = log_write_index if log_write_wrapped else 0

    for i in range(log_length):
        x = (log_start + i) % sample_depth
        data = full_log[(x * 4):(x * 4 + 4)]
        value = int.from_bytes(data, "little")

        values = [
            ((value >> s.offset) & ((1 << s.width) - 1))
            for s in ela.signals
        ]

        val1 = (value >> 0) & 0b111111
        val2 = (value >> 6) & 0b1111111111111111
        val3 = (value >> 22) & 1

        print(i - log_length + 1, ":", val1, val2, val3)


if __name__ == '__main__':
    main(sys.argv)

import base64
from serial import Serial
import sys
import time


class EmbeddedLogicAnalyzer:
    def __init__(self, serial: Serial, base_address: int) -> None:
        self.serial = serial
        self.base_address = base_address

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

    ela = EmbeddedLogicAnalyzer(serial, 0x3000_0000)
    print(ela.run_command("get_hwinfo"))


    ela.write_register(0xC, 1) # arm

    for x in range(0, 20, 4):
        data = ela.read_register(x)
        print(f"{hex(x)}: {hex(data)} == {data}")

    print("=====")
    ela.write_register(0x10, 1) # force trigger

    for x in range(0, 20, 4):
        data = ela.read_register(x)
        print(f"{hex(x)}: {hex(data)} == {data}")



    log_base = 0x80_0000
    start_time = time.time()
    # can read in chunks of 1024 bytes
    full_log = bytearray()
    for i in range(0, 2048 * 4, 1024):
        full_log += ela.fpga_read(ela.base_address + log_base + i, 1024)
    duration = time.time() - start_time
    print("log: ", duration)
    for x in range(0, 2048):
        data = full_log[(x * 4):(x * 4 + 4)]
        value = int.from_bytes(data, "little")
        print(hex(value))
    # for x in range(0, 2048):
        # value = ela.read_register(log_base + x)
        # print(hex(value))


if __name__ == '__main__':
    main(sys.argv)

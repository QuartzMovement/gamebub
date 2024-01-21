import machine
from machine import SPI, I2C, Pin
import time

from .ili9488 import ILI9488
from .max17048 import MAX17048
from .tlv320dac3101 import TLV320DAC3101
from .fpga import FPGA

lcd_backlight = Pin(6, Pin.OUT)
lcd_reset = Pin(7, Pin.OUT)
lcd_cs = Pin(15, Pin.OUT)
lcd_dc = Pin(16, Pin.OUT)
status_led = Pin(3, Pin.OUT)
dac_reset = Pin(40, Pin.OUT)
fpga_power = Pin(46, Pin.OUT)
fpga_done = Pin(17, Pin.IN)
fpga_program_b = Pin(18, Pin.OPEN_DRAIN, value=1)
fpga_init_b = Pin(8, Pin.IN)
fpga_spi_cs = Pin(10, Pin.OUT)
pin_vbus_pgood_n = Pin(41, Pin.IN)
pin_chg = Pin(42, Pin.IN)
mcu_irq = Pin(2, Pin.IN)

# BUG (Rev A): FPGA can interfere with LCD SPI, also using LCD SPI while FPGA is unpowered probably isn't great
fpga_power.value(1)

i2c = I2C(0, sda=Pin(38), scl=Pin(39), freq=400_000)

class SpiGetter(object):
    """SPI bus 'getter' to allow changing baudrate"""
    def __init__(self) -> None:
        self._spi = None
        self._baudrate = None

    def __call__(self, baudrate: int) -> SPI:
        if self._spi is None or self._baudrate != baudrate:
            self._baudrate = baudrate
            # polarity = 0, phase = 0, firstbit = MSB
            self._spi = SPI(2, baudrate=baudrate, sck=Pin(12), mosi=Pin(11), miso=Pin(13))
        return self._spi
spi = SpiGetter()

lcd_pwm = machine.PWM(lcd_backlight, freq=30_000, duty=256)

fuel_gauge = MAX17048(i2c)

print("Initializing LCD")
lcd = ILI9488(lambda: spi(10_000_000), pin_rst=lcd_reset, pin_cs=lcd_cs, pin_dc=lcd_dc)
lcd.setup()

print("Initializing DAC")
dac = TLV320DAC3101(i2c, dac_reset)
dac.setup()
dac.set_volume(100)
# dac.set_mute(True)
dac.set_mute(False)
dac.set_headphones_enabled(True)
dac.set_speakers_enabled(False)

print("Passing control of display to FPGA")
# BPGRAM=0 (write to mem), RM=1 (RGB interface), DM=1 (DOTCLK), RCM=0 (DE mode), 
# lcd._write_cmd(0xB6, bytes([0x32]))
# hsync, vsync, enable polarity high, dotclock: sample on falling edge
lcd._write_cmd(0xB0, bytes([0x0E]))
# TESTING: bypass memory, direct to shift register:
lcd._write_cmd(0xB6, bytes([0xB2, 0x62]))   # BYPASS memory, direct to shift register... and rotate gate/drive 
lcd._write_cmd(0xB4, bytes([0x00]))  # set display inversion to "column inversion"

print("Programming FPGA")
fpga = FPGA(
    fpga_power,
    fpga_done,
    fpga_program_b,
    fpga_init_b,
    fpga_spi_cs,
    program_spi = lambda: spi(80_000_000),
    fpga_spi = lambda: spi(1_000_000),
)
fpga.program()


# # 1-bit SPI
# sd_card = machine.SDCard(
#     slot=3, # SPI
#     width=1,
#     cd = Pin(37),
#     sck = Pin(45),
#     miso = Pin(35),
#     mosi = Pin(48),
#     cs = Pin(47),
#     freq = 20_000_000,
# )
# # 4-bit SDIO (requires micropython changes to set pins)
# sd_card = machine.SDCard(
#     slot=1, # SDIO
#     width=4,
#     freq=40_000_000,
# )
# os.mount(sd_card, "/sd")
# s = time.time_ns() ; print(len(open('/sd/BOOT.bin', 'rb').read(256 * 1024))) ; e = time.time_ns()
# print("time (ns)", e - s)




# INIT CODE FROM EXAMPLE

### END






## write frame data?

# def lcd_write_data_pix(DH, DL):
#     LD = DH<<8
#     LD |= DL

#     R1=(0x1f&(LD>>11))*2
#     R1<<=2
#     G1=0x3f&(LD>>5)
#     G1<<=2
#     B1= (0x1f&LD)*2
#     B1<<=2

#     lcd_cs.value(0)
#     lcd_dc.value(1) # data
#     print([R1, G1, B1])
#     display_spi.write(bytes([R1, G1, B1])) 
#     lcd_cs.value(1)

# def lcd_write_data_u16(y):
#     m=y>>8
#     n=y
#     lcd_write_data_pix(m,n)


# lcd.set_pos(0,319,0,479)
# for i in range(0, 320):
#     for j in range(0, 480):
#         lcd_write_data_u16(0xf800)

# print("Writing screen...")
# for i in range(0, 256):
#     data = bytes([248, i, 0] * 480)
#     lcd.set_pos(0,319,0,479)
#     lcd_cs.value(0)
#     lcd_dc.value(1)
#     for i in range(0, 320):
#         display_spi.write(data)
#     lcd_cs.value(1)
# print("Done writing screen")

# lcd.set_pos(0,479,0,319)
# for y in range(0, 320):
#     for x in range(0, 480):
#         data = bytes([x & 0xFF, (y << 3) & 0xFF, 0])
#         lcd_cs.value(0)
#         lcd_dc.value(1)
#         display_spi.write(data)
#         lcd_cs.value(1)
#         # time.sleep_us(1000)

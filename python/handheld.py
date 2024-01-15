import machine
from machine import I2C, Pin
import time
import sys

lcd_backlight = Pin(6, Pin.OUT)
lcd_reset = Pin(7, Pin.OUT)
lcd_cs = Pin(15, Pin.OUT)
lcd_dc = Pin(16, Pin.OUT)
status_led = Pin(3, Pin.OUT)
i2c = I2C(0, sda=Pin(38), scl=Pin(39), freq=400_000)
fpga_power = Pin(46, Pin.OUT)
dac_reset = Pin(40, Pin.OUT)
fpga_done = Pin(17, Pin.IN)
fpga_program_b = Pin(18, Pin.OPEN_DRAIN, value=1)
fpga_init_b = Pin(8, Pin.IN)
fpga_spi_d2 = Pin(14, Pin.IN)

# BUG: FPGA can interfere with LCD SPI, also using LCD SPI while FPGA is unpowered probably isn't great
fpga_power.value(1)

lcd_pwm = machine.PWM(lcd_backlight, freq=30_000, duty=256)

display_spi = machine.SPI(2, baudrate=10_000_000, polarity=1, phase=1, firstbit=machine.SPI.MSB, sck=Pin(12), mosi=Pin(11), miso=Pin(13))

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


class ILI9488:
    def __init__(
        self,
        spi: machine.SPI,
        pin_rst: machine.Pin,
        pin_cs: machine.Pin,
        pin_dc: machine.Pin
    ) -> None:
        self._spi = spi
        self._pin_rst = pin_rst
        self._pin_cs = pin_cs
        self._pin_dc = pin_dc

    def _write_cmd(self, cmd: int, params: bytes = bytes()) -> None:
        self._pin_cs.value(0)
        self._pin_dc.value(0) # command
        self._spi.write(bytes([cmd]))
        if params:
            self._pin_cs.value(1) # Is toggling cs necessary?
            self._pin_cs.value(0) 
            self._pin_dc.value(1) # data
            self._spi.write(params)
        self._pin_cs.value(1)

    def _read_cmd(self, cmd, len=2):
        self._pin_cs.value(0)
        self._pin_dc.value(0) # command
        self._spi.write(bytes([cmd]))
        #self._pin_cs.value(1) # Is toggling cs necessary?
        #self._pin_cs.value(0) 
        #self._pin_dc.value(1) # data
        data = self._spi.read(len)
        self._pin_cs.value(1)
        return data

    def setup(self) -> None:
        self._pin_cs.value(1)

        self._pin_rst.value(0)
        time.sleep_us(100)
        self._pin_rst.value(1)
        time.sleep_ms(120)
        # Must wait 120ms to send "SLEEP OUT"

        # "Adjust Control 3": params have no specified meaning
        self._write_cmd(0xF7, bytes([0xA9, 0x51, 0x2C, 0x82]))

        # MADCTL (Memory Access Control):
        # BGR order, rotate display orientation
        # (vendor provided is 0x48)
        # TODO might want to also update LCD shift register direction somewhere?
        # self._write_cmd(0x36, bytes([0xE8])) # Rotated
        self._write_cmd(0x36, bytes([0xC8])) # original  (works with *native* bitstream, W=320,H=480)

        # Interface Pixel Format: DPI = 18 bit, DBI = 18 bit
        #  (16 bit doesn't work)
        self._write_cmd(0x3A, bytes([0x66]))

        # Interface Mode Control: use separate SPI read/write wires
        # TODO: THIS SHOULD BE 0b1000_0000 -- use the same SDA wire,
        #    because ILI9488 does *NOT* tri-state SDO when CS is high, 
        #    meaning it screws up the SPI bus. It should be *disconnected*
        #    (or in a board revision, a tri-state buffer added.)
        self._write_cmd(0xB0, bytes([0x00]))

        # Display Inversion Control: 2-dot (from vendor)
        self._write_cmd(0xB4, bytes([0x02]))

        # Frame rate: 60 Hz
        self._write_cmd(0xB1, bytes([0xA0, 0x11]))

        # Power Control 1: Vreg1out=4.56  Vreg2out=-4.56 (from vendor)
        self._write_cmd(0xC0, bytes([0x0f, 0x0f]))

        # Power Control 2: VGH=15.81 ,VGL=-10.41,DDVDH=5.35,DDVDL=-5.23  VCL=-2.7 (from vendor)
        self._write_cmd(0xC1, bytes([0x41]))

        # Power Control 3: (from vendor)
        self._write_cmd(0xC2, bytes([0x22]))

        # VCOM Control (from vendor)
        self._write_cmd(0xC5, bytes([0x00, 0x53, 0x80]))

        # Entry Mode Set (from vendor)
        self._write_cmd(0xB7, bytes([0xC6]))

        # Positive Gamma Control (from vendor)
        self._write_cmd(0xE0, bytes([0x00, 0x08, 0x0C, 0x02, 0x0E, 0x04, 0x30, 0x45, 0x47, 0x04, 0x0C, 0x0A, 0x2E, 0x34, 0x0F]))

        # Negative Gamma Control (from vendor)
        self._write_cmd(0xE1, bytes([0x00, 0x11, 0x0D, 0x01, 0x0F, 0x05, 0x39, 0x36, 0x51, 0x06, 0x0F, 0x0D, 0x33, 0x37, 0x0F]))

        # Display Inversion ON (?? from vendor)
        self._write_cmd(0x21)

        # Sleep out
        self._write_cmd(0x11)
        time.sleep_ms(10)

        # Display on
        self._write_cmd(0x29)
        time.sleep_ms(10)

    # TODO remove or change
    def set_pos(self, xs, xe, ys, ye):
        # Column Address Set
        self._write_cmd(0x2A, bytes([xs>>8, xs&0xff, xe>>8, xe&0xff]))
        # Page Address Set
        self._write_cmd(0x2B, bytes([ys>>8, ys&0xff, ye>>8, ye&0xff]))
        # Begin Memory Write
        self._write_cmd(0x2C)

# INIT CODE FROM EXAMPLE

### END

print("Initializing LCD")
lcd = ILI9488(display_spi, pin_rst=lcd_reset, pin_cs=lcd_cs, pin_dc=lcd_dc)
lcd.setup()




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

print("Writing screen")

# lcd.set_pos(0,479,0,319)
# for y in range(0, 320):
#     for x in range(0, 480):
#         data = bytes([x & 0xFF, (y << 3) & 0xFF, 0])
#         lcd_cs.value(0)
#         lcd_dc.value(1)
#         display_spi.write(data)
#         lcd_cs.value(1)
#         # time.sleep_us(1000)


###
# setting FPGA control of display:
print("Setting FPGA control???")
# BPGRAM=0 (write to mem), RM=1 (RGB interface), DM=1 (DOTCLK), RCM=0 (DE mode), 
###
lcd._write_cmd(0xB6, bytes([0x32]))
# hsync, vsync, enable polarity high, dotclock: sample on falling edge
lcd._write_cmd(0xB0, bytes([0x0E]))


class TLV320DAC3101:
    i2c_address = 0x18

    def __init__(
        self,
        i2c: machine.SPI,
        pin_rst: machine.Pin,
    ) -> None:
        self._i2c = i2c
        self._pin_rst = pin_rst

    def reset(self) -> None:
        self._pin_rst.value(0)
        time.sleep_us(1)
        self._pin_rst.value(1)
        time.sleep_ms(1)

    def _set_page(self, page) -> None:
        self._i2c.writeto_mem(self.i2c_address, 0, bytes([page & 0xFF]))

    def _read_reg(self, page, reg) -> int:
        """Read 8-bit register"""
        self._set_page(page)
        return self._i2c.readfrom_mem(self.i2c_address, reg, 1)[0]

    def _write_reg(self, page, reg, data) -> int:
        """Write 8-bit register"""
        self._set_page(page)
        self._i2c.writeto_mem(self.i2c_address, reg, bytes([data & 0xFF]))

    def setup(self) -> None:
        """
        Sets up the DAC with both channels muted, and speakers and headphones
        both disabled.

        # DAC Configuration

        ## Filter Selection
            PRB_P7 (interp. filter B) or PRB_P17 (interp. filter C) look like 
            basic, low power, stereo filters. They use IIR without any
            biquads.
            Section 6.3.10.1.4 talks about different interpolation filters.
            Type B is for up to 96kHz, Type C is specifically for 192 kHz.
            So we're going with *PRB_P7* for now.

        ## Clock dividers
            CODEC_CLKIN = NDAC × MDAC × DOSR × DAC_fS
            DAC_fS is 48KHz, and CODEC_CLKIN is MCLK, chosen to be 256 * DAC_fS

            So NDAC × MDAC × DOSR = 256

            For filter type B, DOSR must be a multiple of 4.
            (DOSR is "oversampling ratio"?)
            2.8 MHz < DOSR × DAC_fS < 6.2 MHz
            Thus, DOSR can be 64 or 128.

            DOSR = 128

            NDAC and MDAC can be from 1..128.
            NDAC should be as large as possible with "MDAC × DOSR / 32 ≥ RC",
            where RC for PRB_P7 is 6.

            MDAC = 2, NDAC = 1

            To increase NDAC, we can use the PLL to multiply MCLK.

        ## Common-mode voltage
            Based on the analog power supply. For Rev A, we have 3.3V.
            The options are 1.35 V, 1.5 V, 1.65 V, or 1.8 V, and it must be
            <= AVDD/2. 
            We'll go with 1.5V
        """

        # 1. Set up device.
        self.reset()

        # Do software reset?
        # self._write_reg(0,  0x01, 0x01)

        # 2. Program clock settings
        # PLL_clkin = MCLK, codec_clkin=MCLK
        self._write_reg(0, 0x04, 0x00)

        # PLL is unused
        # self._write_reg(0, 0x06, 0x08)
        # self._write_reg(0, 0x07, 0x00)
        # self._write_reg(0, 0x08, 0x00)
        # self._write_reg(0, 0x05, 0x91)

        # Program and power up NDAC ( = 1)
        self._write_reg(0, 0x0B, 0x81)

        # Program and power up MDAC ( = 2)
        self._write_reg(0, 0x0C, 0x82)

        # Program OSR
        #
        # DOSR = 128, DOSR(9:8) = 0, DOSR(7:0) = 128
        self._write_reg(0, 0x0D, 0x00)
        self._write_reg(0, 0x0E, 0x80)

        # Program codec interface (I2S, 16-bit, BCLK/WCLK inputs)
        self._write_reg(0, 0x1B, 0x00)

        # Program processing block. Select PRB_P7
        self._write_reg(0, 0x3C, 0x07)
        # Enable adaptive filtering
        self._write_reg(0, 0x00, 0x08)
        self._write_reg(0, 0x01, 0x04)
        self._write_reg(0, 0x00, 0x00)

        # DAC volume control through register, not pin
        self._write_reg(0, 0x74, 0x00)

        # 3. Program analog blocks

        # Program common-mode voltage (set to 1.5 V)
        self._write_reg(1, 0x1F, 0x0C)

        # Program headphone depop settings (power on = 800ms, step = 4ms)
        self._write_reg(1, 0x21, 0x4E)

        # Route DAC output to output amplifier mixer
        # LDAC to HPL, RDAC to HPR
        self._write_reg(1, 0x23, 0x44)

        # Unmute and set gain of output driver
        # Unmute HPL, set gain = 0 db
        self._write_reg(1, 0x28, 0x06)
        # Unmute HPR, set gain = 0 dB
        self._write_reg(1, 0x29, 0x06)
        # Unmute left speaker, set gain = 6 dB
        self._write_reg(1, 0x2A, 0x04)
        # Unmute right speaker, set gain = 6 dB
        self._write_reg(1, 0x2B, 0x04)

        # Configure output drivers
        # Enable HPL output analog volume, set = -9 dB
        self._write_reg(1, 0x24, 0x92)
        # Enable HPR output analog volume, set = -9 dB
        self._write_reg(1, 0x25, 0x92)
        # Enable speaker left output analog volume, set = -9 dB
        self._write_reg(1, 0x26, 0x92)
        # Enable speaker right output analog volume, set = -9 dB
        self._write_reg(1, 0x27, 0x92)

        # TODO: Apply waiting time determined by the de-pop settings and the soft-stepping settings
        #    of the driver gain or poll page 1 / register 63
        # ... 

        # 5. Power up DAC

        # Powerup DAC left and right channels (soft step enabled)
        self._write_reg(0, 0x3F, 0xD4)

        # Enable headphone detection
        self._write_reg(0, 0x43, 0x80)

    def set_volume(self, volume: int) -> None:
        """
        Sets DAC volume (range 0 to 255) for left and right.
        Mapped to DAC's volume range of -63.5 dB to 24 dB.
        """
        # map 0 -> -127, 255 -> 48
        # range = 175
        value = ((volume * 175) // 255) - 127
        self._write_reg(0, 0x41, value & 0xFF)
        self._write_reg(0, 0x42, value & 0xFF)

    def set_mute(self, mute: bool) -> None:
        # Left and right are individually controllable, but this sets them together.
        self._write_reg(0, 0x40, 0xC if mute else 0x0)

    def set_headphones_enabled(self, enabled: bool) -> None:
        if enabled:
            self._write_reg(1, 0x1F, 0xC2)
        else:
            self._write_reg(1, 0x1F, 0x02)

    def set_speakers_enabled(self, enabled: bool) -> None:
        if enabled:
            self._write_reg(1, 0x20, 0xC6)
        else:
            self._write_reg(1, 0x20, 0x06)

    def get_headphones_detected(self) -> bool:
        status = (self._read_reg(0, 0x43) >> 5) & 0b11
        return (status == 0b01) or (status == 0b11)


print("Setting up DAC")
dac = TLV320DAC3101(i2c, dac_reset)
dac.setup()
dac.set_volume(128)
# dac.set_mute(True)
dac.set_mute(False)
dac.set_headphones_enabled(True)
dac.set_speakers_enabled(True)



def program_fpga(bitstream='/top_handheld.bit'):
    # TODO check and see if timings can be reduced
    print("Powering on FPGA")
    fpga_power.value(1)
    time.sleep_ms(100)
    fpga_program_b.value(0)
    time.sleep_ms(50)
    print("init_b (should be 0):", fpga_init_b.value())
    fpga_program_b.value(1)
    while fpga_init_b.value() == 0:
        print("waiting for init_b...")
        time.sleep_ms(50)

    print("FPGA is in program mode.")
    f = open(bitstream, 'rb')
    f.read(129) # discard header

    fpga_config_spi = machine.SPI(2, baudrate=80_000_000, polarity=0, phase=0, firstbit=machine.SPI.MSB, sck=Pin(12), mosi=Pin(11), miso=Pin(13))
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
        fpga_config_spi.write(data)
        end_time = time.time_ns()
        duration_write += (end_time - start_time)
    #
    print(f"Done! read time (ns) = {duration_read}, write time = {duration_write}")
    time.sleep_ms(100)
    print("Done pin (should be 1):", fpga_done.value())

program_fpga()
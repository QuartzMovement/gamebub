@ GBA boot stub
@ 
@ Sets up proper CPU state before jumping to cartridge entrypoint.
@ Usable in place of BIOS (or patched in at 0x68, the official BIOS reset vector).
@
@ From mGBA:
@   r0: 00000000   r1: 00000000   r2: 00000000   r3: 00000000
@   r4: 00000000   r5: 00000000   r6: 00000000   r7: 00000000
@   r8: 00000000   r9: 00000000  r10: 00000000  r11: 00000000
@  r12: 00000000  r13: 03007F00  r14: 08000000  r15: 08000004
@ cpsr: 0000001F [-------]

.arm
.align 4

mov r0, #0x3000000
orr r0, r0, #0x7f00
msr cpsr_fc, #0x11     @ FIQ
mov sp, r0
msr cpsr_fc, #0x12     @ IRQ
orr sp, r0, #0xA0
msr cpsr_fc, #0x13     @ SVC
orr sp, r0, #0xE0
msr cpsr_fc, #0x17     @ ABT
mov sp, r0
msr cpsr_fc, #0x1B     @ UND
mov sp, r0
msr cpsr_fc, #0x1F     @ System
mov sp, r0
mov r0, #0
mov lr, #0x8000000
mov pc, lr

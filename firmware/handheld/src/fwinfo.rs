#[repr(C)]
struct FirmwareMetadata {
    git_commit: [u8; 20],
}

#[used]
#[link_section = ".rodata_custom_desc"]
static FIRMWARE_METADATA: FirmwareMetadata = FirmwareMetadata {
    git_commit: GIT_COMMIT_BYTES,
};

pub const GIT_COMMIT_BYTES: [u8; 20] = {
    const fn parse(hex: u8) -> u8 {
        if hex >= b'0' && hex <= b'9' {
            hex - b'0'
        } else if hex >= b'a' && hex <= b'f' {
            hex - b'a' + 10
        } else {
            panic!();
        }
    }

    let mut hash = [0u8; 20];
    let string = env!("GIT_COMMIT").as_bytes();
    let mut i = 0;
    while i < 20 {
        hash[i] = parse(string[i * 2]) << 4 | parse(string[i * 2 + 1]);
        i += 1;
    }
    hash
};

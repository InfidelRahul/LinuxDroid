    .section .rodata
    .global _binary_loader_elf_start
    .global _binary_loader_elf_end
    .type _binary_loader_elf_start, %object
    .type _binary_loader_elf_end, %object
_binary_loader_elf_start:
    .incbin "loader.elf"
_binary_loader_elf_end:

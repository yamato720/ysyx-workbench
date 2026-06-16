# ZCU102 PS-side NEMU SDB

This directory is now the PS-side entry for a source-built NEMU simple debugger.
It does not load `nemu` as a shared object. Instead, `sw/src/ps_nemu_sdb.c`
links against the NEMU sources reached through `zcu102-runtime/nemu-src/nemu`.

Build:

```bash
make -C zcu102-runtime build-ps
```

This builds both software PS-side SDB binaries:

```text
build/bin/zcu102-ps-nemu-sdb-rv64
build/bin/zcu102-ps-nemu-sdb-rv32
```

Run the direct NEMU SDB:

```bash
make -C zcu102-runtime run-ps
```

The executable enters NEMU's original `(nemu)` SDB loop, so commands such as
`help`, `si`, `c`, `info r`, `x`, `p`, `w`, and `q` come from NEMU itself.

Run the test launcher shell:

```bash
make -C zcu102-runtime busy-box-nano
```

`busy-box-nano` is a C menu shell. It lists `am-kernels/tests/cpu-tests`,
builds the selected test on demand for `riscv64-nemu`, `riscv64-npc`,
`riscv32-nemu`, or `riscv32-npc`,
launches the matching NEMU SDB with the generated image, and returns to the
menu when NEMU exits.

CPU-test helpers:

```bash
make -C zcu102-runtime busy-box-nano
make -C zcu102-runtime list-cpu-tests
make -C zcu102-runtime build-cpu-test TEST=add
make -C zcu102-runtime run-cpu-test TEST=add
make -C zcu102-runtime run-cpu-test TEST=dummy TEST_ARCH=riscv32-nemu
make -C zcu102-runtime build-cpu-test TEST=dummy TEST_ARCH=riscv64-npc
```

`busy-box-nano` builds selected programs from
`zcu102-runtime/nemu-src/am-kernels/tests/cpu-tests` into
`zcu102-runtime/build/busy-box-nano/cpu-tests`, then launches the PS-side NEMU
with the generated `.bin` image and `.elf` symbol file. It stores the last menu
configuration, including the selected arch, in
`zcu102-runtime/build/busy-box-nano/config`.

The PS-side NEMU build uses local generated config headers under
`zcu102-runtime/include/riscv64` and `zcu102-runtime/include/riscv32`, so the
shared `nemu`, `abstract-machine`, and `am-kernels` defaults are not changed for
non-ZCU102 flows.

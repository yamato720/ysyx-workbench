---
name: ysyx-config-build
description: Operate the YSYX workbench's Config-driven NPC, ysyxSoC, NEMU-host, and FPGA construction workflow. Use when inspecting or changing Scala CDE Config composition, `make config=`, construction versions, `run`/`run-bat`, NEMU host builds, FPGA board settings, generated RTL routing, or NPC/SoC memory integration in this repository.
---

# YSYX Config Build

Treat a complete Scala Config as the hardware ABI, build policy, and runtime-host selection. Work from the workbench root; the NPC root is `npc/`.

## Orient First

Read these sources before changing build behavior or Config composition:

1. `npc/README.md` and `npc/chisel/configs/README.md` for the public interface and layer model.
2. `npc/chisel/configs/parameters/ConfigCatalogGenerator.scala` for automatic terminal discovery.
3. `npc/chisel/configs/npc/base/ConfigBase.scala` and `npc/chisel/configs/npc/core/ConstructionConfig.scala` for `NpcCoreConfigKey` and L1 composition.
4. `npc/chisel/configs/common/base/ConstructionTraits.scala` and `npc/chisel/configs/common/TerminalTraits.scala` for the base construction interfaces and directly mountable terminal traits.
5. `npc/Makefile`, `npc/scripts/construction-manager.sh`, and `am-kernels/tests/cpu-tests/Makefile` for the actual lifecycle and user-facing commands.

Do not hand-edit `npc/chisel/configs/resources/scpu-config-catalog.tsv` or infer public availability from class names. Make refreshes the catalog through Scala and only discovers complete, public, no-argument Configs from each terminal domain's root `Configs.scala`.

## Config Rules

- Preserve `left ++ right` precedence: the right side establishes defaults and the left side overrides identical CDE keys.
- Use `ConstructionConfig` for reusable L1 NPC hardware. It publishes a completed `NpcConfig` through `NpcCoreConfigKey`.
- Let SoC and FPGA configurations consume that same key. A higher layer can override the default NPC by placing a complete L1 Config to the left of its SoC graph.
- Put parameter keys, ordinary data models, composition protocols, atomic `With...Config` fragments, and low-level construction interfaces in `base/`. Base must not depend on `core/`, provide terminal catalog identity, or describe a directly runnable target. A terminal Config must never mix a base construction trait directly.
- Build terminal-ready, plainly named hardware combinations in `core/`. Core consumes base; a terminal must select complete core hardware values instead of expanding base fragments again. Keep reusable integration and `check-only` Configs here without Make terminal identity.
- Keep terminal-level files directly at the domain root, outside `base/` and `core/`. Put the four shared traits in root-level `common/TerminalTraits.scala`: `NpcTerminal`, `SocTerminal`, `FpgaNpcTerminal`, and `FpgaSocTerminal`. Do not create a `terminal/` directory for them.
- Keep every Make-selectable terminal Config in its domain root `Configs.scala`, and keep only public no-argument terminal classes in that file. Each terminal must mix exactly one terminal-layer trait. That single trait combines backend construction behavior, catalog identity, scope, and target; do not separately mix `HostConstruction`, `NemuSimulationConstruction`, `FpgaConstruction`, or `MakeTerminal`.
- Keep `CheckOnlyConstruction` as the directly mountable core trait for non-Make check Configs. Name shared construction traits without a `Trait` suffix, and name files that define those traits with the `*Traits.scala` suffix.
- Do not add empty `Configs.scala` files to `common/` or `nemu/`; they have no hardware terminal Configs. `common/TerminalTraits.scala` is their shared root-level terminal protocol. FPGA boards share `fpga/common/base/`, form terminal-ready board policy in each board's `core/`, and define final terminals in that board's root `Configs.scala`.
- Keep hardware parameters in CDE. Terminals directly provide ordinary case class values through `configuredNemu: NemuHostConfig`; FPGA terminals additionally provide grouped `configuredFpga: FpgaToolchainConfig`. Use nested `copy(...)` for local overrides instead of adding CDE keys or another Make selector.
- Keep `check-only` Configs out of Make's public catalog. Keep board policy at L4 under `configs/fpga/u55c/` or `configs/fpga/zcu102/`.
- Put only common accelerator/IP parameters in `configs/common/`; keep NPC-specific ISA, pipeline, interface, and memory fragments under `configs/npc/`.

## Use the Public Interface

Use only the Config-driven commands unless explicitly repairing an internal build step:

```bash
make -C npc config-list
make -C npc build config=SimulationConfig
make -C npc host-build config=SimulationConfig
make -C npc version
make -C am-kernels/tests/cpu-tests run ALL=add config=SimulationConfig
make -C am-kernels/tests/cpu-tests run-bat ALL="forwarding matrix-mul" version=1,2 jobs=2
```

- Accept short Config names or registered FQCNs; resolve them through the generated catalog.
- Use `version=<positive index>` as the stable reference to a saved construction. Do not expose internal timestamp IDs as normal user workflow.
- Staging constructions carry `.incomplete`; after host/RTL/asset validation the manager writes `.complete` and only then atomically publishes the directory. `make -C npc version` reads complete published metadata without regenerating the Scala catalog or waiting for a long-running build lock. FPGA completion additionally requires the U55C xclbin or ZCU102 `npc.bit` to exist and be nonempty; full manifest hashes remain a build/run validation. The query only waits when an old construction still needs its one-time version metadata migration. `resolve` uses the same nonblocking published snapshot for saved versions but may refresh a current Config profile when no saved version was selected.
- Read the `version` output as two tables: the primary table uses `+`/blank feature cells for RV32/RV64, M/F/Zicsr, Pipe/ID/EX, target, and board; the second table maps stable version numbers to Config short names. Pipeline always occupies exactly the Pipe, ID-forwarding, and EX-forwarding cells.
- Before giving a user a `run` or `run-bat` command for a Config, check `make -C npc version config=<Config>`. If a matching saved construction exists, present `version=<positive index>` as the preferred execution selector so the command uses its frozen hardware ABI, runtime host, and FPGA assets. For batches across saved constructions, prefer a `version=1,2,...` matrix. Use `config=<Config>` for execution only when no saved construction exists, the user explicitly requests current Config resolution, or the command creates or updates a construction with `build`/`rebuild`.
- Keep `run` and `run-bat` focused on execution. Use `make -C npc build config=<FpgaConfig> rebuild=1` for a deliberate FPGA asset replacement.
- Use `host-build` or `host-rebuild=1` for saved NEMU-host C/C++ or `NemuHostConfig` changes. It rereads the current terminal, atomically replaces only the saved profile's `NEMU_*` segment and host, and preserves hardware, FPGA toolchain fields, assets, version metadata, and runtime reports. Do not pair `host-rebuild=1` with full `rebuild=1`.
- FPGA toolchain changes require `make -C npc build config=<Config> rebuild=1`; `host-build` intentionally does not absorb them.
- Use `jobs=-1` for unlimited batch execution. `job=` is ignored, and the removed `host_rebuild=` spelling fails with an explicit replacement message.
- Expect `run-bat` to prepare selected constructions serially before launching its parallel version-by-test matrix. `jobs=N` applies only after preparation. To refresh every saved runnable NEMU host in parallel, use `make -C npc host-build all=1 jobs=N`, then run the batch without `host-rebuild=1`.
- Do not resurrect legacy selectors such as `S=1`, `fpga=`, `soc=`, `SIM_FPGA_CONFIG`, or hand-written snapshot IDs.

## Construction Lifecycle

- Store each completed Config at `npc/constructions/<FQCN>/`; preserve its public version index across a successful rebuild.
- Build through staging directories and atomically replace the saved construction only on success. Keep failed logs and keep the previous usable construction.
- Public construction output is phase-oriented: show compact stage progress (and live Chisel `N/M` progress) while preserving complete raw tool output. In an interactive terminal, a passthrough phase with no new output for one second shows a transient spinner that never enters logs; the next tool line clears it. The latest successful hardware phases live in `constructions/<FQCN>/logs/build/`, and a host refresh lives in `logs/host/`; failed attempts replace only `constructions/.failed/<FQCN>/<build|host>/` and never mutate the saved ABI.
- The reusable current-source profile cache in `constructions/.profiles/` is keyed by a Scala/profile-input fingerprint. It automatically refreshes for `resolve` and `config-list` after Config or profile-source changes, while an already saved construction remains frozen until an explicit `rebuild=1`.
- Keep every Vivado-participating phase live, including the U55C `v++` link flow. The arithmetic-IP Tcl stores one log per generated IP under `fpga/ip/logs/`; other historical tool output remains in the phase log unless it is compact progress or a failure excerpt.
- Freeze the Alveo Vitis XRT environment policy in `FpgaToolchainConfig.flow.vitisXrtMode` and render it as `FPGA_VITIS_XRT_MODE`; it applies only to `v++`, never to the saved runtime host. On the local U55C/Vitis 2022.2 flow use `unset` so Vitis selects its bundled `xclbinutil` rather than an incompatible newer `XILINX_XRT` installation.
- FPGA implementation-report depth and auxiliary-report switches are frozen in `FpgaToolchainConfig.reports`, then rendered as `FPGA_REPORT_*` in `profile.env`; do not introduce Make overrides. U55C writes reports through a Vitis post-route hook and ZCU102 sources the same Tcl after `impl_1` opens. Each implementation run owns `npc-implementation-reports/`; failed constructions preserve those directories.
- `run-bat` must not interleave parallel child output. It reports a completion-ordered per-item table only after all tasks finish, then a stable version/Config/test comparison table and a per-item `performance.html` home-page index. Child reports are reached from that home page; do not print a parallel direct-link list or generate a batch HTML. Per-item raw run output is temporary; session TSV summaries and `details.txt` remain under `log/constructions/runs/`.
- Treat `profile.env`, `construction.env`, saved host files, generated RTL, and FPGA manifests as one construction ABI. Do not mix files from the current checkout into an old FPGA construction.
- `NemuHostConfig.performanceHtml` is the optional parent report feature. When enabled, a construction-backed run writes `performance.html` as the report home page and `instructions.html` as the searchable per-commit detail page under `constructions/<FQCN>/runtime/<test>/<timestamp-ns>-<pid>/`. `pipelineHtml` requires `performanceHtml`, is local-Verilator-only, and reuses the same bounded commit records to add `pipeline.html`; do not collect a second trace. The home page opens child reports in a new window, and each child links back. All local NPC/SoC simulation terminals currently bind `NemuHostConfig.LocalPipelineTrace`, enable per-commit NPC/NEMU software self-difftest, and generate all three pages. Self-difftest compares GPRs, FPRs, FCSR, next PC, and main-memory store bus effects so the first architectural divergence is reported directly, including adjacent-lane corruption that shared memory would otherwise mask. Scalar cores show sequential stage residency; pipelined cores additionally show overlap and stalls. The wrapper atomically updates `<test>/latest`; optional SDB VCD clips use numbered `wave-*.vcd` files in the same directory. A full `rebuild=1` drops prior runtime reports with the old hardware ABI, while `host-build` preserves them.
- Let local NPC/SoC runs ensure their saved construction. For FPGA, require a complete saved asset set and validate board, manifest, checksum, platform, and mailbox ABI before attempting board execution.
- The current FPGA mailbox ABI is `npc-fpga-mailbox-v3`. FPGA profiles also freeze every enabled M/F `OPERATOR_ROUTE_*` entry and `FPGA_NOTIFICATION_MODE`; a changed route, mailbox ABI, or notification mode requires `rebuild=1`, not only `host-rebuild=1`.
- `ps-uio-irq` profiles use the ZCU102 PS/UIO host interrupt path. `xrt-poll` profiles keep explicit U55C XRT polling because that runtime does not expose a consumable IRQ file descriptor. Neither notification mode is a RISC-V external interrupt.

## Memory and SoC Integration

- Read `npc/chisel/rv-core/main/scala/protocol/axi/NpcMemoryFabric.scala` before changing memory topology.
- Distinguish local simulation from external memory: local NPC uses DPI RAM/MMIO; external NPC merges IF and LSU-main-memory AXI-Lite requests, then bridges them to one AXI4-Full master.
- Do not describe the two Lite clients as a doubled data width. They are arbitrated request sources; the current bridge uses the same data width on Lite and Full.
- Do not assume ysyxSoC removes the NPC fabric. The NPC Lite arbiter, address split, and Lite-to-Full bridge remain; the SoC places Rocket AXI4 interconnect downstream. In current FPGA mode many SoC peripherals are intentionally absent, so retain or add the SoC path only when its system-level topology is needed.
- Treat a wider HBM/DDR beat as a separate width-adapter/cache-line design. It requires correct alignment, byte strobes, read extraction, burst semantics, and response ordering; changing one bus-width parameter is insufficient.

## Commit in Reviewable Slices

- Inspect the root worktree and affected submodules before editing, and identify independent commit boundaries for the task.
- Keep each commit to one coherent behavior change plus the tests and documentation required to verify it. Do not combine unrelated Config, NEMU, FPGA, memory, report, or cleanup work merely because they belong to one long-running task or already share a dirty worktree.
- After an independent slice passes its narrow regression, commit it immediately when the user has authorized commits. Without that authorization, report the ready commit boundary and ask before committing. Do not split work into commits that are knowingly uncompilable, untestable, or incomplete.
- Review the staged diff before every commit. Exclude construction caches, generated artifacts, `log/`, failed-build evidence, and unrelated user changes unless they are explicitly part of that slice.
- Commit submodule changes inside the submodule first. When push is explicitly authorized, push those commits before committing and pushing the root repository's gitlink update. Authorization to commit does not by itself authorize a push.
- When the user requests Codex attribution, preserve the requested `Co-authored-by` trailer on every applicable commit, not only the final commit in a series.

## Verify Proportionally

For Config or construction changes, run the catalog and the narrowest relevant checks first:

```bash
make -C npc config-list
make -C npc version
cd npc && sbt "root/test"
cd npc/chisel/ysyxSoC && mill -i ysyxsoc.compile
```

Run `mill -i ysyxsocTest.test` when altering the SoC graph. Run construction or FPGA dry-run regressions when changing lifecycle, profile, board, AXI, or artifact logic. Do not start a full Vivado/Vitis implementation or real-board test unless the user specifically requests it.

## Keep This Skill Current

Treat this skill as part of the runtime-layer contract. Before finishing any change that affects one of the following, compare the resulting source behavior with this file and update this file in the same task when the instructions, paths, command examples, or invariants have changed:

- public Make targets, arguments, defaults, or rejected legacy interfaces;
- Config discovery, terminal traits, CDE composition, names, scopes, or host traits;
- construction directory layout, version selection, rebuild/host-rebuild behavior, atomic replacement, or validation;
- NEMU host configuration, FPGA board execution, mailbox/asset ABI, or generated-profile consumption;
- NPC/SoC memory routing, AXI topology, bus-width semantics, or where DPI/board memory is selected.

Read the affected implementation and public README first; update this skill only with durable workflow knowledge, not transient logs, one-off failures, generated catalog rows, or individual construction IDs. Run the skill validator after changing this file:

```bash
"${PYTHON_WITH_YAML:-$HOME/chipyard/.conda-env/bin/python3.10}" \
  /home/pyx/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  "$PWD/.codex/skills/ysyx-config-build"
```

#ifndef ZCU102_RUNTIME_OVERRIDES_H
#define ZCU102_RUNTIME_OVERRIDES_H

/*
 * Local compile-time override hook for sources reached through zcu102-runtime/nemu-src.
 *
 * Keep board/runtime-specific switches here instead of editing NEMU,
 * abstract-machine, or am-kernels directly. Makefile targets inject this file
 * with `-include`, so future local patches can be expressed as #define-based
 * switches from this single boundary.
 */

#ifndef ZCU102_RUNTIME
#define ZCU102_RUNTIME 1
#endif

#endif

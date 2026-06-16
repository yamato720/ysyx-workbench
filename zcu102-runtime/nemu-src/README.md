# zcu102-runtime source links

This directory is a local source portal for the runtime bring-up flow.

```text
nemu              -> ../../nemu
abstract-machine  -> ../../abstract-machine
am-kernels        -> ../../am-kernels
navy-apps         -> ../../navy-apps
nanos-lite        -> ../../nanos-lite
```

Build targets under `zcu102-runtime` should refer to these paths instead of
reaching around the runtime directory directly. Runtime-specific compile-time
switches are injected through `../include/zcu102_runtime_overrides.h`.

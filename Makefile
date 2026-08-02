STUID = ysyx_22040000
STUNAME = 普禹翔

# DO NOT modify the following code!!!

TRACER = tracer-ysyx
GITFLAGS = -q --author='$(TRACER) <tracer@ysyx.org>' --no-verify --allow-empty

YSYX_HOME = $(NEMU_HOME)/..
WORK_BRANCH = $(shell git rev-parse --abbrev-ref HEAD)
WORK_INDEX = $(YSYX_HOME)/.git/index.$(WORK_BRANCH)
TRACER_BRANCH = $(TRACER)

LOCK_DIR = $(YSYX_HOME)/.git/

# prototype: git_soft_checkout(branch)
define git_soft_checkout
	git checkout --detach -q && git reset --soft $(1) -q -- && git checkout $(1) -q --
endef

# prototype: git_commit(msg)
define git_commit
	-@flock $(LOCK_DIR) $(MAKE) -C $(YSYX_HOME) .git_commit MSG='$(1)'
	-@sync $(LOCK_DIR)
endef

.git_commit:
	-@while (test -e .git/index.lock); do sleep 0.1; done;               `# wait for other git instances`
	-@git branch $(TRACER_BRANCH) -q 2>/dev/null || true                 `# create tracer branch if not existent`
	-@cp -a .git/index $(WORK_INDEX)                                     `# backup git index`
	-@$(call git_soft_checkout, $(TRACER_BRANCH))                        `# switch to tracer branch`
	-@git add . -A --ignore-errors                                       `# add files to commit`
	-@(echo "> $(MSG)" && echo $(STUID) $(STUNAME) && uname -a && uptime `# generate commit msg`) \
	                | git commit -F - $(GITFLAGS)                        `# commit changes in tracer branch`
	-@$(call git_soft_checkout, $(WORK_BRANCH))                          `# switch to work branch`
	-@mv $(WORK_INDEX) .git/index                                        `# restore git index`

.clean_index:
	rm -f $(WORK_INDEX)

_default:
	@echo "Please run 'make' under subprojects."

# npc/Makefile 会 include 本文件；只有从工作区根目录直接调用时才声明转发目标，
# 避免这些目标覆盖 npc 自己的实现并递归进入 npc/npc。
WORKBENCH_ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ifeq ($(abspath $(CURDIR)),$(WORKBENCH_ROOT_DIR))
# 构造管理命令不依赖当前目录；从工作区根目录转发可以保持 config/version 等
# 命令行变量由递归 make 原样传给 npc/Makefile。
NPC_FORWARD_TARGETS = config-list host-config-list build rebuild resume-post-link host-build build-host rebuild-host version

$(NPC_FORWARD_TARGETS):
	+$(MAKE) -C "$(CURDIR)/npc" "$@"

.PHONY: .git_commit .clean_index _default $(NPC_FORWARD_TARGETS)
else
.PHONY: .git_commit .clean_index _default
endif

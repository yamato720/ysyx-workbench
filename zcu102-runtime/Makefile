NPC_DIR ?= ../npc
GENERATED_DIR := generated

.PHONY: chisel selftest clean-generated lint-sw lint-rtl lint-selftest check check-selftest

chisel:
	@echo "=== Generating ZCU102 NPC debugger from Chisel ==="
	@cd $(NPC_DIR) && sbt "root/runMain zcu102.ElaborateZCU102NPCDebugger"
	@echo "Generated Verilog files in $(GENERATED_DIR)"

selftest:
	@echo "=== Generating ZCU102 NPC self-test from Chisel ==="
	@cd $(NPC_DIR) && sbt "root/runMain zcu102.ElaborateZCU102NPCSelfTest"
	@echo "Generated Verilog files in $(GENERATED_DIR)"

lint-sw:
	@gcc -std=c11 -Wall -Wextra -I sw/include -c sw/src/zcu102_npc_debugger.c -o /tmp/zcu102_npc_debugger.o
	@gcc -std=c11 -Wall -Wextra -I sw/include -c sw/examples/selftest.c -o /tmp/zcu102_npc_selftest.o

lint-rtl: chisel
	@verilator --lint-only --sv $(GENERATED_DIR)/ZCU102NPCDebugger.v

lint-selftest: selftest
	@verilator --lint-only --sv $(GENERATED_DIR)/ZCU102NPCSelfTest.v

check: lint-sw lint-rtl

check-selftest: lint-sw lint-selftest

clean-generated:
	@rm -rf $(GENERATED_DIR)

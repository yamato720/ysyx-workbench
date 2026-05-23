NPC_DIR ?= ../npc
GENERATED_DIR := generated

.PHONY: chisel clean-generated lint-sw lint-rtl check

chisel:
	@echo "=== Generating ZCU102 NPC debugger from Chisel ==="
	@cd $(NPC_DIR) && sbt "root/runMain zcu102.ElaborateZCU102NPCDebugger"
	@echo "Generated Verilog files in $(GENERATED_DIR)"

lint-sw:
	@gcc -std=c11 -Wall -Wextra -I sw/include -c sw/src/zcu102_npc_debugger.c -o /tmp/zcu102_npc_debugger.o

lint-rtl: chisel
	@verilator --lint-only --sv $(GENERATED_DIR)/ZCU102NPCDebugger.v

check: lint-sw lint-rtl

clean-generated:
	@rm -rf $(GENERATED_DIR)

# ============================================================================
# Makefile for the iPod tool (ipod project)
#
# Same layout as light-sdk/Makefile (see that one for the Android vocab
# explainers). The new thing here is `make sync`:
#
#   1. Drop .mp3/.m4a/.wav/.ogg/.flac files into ./library/  (create it)
#   2. Run `make sync`
#   3. They appear in iPod's library after a rescan
#
# How sync works: debug builds of Android apps are "debuggable", which lets
# adb's `run-as` command act as the app and write into its private storage —
# the same folder the tool's LightFileShare API reads (files/shared/music/).
# Works on the emulator AND on a real LP3 over USB (the tool must be
# installed there first).
# ============================================================================

export JAVA_HOME := /opt/homebrew/opt/openjdk@17

SDK_ROOT := /opt/homebrew/share/android-commandlinetools
ADB      := /opt/homebrew/bin/adb
EMULATOR := $(SDK_ROOT)/emulator/emulator
AVD      := lp3

# Application id, from tool/lighttool.toml
APP_ID   := com.thelightphone.ipod

TOOL_APK := tool/build/outputs/apk/debug/tool-debug.apk

# Local folder of audio files to mirror onto the device
LIBRARY  := library

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this list of targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-14s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Compile the tool into an APK
	./gradlew :tool:assembleDebug

.PHONY: install
install: build ## Build + install onto the connected device/emulator
	$(ADB) install -r $(TOOL_APK)

.PHONY: run
run: install ## Build + install + (re)launch
	$(ADB) shell monkey -p $(APP_ID) 1 >/dev/null
	@echo ">> $(APP_ID) launched"

.PHONY: sync
sync: ## Recursively push everything in ./library/ into the tool's music folder, relaunch
	@test -d $(LIBRARY) || { echo ">> create a '$(LIBRARY)/' folder and put audio files in it"; exit 1; }
	@# Stage each file in /data/local/tmp (world-readable), then copy it into
	@# the app's private files/shared/music/ as the app itself via run-as,
	@# preserving the folder structure under $(LIBRARY) (any layout is fine —
	@# the scanner walks it recursively).
	$(ADB) shell run-as $(APP_ID) mkdir -p files/shared/music
	@set -e; \
	found_files=$$(find $(LIBRARY) -type f \( -iname '*.mp3' -o -iname '*.m4a' -o -iname '*.wav' -o -iname '*.ogg' -o -iname '*.flac' \)); \
	[ -n "$$found_files" ] || { echo ">> no audio files found in $(LIBRARY)/"; exit 0; }; \
	old_ifs=$$IFS; IFS=$$'\n'; \
	for f in $$found_files; do \
		IFS=$$old_ifs; \
		rel=$$(echo "$$f" | sed 's|^$(LIBRARY)/||'); \
		staged=$$(echo "$$rel" | tr '/' '_'); \
		echo ">> pushing $$rel"; \
		$(ADB) push "$$f" "/data/local/tmp/$$staged" </dev/null >/dev/null; \
		reldir=$$(dirname "$$rel"); \
		if [ "$$reldir" != "." ]; then $(ADB) shell "run-as $(APP_ID) mkdir -p 'files/shared/music/$$reldir'" </dev/null; fi; \
		$(ADB) shell "run-as $(APP_ID) cp '/data/local/tmp/$$staged' 'files/shared/music/$$rel' && rm '/data/local/tmp/$$staged'" </dev/null; \
		IFS=$$'\n'; \
	done; IFS=$$old_ifs
	@# Restart the tool so it re-reads its library folder
	$(ADB) shell am force-stop $(APP_ID)
	$(ADB) shell monkey -p $(APP_ID) 1 >/dev/null
	@echo ">> synced. device library now contains:"
	@$(ADB) shell run-as $(APP_ID) find files/shared/music -type f

.PHONY: device-library
device-library: ## List what's in the tool's music folder on the device
	$(ADB) shell run-as $(APP_ID) ls -la files/shared/music 2>/dev/null || echo ">> none (run 'make sync')"

.PHONY: logs
logs: ## Stream the tool's log output. Ctrl-C to stop.
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s $(APP_ID))

.PHONY: emu
emu: ## Boot the LP3-shaped emulator (shared with the hello world project)
	$(EMULATOR) -avd $(AVD) -writable-system >/dev/null 2>&1 &
	$(ADB) wait-for-device
	@echo ">> emulator booting; give it ~20s"

.PHONY: emu-kill
emu-kill: ## Shut the emulator down
	$(ADB) emu kill

.PHONY: status
status: ## Show devices; note a USB-connected LP3 appears here too
	$(ADB) devices -l

.PHONY: clean
clean: ## Delete build output
	./gradlew clean

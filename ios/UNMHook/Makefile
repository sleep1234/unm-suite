export ARCHS = arm64
export TARGET = iphone:clang:latest:14.0

INSTALL_TARGET_PROCESSES = NeteaseMusic

include $(THEOS)/makefiles/common.mk

TWEAK_NAME = UNMHook

UNMHook_FILES = Tweak.x
UNMHook_CFLAGS = -fobjc-arc
UNMHook_FRAMEWORKS = Foundation UIKit

include $(THEOS_MAKE_PATH)/tweak.mk

# Also support direct clang build (for CI without full Theos)
.PHONY: dylib
dylib:
	@echo "Use CI workflow with logos preprocessor instead"

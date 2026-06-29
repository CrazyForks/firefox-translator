.PHONY: lint lint-fix check build screenshots

lint:
	git grep -w println app/src/main/ && echo "Found println" && exit 1 || true
	./gradlew ktlintCheck
lint-fix:
	./gradlew ktlintFormat
build:
	./build.sh
check:
	./gradlew detekt detektHardcodedStrings

# Regenerate per-section UI screenshots and push them to Weblate (needs a connected
# device + WEBLATE_* env). Runs the export test, pulls the SVGs/texts, then crops
# (Inkscape) + uploads + associates strings exactly.
screenshots:
	./gradlew :app:installDebug :app:installDebugAndroidTest
	adb shell am instrument -w \
	  -e class dev.davidv.translator.uiexport.UiExportInstrumentedTest#exportScreens \
	  -e additionalTestOutputDir /sdcard/Download/ui-export-out \
	  dev.davidv.translator.test/androidx.test.runner.AndroidJUnitRunner
	rm -rf ui-export && adb pull /sdcard/Download/ui-export-out/ui-export ./ui-export
#	python3 scripts/weblate_screenshots.py --ui-export ui-export

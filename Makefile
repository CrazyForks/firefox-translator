.PHONY: lint lint-fix check build screenshots

lint:
	git grep -w println app/src/main/ && echo "Found println" && exit 1 || true
	python3 scripts/check_extra_translations.py
	./gradlew ktlintCheck
lint-fix:
	./gradlew ktlintFormat
build:
	./build.sh
check:
	./gradlew detekt detektHardcodedStrings

# Regenerate per-section UI screenshots and push them to Weblate (needs a connected device for the
# export tests; WEBLATE_* env only for the final upload). Runs the export tests (navigation-driven +
# isolated screens), pulls the SVGs/texts, resolves data-key -> R.string into <route>.keys.json,
# renders cropped PNGs (chromium), then uploads + associates the exact strings per screenshot.
screenshots:
	./gradlew :app:installDebug :app:installDebugAndroidTest
	adb shell am instrument -w \
	  -e package dev.davidv.translator.uiexport \
	  -e additionalTestOutputDir /sdcard/Download/ui-export-out \
	  dev.davidv.translator.test/androidx.test.runner.AndroidJUnitRunner
	rm -rf ui-export && adb pull /sdcard/Download/ui-export-out/ui-export ./ui-export
	python3 tools/i18n_viewer.py ui-export/*.svg
	python3 scripts/render_svg_sections.py
	#python3 scripts/weblate_screenshots.py --screenshots ui-export/png

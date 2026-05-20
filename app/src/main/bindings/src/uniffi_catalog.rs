use std::sync::Arc;
use std::time::Instant;
use std::{fs, path::Path};

/// Diagnostic logging for the live pipeline. Flip to `true` while
/// investigating contention or per-stage costs in the planar
/// acquire/composite path; otherwise leave off — the lock timing +
/// raster body lines are noisy in normal use.
#[cfg(feature = "planar-tracker")]
const LIVE_PIPELINE_DIAG: bool = false;

/// Master toggle for per-frame outer timing logs (the
/// `outer: orient=... engine=...` and `outer: composite=...` lines
/// emitted to logcat target `planar_timing`). Pair with
/// `translator::planar_tracker::PER_FRAME_TIMING_LOG` (separate crate)
/// to silence the corresponding `guided: ...` / `brute: ...` lines.
#[cfg(feature = "planar-tracker")]
pub(crate) const PER_FRAME_TIMING_LOG: bool = false;

/// Threshold in ms below which we suppress lock-timing logs even when
/// `LIVE_PIPELINE_DIAG` is on — keeps the diagnostic mode focused on
/// actually-slow operations.
#[cfg(feature = "planar-tracker")]
const LOCK_LOG_THRESHOLD_MS: f64 = 3.0;

#[cfg(feature = "planar-tracker")]
fn log_lock_timing(label: &str, wait: std::time::Duration, hold: std::time::Duration) {
    if !LIVE_PIPELINE_DIAG {
        return;
    }
    let wait_ms = wait.as_secs_f64() * 1_000.0;
    let hold_ms = hold.as_secs_f64() * 1_000.0;
    if wait_ms > LOCK_LOG_THRESHOLD_MS || hold_ms > LOCK_LOG_THRESHOLD_MS {
        log::debug!(
            "[lock] {label}: wait={:.1}ms hold={:.1}ms",
            wait_ms,
            hold_ms,
        );
    }
}

use thiserror::Error;
use translator::{
    CatalogSnapshot, Feature, FsPackInstallChecker, TranslatorError, TranslatorErrorKind,
    TranslatorSession, language_rows_in_snapshot, sample_overlay_colors,
};

#[derive(Debug, Error, uniffi::Error)]
pub enum CatalogOpenError {
    #[error("failed to parse any catalog")]
    ParseFailed,
}

#[derive(Debug, Error, uniffi::Error)]
pub enum CatalogError {
    #[error("{reason}")]
    MissingAsset { reason: String },
    #[error("{reason}")]
    Other { reason: String },
    /// User-initiated cancel. The Kotlin side should treat this as a
    /// silent stop, not as a translation failure.
    #[error("cancelled")]
    Cancelled,
}

impl From<TranslatorError> for CatalogError {
    fn from(err: TranslatorError) -> Self {
        match err.kind {
            TranslatorErrorKind::MissingAsset => Self::MissingAsset {
                reason: err.message,
            },
            _ => Self::Other {
                reason: err.message,
            },
        }
    }
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum DocumentProgressEvent {
    Preparing,
    /// PDF-only: emitted once after inventory, before any pass starts.
    /// Lets the UI render three labelled progress lines (pages /
    /// images / raster pages) with their totals up-front, instead of
    /// one bar that resets when each phase ends. Subsequent
    /// `Translating` events update the matching counter via `unit`:
    ///   - "page" → text-translation pages
    ///   - "image" → image-XObject pass
    ///   - "raster_page" → page-raster overlay pass
    /// `raster_pages` is an upper bound; the raster pass refines it
    /// once the image pass has narrowed the actual set, by reporting
    /// the smaller `total` in its ticks.
    PdfPlan {
        text_pages: u32,
        image_xobjects: u32,
        raster_pages: u32,
    },
    Translating {
        current: u32,
        total: u32,
        unit: String,
    },
    Writing,
}


#[uniffi::export(with_foreign)]
pub trait DocumentProgressSink: Send + Sync {
    fn on_progress(&self, event: DocumentProgressEvent);
    fn is_cancelled(&self) -> bool;
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryGlossRecord {
    pub gloss_lines: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionarySenseRecord {
    pub pos: String,
    pub glosses: Vec<DictionaryGlossRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryWordEntryRecord {
    pub senses: Vec<DictionarySenseRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryWordRecord {
    pub word: String,
    pub tag: i32,
    pub entries: Vec<DictionaryWordEntryRecord>,
    pub sounds: Option<String>,
    pub hyphenations: Vec<String>,
    pub redirects: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CatalogFileRecord {
    pub name: String,
    pub size_bytes: u64,
    pub install_path: String,
    pub url: String,
}

impl From<translator::catalog::AssetFileV2> for CatalogFileRecord {
    fn from(file: translator::catalog::AssetFileV2) -> Self {
        Self {
            name: file.name,
            size_bytes: file.size_bytes,
            install_path: file.install_path,
            url: file.url,
        }
    }
}

#[cfg(feature = "dictionary")]
fn map_dictionary_word(word: translator::tarkka::WordWithTaggedEntries) -> DictionaryWordRecord {
    DictionaryWordRecord {
        word: word.word,
        tag: word.tag as i32,
        entries: word
            .entries
            .into_iter()
            .map(|entry| DictionaryWordEntryRecord {
                senses: entry
                    .senses
                    .into_iter()
                    .map(|sense| DictionarySenseRecord {
                        pos: sense.pos.to_string(),
                        glosses: sense
                            .glosses
                            .into_iter()
                            .map(|gloss| DictionaryGlossRecord {
                                gloss_lines: gloss.gloss_lines,
                            })
                            .collect(),
                    })
                    .collect(),
            })
            .collect(),
        sounds: word.sounds,
        hyphenations: word.hyphenations,
        redirects: word.redirects,
    }
}

#[uniffi::export]
fn sample_overlay_colors_rgba(
    rgba_bytes: Vec<u8>,
    width: u32,
    height: u32,
    bounds: translator::Rect,
    background_mode: translator::BackgroundMode,
    word_rects: Option<Vec<translator::Rect>>,
) -> Option<translator::OverlayColors> {
    sample_overlay_colors(
        &rgba_bytes,
        width,
        height,
        bounds,
        background_mode,
        word_rects.as_deref(),
    )
    .ok()
}

/// Rasterize the translated overlay onto `prepared`. Returns the new RGBA
/// buffer (same dimensions as `prepared.width` x `prepared.height`).
///
/// `language` is a BCP-47 hint for font selection (CJK regional variants);
/// `min_font_size_px` floors the fit-loop's shrink (caller should pass the
/// equivalent of the old Kotlin `minTextSize`, typically 8.0).
#[cfg(feature = "image-render")]
#[uniffi::export]
fn render_translated_overlay(
    prepared: translator::PreparedImageOverlay,
    language: String,
    min_font_size_px: f32,
) -> Result<Vec<u8>, CatalogError> {
    use translator::image_render::{RenderOptions, render_overlay};
    let opts = RenderOptions {
        language,
        min_font_size_px,
    };
    let provider = crate::android_font_provider::AndroidFontProvider;
    render_overlay(&prepared, &provider, &opts).map_err(|e| CatalogError::Other {
        reason: e.to_string(),
    })
}

fn document_extension(path: &str) -> String {
    Path::new(path)
        .extension()
        .and_then(|ext| ext.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
}

fn map_available_language_codes(codes: Vec<String>) -> Vec<translator::LanguageCode> {
    codes
        .into_iter()
        .map(translator::LanguageCode::from)
        .collect()
}

fn translate_document_path_impl(
    session: &TranslatorSession,
    input_path: String,
    output_path: String,
    forced_source_code: Option<String>,
    target_code: String,
    available_language_codes: Vec<String>,
    translate_pdf_images: bool,
    mut on_progress: impl FnMut(DocumentProgressEvent),
    is_cancelled: impl Fn() -> bool + Send + Sync,
) -> Result<String, CatalogError> {
    let check_cancelled = || {
        if is_cancelled() {
            Err(CatalogError::Cancelled)
        } else {
            Ok(())
        }
    };
    check_cancelled()?;
    on_progress(DocumentProgressEvent::Preparing);
    check_cancelled()?;
    let extension = document_extension(&input_path);
    let available = map_available_language_codes(available_language_codes);
    let input_bytes = fs::read(&input_path).map_err(|error| CatalogError::Other {
        reason: format!("failed to read document: {error}"),
    })?;
    check_cancelled()?;

    let output_bytes = match extension.as_str() {
        "txt" => {
            let source_code = forced_source_code
                .as_deref()
                .ok_or_else(|| CatalogError::Other {
                    reason: "source language is required for text documents".to_string(),
                })?;
            let text = String::from_utf8(input_bytes).map_err(|error| CatalogError::Other {
                reason: format!("text document is not UTF-8: {error}"),
            })?;
            on_progress(DocumentProgressEvent::Translating {
                current: 0,
                total: 1,
                unit: "block".to_string(),
            });
            check_cancelled()?;
            let translated = session
                .translate_text(source_code, &target_code, &text)
                .map_err(CatalogError::from)?;
            check_cancelled()?;
            on_progress(DocumentProgressEvent::Translating {
                current: 1,
                total: 1,
                unit: "block".to_string(),
            });
            check_cancelled()?;
            translated.into_bytes()
        }
        "odt" => {
            #[cfg(feature = "odt")]
            {
                translator::odt::translate_odt_with_progress(
                    session,
                    &input_bytes,
                    forced_source_code.as_deref(),
                    &target_code,
                    &available,
                    |progress| {
                        if is_cancelled() {
                            return Err(translator::odt::OdtTranslateError::Cancelled);
                        }
                        let translator::odt::OdtTranslateProgress::TranslatingBlock {
                            current,
                            total,
                        } = progress;
                        on_progress(DocumentProgressEvent::Translating {
                            current: current as u32,
                            total: total as u32,
                            unit: "block".to_string(),
                        });
                        if is_cancelled() {
                            Err(translator::odt::OdtTranslateError::Cancelled)
                        } else {
                            Ok(())
                        }
                    },
                )
                .map_err(|error| match error {
                    translator::odt::OdtTranslateError::Cancelled => CatalogError::Cancelled,
                    other => CatalogError::Other {
                        reason: format!("failed to translate ODT: {other}"),
                    },
                })?
            }
            #[cfg(not(feature = "odt"))]
            {
                let _ = (forced_source_code, target_code, available);
                return Err(CatalogError::Other {
                    reason: "odt feature disabled".to_string(),
                });
            }
        }
        "pdf" => {
            #[cfg(feature = "pdf")]
            {
                // Pipeline order: text translation FIRST, then image
                // XObject translation, then page-raster overlay last.
                //
                // Why this order: each later pass depends on the input
                // it sees being free of *its own* output. Text
                // translation runs surgery on extractable PDF text;
                // running it after the overlay pass would re-process
                // the overlay's `Tj` operators, embed duplicate fonts,
                // and bloat the output. Image-XObject translation
                // re-encodes bitmaps; running it after page-raster
                // overlay would bake redundant translated text into
                // images that the overlay also covers. Keeping text →
                // XObject → overlay means each pass sees only the
                // upstream content it's designed for.
                #[cfg(feature = "pdf-image-translate")]
                let overlay_pages: std::collections::HashSet<usize> =
                    if translate_pdf_images && forced_source_code.is_some() {
                        translator::pdf_image_translate::log_page_inventory(&input_bytes);
                        let pages = translator::pdf_image_translate::pages_without_extractable_text(
                            &input_bytes,
                        );
                        // Emit a PdfPlan up-front so the UI can render
                        // three labelled progress bars (text pages /
                        // images / raster pages) with totals known
                        // before any pass starts. raster_pages here is
                        // the upper bound (pages with no extractable
                        // text); the raster pass refines its `total`
                        // down to whatever survives the image pass.
                        if let Some(inv) =
                            translator::pdf_image_translate::pdf_translation_inventory(&input_bytes)
                        {
                            on_progress(DocumentProgressEvent::PdfPlan {
                                text_pages: inv.total_pages,
                                image_xobjects: inv.image_xobjects,
                                raster_pages: inv.raster_pages,
                            });
                        }
                        pages
                    } else {
                        std::collections::HashSet::new()
                    };

                // Pass 1: text translation over the original bytes.
                let translations_result = translator::pdf_translate::translate_pdf_with_progress(
                    session,
                    &input_bytes,
                    forced_source_code.as_deref(),
                    &target_code,
                    &available,
                    |progress| {
                        if is_cancelled() {
                            return Err(translator::pdf_translate::PdfTranslateError::Cancelled);
                        }
                        let translator::pdf_translate::PdfTranslateProgress::TranslatingPage {
                            current,
                            total,
                        } = progress;
                        on_progress(DocumentProgressEvent::Translating {
                            current: current as u32,
                            total: total as u32,
                            unit: "page".to_string(),
                        });
                        if is_cancelled() {
                            Err(translator::pdf_translate::PdfTranslateError::Cancelled)
                        } else {
                            Ok(())
                        }
                    },
                );
                // If no native text was found but image translation
                // can still add overlay content, proceed with an empty
                // translation set so the writer round-trips the bytes.
                let translations = match translations_result {
                    Ok(t) => t,
                    Err(translator::pdf_translate::PdfTranslateError::NoTextFound) => Vec::new(),
                    Err(translator::pdf_translate::PdfTranslateError::Cancelled) => {
                        return Err(CatalogError::Cancelled);
                    }
                    Err(error) => {
                        return Err(CatalogError::Other {
                            reason: format!("failed to translate PDF: {error}"),
                        });
                    }
                };
                // No Writing event here: we still have image/page-raster
                // passes to do after `write_translated_pdf` produces the
                // post-text bytes. The single Writing event lives at the
                // end of `translate_document_path_impl`, right before
                // `fs::write` actually persists the result.
                let after_text = translator::pdf_write::write_translated_pdf(
                    &input_bytes,
                    &translations,
                    &crate::android_font_provider::AndroidFontProvider,
                )
                .map_err(|error| CatalogError::Other {
                    reason: format!("failed to write PDF: {error}"),
                })?;

                // Passes 2 & 3 (only if image translation requested):
                // image-XObject translation, then page-raster overlay.
                #[cfg(feature = "pdf-image-translate")]
                {
                    if translate_pdf_images && forced_source_code.is_some() {
                        let src = forced_source_code.as_deref().unwrap_or("");
                        let xobject_progress = |current: usize, total: usize| {
                            on_progress(DocumentProgressEvent::Translating {
                                current: current as u32,
                                total: total as u32,
                                unit: "image".to_string(),
                            });
                        };
                        let xobject_output =
                            translator::pdf_image_translate::translate_pdf_images_in_place(
                                &after_text,
                                session,
                                src,
                                &target_code,
                                &crate::android_font_provider::AndroidFontProvider,
                                &overlay_pages,
                                &is_cancelled,
                                xobject_progress,
                            )
                            .map_err(|error| CatalogError::Other {
                                reason: format!("failed to translate PDF images: {error}"),
                            })?;
                        check_cancelled()?;
                        // Pages whose visible content was translated via
                        // image XObjects don't need a page-raster overlay
                        // on top — that would just OCR the freshly
                        // translated bitmap and stamp the same Spanish
                        // text again.
                        let raster_pages: std::collections::HashSet<usize> = overlay_pages
                            .difference(&xobject_output.translated_pages)
                            .copied()
                            .collect();
                        let page_progress = |current: usize, total: usize| {
                            on_progress(DocumentProgressEvent::Translating {
                                current: current as u32,
                                total: total as u32,
                                unit: "raster_page".to_string(),
                            });
                        };
                        let final_bytes =
                            translator::pdf_image_translate::translate_pdf_pages_as_raster_in_place(
                                &xobject_output.bytes,
                                session,
                                src,
                                &target_code,
                                &crate::android_font_provider::AndroidFontProvider,
                                &raster_pages,
                                &is_cancelled,
                                page_progress,
                            )
                            .map_err(|error| CatalogError::Other {
                                reason: format!("failed to rasterize PDF pages: {error}"),
                            })?;
                        check_cancelled()?;
                        final_bytes
                    } else {
                        let _ = (translate_pdf_images, &overlay_pages);
                        after_text
                    }
                }
                #[cfg(not(feature = "pdf-image-translate"))]
                {
                    let _ = translate_pdf_images;
                    after_text
                }
            }
            #[cfg(not(feature = "pdf"))]
            {
                let _ = (forced_source_code, target_code, available);
                return Err(CatalogError::Other {
                    reason: "pdf feature disabled".to_string(),
                });
            }
        }
        _ => {
            return Err(CatalogError::Other {
                reason: format!("unsupported document type: {extension}"),
            });
        }
    };

    check_cancelled()?;
    on_progress(DocumentProgressEvent::Writing);
    check_cancelled()?;
    fs::write(&output_path, output_bytes).map_err(|error| CatalogError::Other {
        reason: format!("failed to write translated document: {error}"),
    })?;
    Ok(output_path)
}

#[derive(uniffi::Object)]
pub struct CatalogHandle {
    session: TranslatorSession,
}

impl CatalogHandle {
    fn snapshot(&self) -> Arc<CatalogSnapshot> {
        self.session.snapshot()
    }
}

#[uniffi::export]
impl CatalogHandle {
    #[uniffi::constructor]
    fn open(
        bundled_json: String,
        disk_json: Option<String>,
        base_dir: String,
    ) -> Result<Arc<Self>, CatalogOpenError> {
        crate::init_logging();
        let checker = FsPackInstallChecker::new(&base_dir);
        let session =
            TranslatorSession::open(&bundled_json, disk_json.as_deref(), base_dir, &checker)
                .map_err(|_| CatalogOpenError::ParseFailed)?;
        Ok(Arc::new(CatalogHandle { session }))
    }

    fn format_version(&self) -> i32 {
        self.snapshot().catalog.format_version
    }

    fn generated_at(&self) -> i64 {
        self.snapshot().catalog.generated_at
    }

    fn dictionary_version(&self) -> i32 {
        self.snapshot().catalog.dictionary_version
    }

    fn language_rows(&self) -> Vec<translator::LanguageAvailabilityRow> {
        language_rows_in_snapshot(&self.snapshot())
    }

    fn dictionary_info(&self, dictionary_code: String) -> Option<translator::DictionaryInfo> {
        self.snapshot()
            .catalog
            .dictionary_info(&translator::DictionaryCode::from(dictionary_code))
    }

    fn support_files_by_kind(&self, support_kind: String) -> Vec<CatalogFileRecord> {
        self.snapshot()
            .catalog
            .support_files_by_kind(&support_kind)
            .into_iter()
            .map(Into::into)
            .collect()
    }

    fn lookup_dictionary(
        &self,
        language_code: String,
        word: String,
    ) -> Result<Option<DictionaryWordRecord>, CatalogError> {
        #[cfg(not(feature = "dictionary"))]
        {
            let _ = (language_code, word);
            return Ok(None);
        }

        #[cfg(feature = "dictionary")]
        {
            self.session
                .lookup_dictionary(&language_code, &word)
                .map(|opt| opt.map(map_dictionary_word))
                .map_err(CatalogError::from)
        }
    }

    fn has_tts_voices(&self, language_code: String) -> bool {
        self.snapshot()
            .catalog
            .has_tts_voices(&translator::LanguageCode::from(language_code))
    }

    fn installed_ocr_engines(&self, language_code: String) -> Vec<String> {
        translator::installed_ocr_engines_for_language(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
    }

    fn available_ocr_engines(&self, language_code: String) -> Vec<String> {
        translator::available_ocr_engines_for_language(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
    }

    fn plan_ocr_engine_download(
        &self,
        language_code: String,
        engine: String,
    ) -> Option<translator::DownloadPlan> {
        translator::plan_ocr_engine_download(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
            &engine,
        )
    }

    fn plan_ocr_engine_downloads(
        &self,
        language_codes: Vec<String>,
        engine: String,
    ) -> translator::DownloadPlan {
        let language_codes = language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        translator::plan_ocr_engine_downloads(&self.snapshot(), &language_codes, &engine)
    }

    fn tts_sample_text(&self, language_code: String) -> Option<String> {
        self.snapshot()
            .catalog
            .tts_sample_text(&translator::LanguageCode::from(language_code))
    }

    fn tts_voice_picker_regions(
        &self,
        language_code: String,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        self.snapshot()
            .catalog
            .tts_voice_picker_regions(&translator::LanguageCode::from(language_code))
    }

    fn installed_tts_voice_picker_regions(
        &self,
        language_code: String,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        translator::installed_tts_voice_picker_regions(
            &self.snapshot(),
            &translator::LanguageCode::from(language_code),
        )
    }

    fn can_swap_languages(&self, from_code: String, to_code: String) -> bool {
        self.snapshot().catalog.can_swap_languages(
            &translator::LanguageCode::from(from_code),
            &translator::LanguageCode::from(to_code),
        )
    }

    fn can_translate(&self, from_code: String, to_code: String) -> bool {
        self.snapshot().can_translate(
            &translator::LanguageCode::from(from_code),
            &translator::LanguageCode::from(to_code),
        )
    }

    fn warm_translation_models(&self, from_code: String, to_code: String) -> bool {
        self.session.warm(&from_code, &to_code).is_ok()
    }

    fn translate_text(
        &self,
        from_code: String,
        to_code: String,
        text: String,
    ) -> Result<String, CatalogError> {
        self.session
            .translate_text(&from_code, &to_code, &text)
            .map_err(CatalogError::from)
    }

    fn translate_html_fragments(
        &self,
        from_code: String,
        to_code: String,
        fragments: Vec<String>,
    ) -> Result<Vec<String>, CatalogError> {
        self.session
            .translate_html_fragments(&from_code, &to_code, &fragments)
            .map_err(CatalogError::from)
    }

    fn translate_mixed_texts(
        &self,
        inputs: Vec<String>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
    ) -> translator::MixedTextTranslationResult {
        let available = available_language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        self.session
            .translate_mixed_texts(
                &inputs,
                forced_source_code.as_deref(),
                &target_code,
                &available,
            )
            .unwrap_or_else(|_| translator::MixedTextTranslationResult::default())
    }

    fn translate_structured_fragments(
        &self,
        fragments: Vec<translator::StructuredStyledFragment>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        screenshot: Option<translator::OverlayScreenshot>,
        background_mode: translator::BackgroundMode,
    ) -> translator::StructuredTranslationResult {
        let available = available_language_codes
            .into_iter()
            .map(translator::LanguageCode::from)
            .collect::<Vec<_>>();
        self.session
            .translate_structured_fragments(
                &fragments,
                forced_source_code.as_deref(),
                &target_code,
                &available,
                screenshot.as_ref(),
                background_mode,
            )
            .unwrap_or_else(|error| translator::StructuredTranslationResult {
                blocks: Vec::new(),
                nothing_reason: None,
                error_message: Some(error.message),
            })
    }

    fn translate_image_plan(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        max_image_size: u32,
        source_selection: translator::OcrSourceSelection,
        target_code: String,
        min_confidence: u32,
        reading_order: translator::ReadingOrder,
        background_mode: translator::BackgroundMode,
        preferred_engine: translator::PreferredOcrEngine,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        #[cfg(any(feature = "tesseract", feature = "ppocr"))]
        {
            return self
                .session
                .translate_image_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    max_image_size,
                    source_selection,
                    &target_code,
                    min_confidence,
                    reading_order,
                    background_mode,
                    preferred_engine,
                )
                .map_err(CatalogError::from);
        }
        #[cfg(not(any(feature = "tesseract", feature = "ppocr")))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                max_image_size,
                source_selection,
                target_code,
                min_confidence,
                reading_order,
                background_mode,
                preferred_engine,
            );
            Err(CatalogError::Other {
                reason: "OCR feature disabled".to_string(),
            })
        }
    }

    fn retranslate_image_plan(
        &self,
        prepared: translator::PreparedImageOverlay,
        source_code: String,
        target_code: String,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        self.session
            .retranslate_prepared_overlay(prepared, &source_code, &target_code)
            .map_err(CatalogError::from)
    }

    fn translate_document_path(
        &self,
        input_path: String,
        output_path: String,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        translate_pdf_images: bool,
    ) -> Result<String, CatalogError> {
        translate_document_path_impl(
            &self.session,
            input_path,
            output_path,
            forced_source_code,
            target_code,
            available_language_codes,
            translate_pdf_images,
            |_| {},
            || false,
        )
    }

    fn translate_document_path_with_progress(
        &self,
        input_path: String,
        output_path: String,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        translate_pdf_images: bool,
        progress: Arc<dyn DocumentProgressSink>,
    ) -> Result<String, CatalogError> {
        translate_document_path_impl(
            &self.session,
            input_path,
            output_path,
            forced_source_code,
            target_code,
            available_language_codes,
            translate_pdf_images,
            |event| progress.on_progress(event),
            || progress.is_cancelled(),
        )
    }

    fn plan_download(
        &self,
        language_code: String,
        feature: Feature,
        selected_tts_pack_id: Option<String>,
    ) -> Option<translator::DownloadPlan> {
        self.session
            .plan_download(&language_code, feature, selected_tts_pack_id.as_deref())
    }

    fn plan_support_download_by_kind(
        &self,
        support_kind: String,
    ) -> Option<translator::DownloadPlan> {
        self.session.plan_support_download_by_kind(&support_kind)
    }

    fn prepare_delete(&self, language_code: String, feature: Feature) -> translator::DeletePlan {
        self.session.prepare_delete(&language_code, feature)
    }

    fn prepare_delete_support_by_kind(&self, support_kind: String) -> translator::DeletePlan {
        self.session.prepare_delete_support_by_kind(&support_kind)
    }

    fn prepare_delete_superseded_tts(
        &self,
        language_code: String,
        selected_pack_id: String,
    ) -> translator::DeletePlan {
        self.session
            .prepare_delete_superseded_tts(&language_code, &selected_pack_id)
    }

    fn prepare_delete_tts_pack(&self, pack_id: String) -> translator::DeletePlan {
        self.session.prepare_delete_tts_pack(&pack_id)
    }

    fn size_bytes(&self, language_code: String, feature: Feature) -> u64 {
        self.session.size_bytes(&language_code, feature)
    }

    fn support_size_bytes_by_kind(&self, support_kind: String) -> u64 {
        self.session.support_size_bytes_by_kind(&support_kind)
    }

    fn default_tts_pack_id(&self, language_code: String) -> Option<String> {
        self.snapshot()
            .catalog
            .default_tts_pack_id_for_language(&translator::LanguageCode::from(language_code))
    }

    fn available_tts_voices(&self, language_code: String) -> Vec<translator::TtsVoiceOption> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .available_tts_voices(&language_code)
                .unwrap_or_default();
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn plan_speech_chunks(
        &self,
        language_code: String,
        text: String,
        pack_id: Option<String>,
    ) -> Vec<translator::SpeechChunk> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .plan_speech_chunks(&language_code, &text, pack_id.as_deref())
                .unwrap_or_default();
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text, pack_id);
            Vec::new()
        }
    }

    fn synthesize_speech_pcm(
        &self,
        language_code: String,
        text: String,
        speech_speed: f32,
        voice_name: Option<String>,
        is_phonemes: bool,
        pack_id: Option<String>,
    ) -> Result<translator::PcmAudio, CatalogError> {
        #[cfg(feature = "tts")]
        {
            return self
                .session
                .synthesize_pcm(
                    &language_code,
                    &text,
                    speech_speed,
                    voice_name.as_deref(),
                    is_phonemes,
                    pack_id.as_deref(),
                )
                .map_err(CatalogError::from);
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (
                language_code,
                text,
                speech_speed,
                voice_name,
                is_phonemes,
                pack_id,
            );
            Err(CatalogError::Other {
                reason: "tts feature disabled".to_string(),
            })
        }
    }

    fn installed_tts_voices(&self, language_code: String) -> Vec<translator::InstalledTtsPack> {
        #[cfg(feature = "tts")]
        {
            return self.session.installed_tts_voices(&language_code);
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn detect_document_quad(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
    ) -> Result<Option<translator::doc_align::DocumentDetection>, CatalogError> {
        #[cfg(feature = "doc-align")]
        {
            return self
                .session
                .detect_document_quad(&rgba_bytes, width, height)
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "doc-align"))]
        {
            let _ = (rgba_bytes, width, height);
            Err(CatalogError::Other {
                reason: "doc-align feature disabled".to_string(),
            })
        }
    }

    fn warp_document_rgba(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        quad: translator::doc_align::DocumentQuad,
        out_width: Option<u32>,
        out_height: Option<u32>,
        postprocess: bool,
    ) -> Result<translator::doc_align::WarpedImageRgba, CatalogError> {
        #[cfg(feature = "doc-align")]
        {
            return self
                .session
                .warp_document_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    &quad,
                    out_width,
                    out_height,
                    postprocess,
                )
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "doc-align"))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                quad,
                out_width,
                out_height,
                postprocess,
            );
            Err(CatalogError::Other {
                reason: "doc-align feature disabled".to_string(),
            })
        }
    }

    /// Allocate a Rust-side frame buffer with a pre-sized capacity. The buffer is
    /// reusable: feed bytes in via either [`FrameHandle::reset_via_uniffi`] or the
    /// `LiveFrameJni.writeFrom` JNI fast-path, then call detect/recognize. Pool
    /// the handle on the Kotlin side to amortise the Rust allocation across many
    /// frames.
    #[cfg(feature = "ppocr")]
    fn make_frame_buffer(&self, capacity: u32) -> Arc<FrameHandle> {
        Arc::new(FrameHandle::new(capacity as usize))
    }

    /// Back-compat one-shot frame creation. Allocates a fresh buffer and writes
    /// bytes into it via the standard uniffi marshalling. Prefer the
    /// `make_frame_buffer` + reset flow for performance.
    #[cfg(feature = "ppocr")]
    fn make_frame(
        &self,
        rgba: Vec<u8>,
        width: u32,
        height: u32,
        rotation_degrees: i32,
    ) -> Arc<FrameHandle> {
        let handle = FrameHandle::new(rgba.len());
        handle.reset_via_uniffi_inner(rgba, width, height, rotation_degrees);
        Arc::new(handle)
    }

    /// Detect in a previously-allocated `FrameHandle`. The first call for a given
    /// display crop region builds the cropped + rotated derived images and caches
    /// them inside the handle; subsequent `recognize_in_frame` calls for the same
    /// crop will reuse that cache.
    ///
    /// Returned boxes are in the **full-crop coord space** (display-orient, with
    /// (0, 0) at top-left of the crop region), already scaled up from the
    /// downscaled detection image.
    #[cfg(feature = "ppocr")]
    fn detect_text_in_frame(
        &self,
        frame: Arc<FrameHandle>,
        crop: translator::Rect,
        det_max_pixels: u32,
    ) -> Result<Vec<translator::DetectedTextBox>, CatalogError> {
        let mut state = frame.state.lock().map_err(|_| poisoned())?;
        ensure_oriented_with_rgb_locked(&mut state, crop, det_max_pixels)?;
        let oriented = state
            .cached
            .as_ref()
            .expect("ensure_oriented populated cache");
        let raw = self
            .session
            .detect_text_in_oriented_image(oriented)
            .map_err(CatalogError::from)?;
        let scale = oriented.det_to_full_scale;
        let rgb = oriented.rgb.as_ref().expect("with_rgb path");
        let max_w = rgb.width();
        let max_h = rgb.height();
        let scaled: Vec<translator::DetectedTextBox> = raw
            .into_iter()
            .map(|b| scale_detected_box(b, scale, max_w, max_h))
            .collect();
        Ok(scaled)
    }

    /// Recognize text in a previously-allocated `FrameHandle`. The same crop must
    /// have been previously passed to `detect_text_in_frame` (which built the
    /// cached oriented image used here). Boxes must be in full-crop coord space.
    #[cfg(feature = "ppocr")]
    fn recognize_in_frame(
        &self,
        frame: Arc<FrameHandle>,
        crop: translator::Rect,
        boxes: Vec<translator::DetectedTextBox>,
        source_selection: translator::OcrSourceSelection,
    ) -> Result<Vec<translator::RecognizedTextLine>, CatalogError> {
        let state = frame.state.lock().map_err(|_| poisoned())?;
        let oriented = state
            .cached
            .as_ref()
            .filter(|oi| oi.display_crop == crop)
            .ok_or_else(|| CatalogError::Other {
                reason: "recognize_in_frame called without prior detect_text_in_frame for this crop"
                    .to_string(),
            })?;
        self.session
            .recognize_in_oriented_image(oriented, &boxes, source_selection)
            .map_err(CatalogError::from)
    }
}

/// Rust-side live-OCR frame buffer. Pool of these on the Kotlin side keeps the
/// per-frame allocation at zero; the underlying `Vec<u8>` is written into in
/// place each frame (either via the standard uniffi marshalling path or — the
/// fast path — directly from a DirectByteBuffer through a JNI shim). The
/// cached oriented image is rebuilt whenever the crop region changes.
#[derive(uniffi::Object)]
pub struct FrameHandle {
    pub(crate) state: std::sync::Mutex<FrameState>,
}

pub(crate) struct FrameState {
    /// Owned RGBA bytes. Populated by the legacy `writeFrom` JNI
    /// path (memcpy from camera buffer), the `reset_via_uniffi`
    /// fallback, OR by [`Self::materialize_owned`] which copies from
    /// `external_rgba` so async pipelines can drop the camera buffer.
    pub rgba: Vec<u8>,
    /// Borrowed RGBA bytes from a Kotlin-held DirectByteBuffer
    /// (typically a CameraX ImageProxy). Set by the
    /// `setExternalBuffer` JNI path for zero-copy ingestion on the
    /// per-frame fast path. Caller (Kotlin) MUST keep the backing
    /// ImageProxy alive until either [`Self::materialize_owned`] or
    /// the JNI `clearExternalBuffer` is called — accessing the slice
    /// after the ImageProxy closes is use-after-free.
    pub external_rgba: Option<ExternalRgba>,
    pub width: u32,
    pub height: u32,
    pub rotation_degrees: i32,
    pub cached: Option<translator::live_frame::OrientedImage>,
    /// Tracker-side gray pyramid, *always* built on the full-display
    /// rect. The tracker needs every feature the sensor produces —
    /// including ones just outside the SurfaceView's FILL_CENTER
    /// visible region — so its anchor stays robust under small motion
    /// that would otherwise push edge features out of frame. Built
    /// separately from `cached` (which is sized to the visible region
    /// for OCR) because the OCR pipeline benefits from detect's
    /// pixel-count-linear cost being scoped to what the user can see.
    pub cached_tracker: Option<translator::live_frame::OrientedImage>,
}

/// Pointer + length into a Kotlin-held DirectByteBuffer. Marked
/// `Send`/`Sync` because the wrapping `Mutex<FrameState>` is held
/// across threads in normal use — the lifetime promise is enforced
/// by Kotlin code, not by Rust types.
pub(crate) struct ExternalRgba {
    pub ptr: *const u8,
    pub len: usize,
}
unsafe impl Send for ExternalRgba {}
unsafe impl Sync for ExternalRgba {}

impl FrameState {
    /// Return the active RGBA bytes — `external_rgba` when set
    /// (zero-copy from camera buffer), `rgba` otherwise (owned copy).
    pub(crate) fn rgba_bytes(&self) -> &[u8] {
        if let Some(ext) = &self.external_rgba {
            // SAFETY: Kotlin guarantees the backing ImageProxy is
            // alive for the duration of this borrow — see contract
            // on `external_rgba`.
            unsafe { std::slice::from_raw_parts(ext.ptr, ext.len) }
        } else {
            &self.rgba
        }
    }

    /// Memcpy `external_rgba` into the owned `rgba` Vec, then clear
    /// the external borrow. After this returns the FrameState's
    /// bytes are owned and the caller can safely close the camera
    /// ImageProxy. No-op when `external_rgba` is already None.
    pub(crate) fn materialize_owned(&mut self) {
        if let Some(ext) = self.external_rgba.take() {
            self.rgba.clear();
            self.rgba.reserve(ext.len);
            // SAFETY: caller has not yet closed the ImageProxy; the
            // source pointer is valid for `ext.len` bytes; the dest
            // Vec was just reserved with at least that capacity; src
            // and dst are disjoint (camera native memory vs Rust heap).
            unsafe {
                std::ptr::copy_nonoverlapping(ext.ptr, self.rgba.as_mut_ptr(), ext.len);
                self.rgba.set_len(ext.len);
            }
        }
    }
}

impl FrameHandle {
    fn new(initial_capacity: usize) -> Self {
        FrameHandle {
            state: std::sync::Mutex::new(FrameState {
                rgba: Vec::with_capacity(initial_capacity),
                external_rgba: None,
                width: 0,
                height: 0,
                rotation_degrees: 0,
                cached: None,
                cached_tracker: None,
            }),
        }
    }

    /// Exposes the inner state mutex to JNI shims (same crate). Not
    /// part of the uniffi-visible surface.
    pub(crate) fn state(&self) -> &std::sync::Mutex<FrameState> {
        &self.state
    }

    /// Address of this handle on the Rust heap. Used by the JNI fast-path —
    /// Kotlin passes this `u64` to a non-uniffi extern "system" fn which casts
    /// it back to `&FrameHandle`. The `Arc` keeps the address stable, so as long
    /// as Kotlin still holds the wrapper, this pointer is valid.
    fn raw_address(&self) -> u64 {
        self as *const FrameHandle as u64
    }

    fn reset_via_uniffi_inner(
        &self,
        rgba: Vec<u8>,
        width: u32,
        height: u32,
        rotation_degrees: i32,
    ) {
        let mut state = self.state.lock().expect("frame mutex poisoned");
        state.rgba = rgba;
        state.external_rgba = None;
        state.width = width;
        state.height = height;
        state.rotation_degrees = rotation_degrees;
        state.cached = None;
        state.cached_tracker = None;
    }
}

#[uniffi::export]
impl FrameHandle {
    /// Returns this handle's Rust-heap address as a `u64`. Pair with
    /// `LiveFrameJni.writeFrom(...)` for zero-JVM-copy byte transfer from a
    /// camera DirectByteBuffer into this buffer.
    fn raw_address_for_jni(&self) -> u64 {
        self.raw_address()
    }

    /// Fallback path that copies bytes in via uniffi's standard marshalling.
    /// Slower than the JNI shim (extra JVM ByteArray copy) but useful when the
    /// camera's plane isn't a DirectByteBuffer or row stride doesn't match.
    fn reset_via_uniffi(&self, rgba: Vec<u8>, width: u32, height: u32, rotation_degrees: i32) {
        self.reset_via_uniffi_inner(rgba, width, height, rotation_degrees);
    }
}





#[cfg(feature = "ppocr")]
/// Gray-only build path. Used by the per-frame planar-tracker step
/// which only reads `oriented.gray`. Reuses the cached oriented image
/// if its `display_crop` still matches — even if the cached one was
/// built with rgb, we keep it (downgrading would re-do the fused gray
/// pass unnecessarily).
/// Tracker-side ensure: build (and cache) a gray-only OrientedImage
/// sized to the *full display* into `state.cached_tracker`. The
/// per-frame planar tracker reads `state.cached_tracker.gray` so it
/// has every available feature, even those just outside the
/// SurfaceView's FILL_CENTER visible region. Cheap to rebuild because
/// it's gray-only (no rgb / rgb_det chains) and the typical full-frame
/// fused crop+rotate+luma pass is ~3 ms on phone.
fn ensure_tracker_oriented_locked(
    state: &mut FrameState,
    det_max_pixels: u32,
) -> Result<(), CatalogError> {
    let crop = full_display_rect(state);
    let needs_rebuild = match state.cached_tracker.as_ref() {
        Some(oi) => oi.display_crop != crop,
        None => true,
    };
    if needs_rebuild {
        let oi = translator::live_frame::OrientedImage::build(
            state.rgba_bytes(),
            state.width,
            state.height,
            state.rotation_degrees,
            crop,
            det_max_pixels,
        )
        .map_err(CatalogError::from)?;
        state.cached_tracker = Some(oi);
    }
    Ok(())
}

/// Full-display rect for the current frame state. Independent of
/// the visible-region crop the SurfaceView shows: the tracker is
/// always built from the full sensor area so it has every available
/// feature, even those just outside the user's framing — keeps the
/// anchor robust under small motion that would otherwise push edge
/// features out of frame. The OCR + compositor still use the
/// visible-region rect (passed separately as `display_crop`) for
/// box filtering and bitmap sizing.
fn full_display_rect(state: &FrameState) -> translator::Rect {
    let r = ((state.rotation_degrees % 360) + 360) % 360;
    let (w, h) = if r == 90 || r == 270 {
        (state.height, state.width)
    } else {
        (state.width, state.height)
    };
    translator::Rect {
        left: 0,
        top: 0,
        right: w,
        bottom: h,
    }
}

fn ensure_oriented_locked(
    state: &mut FrameState,
    display_crop: translator::Rect,
    det_max_pixels: u32,
) -> Result<(), CatalogError> {
    let needs_rebuild = match state.cached.as_ref() {
        Some(oi) => oi.display_crop != display_crop,
        None => true,
    };
    if needs_rebuild {
        let oi = translator::live_frame::OrientedImage::build(
            state.rgba_bytes(),
            state.width,
            state.height,
            state.rotation_degrees,
            display_crop,
            det_max_pixels,
        )
        .map_err(CatalogError::from)?;
        state.cached = Some(oi);
    }
    Ok(())
}

/// Eager build path with `rgb` + `rgb_det` populated, for acquire /
/// refresh / detect / recognize callers. If the cached frame was built
/// gray-only, rebuild it to materialise rgb. Otherwise reuse.
fn ensure_oriented_with_rgb_locked(
    state: &mut FrameState,
    display_crop: translator::Rect,
    det_max_pixels: u32,
) -> Result<(), CatalogError> {
    let needs_rebuild = match state.cached.as_ref() {
        Some(oi) => oi.display_crop != display_crop || !oi.has_rgb(),
        None => true,
    };
    if needs_rebuild {
        let oi = translator::live_frame::OrientedImage::build_with_rgb(
            state.rgba_bytes(),
            state.width,
            state.height,
            state.rotation_degrees,
            display_crop,
            det_max_pixels,
        )
        .map_err(CatalogError::from)?;
        state.cached = Some(oi);
    }
    Ok(())
}

fn poisoned() -> CatalogError {
    CatalogError::Other {
        reason: "frame mutex poisoned".to_string(),
    }
}

/// Scale a `DetectedTextBox` from detector-image coords up to full-crop coords,
/// clamping inside the destination dimensions.
#[cfg(feature = "ppocr")]
fn scale_detected_box(
    b: translator::DetectedTextBox,
    scale: f32,
    max_w: u32,
    max_h: u32,
) -> translator::DetectedTextBox {
    let left = ((b.rect.left as f32) * scale).max(0.0) as u32;
    let top = ((b.rect.top as f32) * scale).max(0.0) as u32;
    let right = ((b.rect.right as f32) * scale).min(max_w as f32) as u32;
    let bottom = ((b.rect.bottom as f32) * scale).min(max_h as f32) as u32;
    let rect = translator::Rect {
        left: left.min(right.saturating_sub(1)),
        top: top.min(bottom.saturating_sub(1)),
        right: right.max(left + 1),
        bottom: bottom.max(top + 1),
    };
    let oriented = translator::ocr::OrientedRect {
        cx: b.oriented_box.cx * scale,
        cy: b.oriented_box.cy * scale,
        width: b.oriented_box.width * scale,
        height: b.oriented_box.height * scale,
        angle_radians: b.oriented_box.angle_radians,
    };
    let tight = translator::ocr::OrientedRect {
        cx: b.tight_box.cx * scale,
        cy: b.tight_box.cy * scale,
        width: b.tight_box.width * scale,
        height: b.tight_box.height * scale,
        angle_radians: b.tight_box.angle_radians,
    };
    let mut contour = Vec::with_capacity(b.contour.len());
    for v in &b.contour {
        contour.push(v * scale);
    }
    translator::DetectedTextBox {
        rect,
        oriented_box: oriented,
        tight_box: tight,
        contour,
        score: b.score,
    }
}

// =========================================================================
// Planar-surface tracker (Phase D wiring of FUTURE_PLANAR_TRACKER.md).
// Lives alongside the legacy LiveMotionTracker for incremental rollout; the
// Kotlin engine picks which to use behind a feature flag.
// =========================================================================

#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Enum)]
pub enum PlanarTrackerState {
    Idle,
    Acquiring,
    Locked,
    Lost,
}

#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Record)]
pub struct PlanarFrameResult {
    pub state: PlanarTrackerState,
    /// Active anchor id when state is Locked or last-known anchor when Lost.
    /// 0 when state is Idle or Acquiring.
    pub anchor_id: u64,
    /// 9-element row-major homography. Empty unless state is Locked.
    pub homography: Vec<f32>,
    /// True the first frame after a brand-new acquisition (Kotlin should
    /// run detect + recognise + translate). False on subsequent frames or
    /// when re-locking onto a cached anchor (skip OCR).
    pub is_new: bool,
    /// Inlier count for the locked fit; 0 otherwise.
    pub inliers: u32,
}

#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Record)]
pub struct PlanarTextRenderItem {
    pub id: u64,
    /// 8 floats = 4 corners (x,y), canonical-frame coords, TL/TR/BR/BL.
    pub quad: Vec<f32>,
    pub translated_text: String,
    pub source_text: String,
    /// BCP-47 of the target language (font-fallback hint).
    pub language: String,
    pub bg_argb: u32,
    pub fg_argb: u32,
    pub suggested_font_px: f32,
}

/// One per-item rasterized bitmap kept resident in the tracker. Each
/// item carries its own small RGBA region (sized to the item's visual
/// quad + a small AA pad) plus its surface-frame origin and a hash of
/// the content that produced the bitmap. `composite_frame` iterates
/// over the list and warps each item through `h_surface_to_viewport`.
///
/// Per-item storage replaces the previous one-big-union-bitmap design:
/// a dense page with 98 items wasted ~96 % of every raster (each
/// rec-batch update changed only 4 items but we re-rastered all 98).
/// Now `upsert_overlay_item` only re-rasters items whose content hash
/// changed; everything else stays resident.
///
#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Object)]
pub struct LivePlanarTracker {
    state: std::sync::Mutex<translator::planar_engine::LivePlanarEngine>,
    /// Per-item resident rasterized overlays for the composite
    /// pipeline. Each item is keyed by its stable id; `upsert_overlay_block`
    /// adds or replaces, `retain_overlay_items` drops anything outside
    /// a set. Persisted across many composite calls so dense pages only
    /// re-rasterize the handful of items whose text changed in the
    /// latest rec batch, not the whole set.
    /// Bumped on every `reset()` (tap-to-focus, language change, etc.).
    /// In-flight acquire pipelines pass the generation they captured at
    /// launch as a parameter; before each potentially-slow step
    /// (detect, recognize, translate) the pipeline checks whether the
    /// generation has moved on and bails if so.
    generation: std::sync::atomic::AtomicU64,
    /// Per-block id source. Used to be the only block-id generator;
    /// now block ids are derived from the sorted SurfaceLine ids
    /// (see `stable_block_id`) so unchanged blocks across acquires
    /// hit `upsert_overlay_block`'s content-hash cache. Kept around
    /// as a fallback / for any caller still on the legacy path.
    #[allow(dead_code)]
    next_entry_id: std::sync::atomic::AtomicU64,
    /// EMA-smoothed homography state, kept across consecutive
    /// `process_and_composite` calls. Holds the last smoothed H + the
    /// anchor it belongs to + the streak of LOST frames since the
    /// previous Locked. Lives in Rust so the per-frame call can do
    /// `tracker step → smooth → composite` in one trip instead of
    /// pinging back to Kotlin for the H math.
    smoothed_h: std::sync::Mutex<SmoothedHomography>,
    /// Most recent raw `H_root→view` for the currently-Locked anchor,
    /// stashed by `process_and_composite` on every Locked frame.
    /// Used as the "latest known H" for non-refresh purposes; the
    /// refresh worker reads `pending_refresh_target` instead so a
    /// snap-back / pan-induced anchor change between trigger-fire
    /// and worker-pickup doesn't make the worker project detections
    /// through the wrong anchor's H.
    last_root_to_view: std::sync::Mutex<Option<(u64, [f32; 9])>>,
    /// `H_root→view` at the last refresh we *fired*. The motion gate
    /// compares the current frame's H against this; on a held camera
    /// (RANSAC + handoff micro-drift only), the corner delta stays
    /// under [`MIN_REFRESH_DELTA_PX`] and we skip the refresh
    /// entirely — restoring the "rock solid still" feel that the
    /// pre-#28 era had.
    last_refresh_h: std::sync::Mutex<Option<[f32; 9]>>,
    /// Snapshot of `(anchor_id, H_root→view)` taken at the moment
    /// `process_and_composite` set `should_refresh_detect = true`.
    /// `run_refresh_pipeline` consumes from this slot. Pinning the
    /// pair at trigger time defends against the worker running
    /// asynchronously: by the time the worker thread picks up the
    /// frame, the detector thread may have processed many more
    /// frames and `last_root_to_view` may now point at a different
    /// anchor (engine snap-back, mid-pan re-lock). Using a stale
    /// `(anchor, H)` would project the worker's camera-coord
    /// detections into garbage surface coords — "boxes flying in
    /// the middle of the room" + `cache=0` because no surface lines
    /// match the projection.
    pending_refresh_target: std::sync::Mutex<Option<(u64, [f32; 9])>>,
    /// Output buffer produced by `composite_frame` (uniffi) and
    /// consumed by `Java_..._PlanarRenderJni_compositeInto` (JNI).
    /// Holds the fully-composited camera + overlay RGBA at display
    /// resolution. Vec<u8>-via-JNI-memcpy avoids uniffi marshalling for
    /// an 8 MB buffer per frame.
    /// Stashed by `process_and_composite` for the follow-up JNI
    /// `compositeIntoBuffer` call: the homography to warp the active
    /// anchor's overlay items by and the id of that active anchor.
    /// `None` between frames where the engine wasn't Locked + Idle and
    /// the previous-good H has been cleared. The JNI call reads this
    /// + `frame.state.rgba` + `session.overlay_items` and runs the
    /// composite directly into the caller-provided DirectByteBuffer,
    /// eliminating the previous intermediate `pending_display` Vec
    /// and the JNI memcpy that copied it into the buffer afterwards.
    pending_compose: std::sync::Mutex<Option<(u64, [f32; 9])>>,
    /// Color-matting results from the most recent acquire, keyed by
    /// the anchor those mats belong to. Indexed parallel to the
    /// acquire pipeline's `entries` (i.e. by detection order).
    /// `None` entries are detections where matting failed (small
    /// contour, sparse ink) — callers fall back to the legacy pill
    /// rendering for those. Cleared when an anchor's overlays go
    /// away.
    matted_strips: std::sync::Mutex<
        std::collections::HashMap<u64, Vec<Option<translator::color_matting::MattedStrip>>>,
    >,
    /// Cross-platform orchestration state. Owns the surface map
    /// (and, in subsequent phases, the engine, overlay store, etc.)
    /// so the desktop `surface_sim` binary and this Android wrapper
    /// share one codebase. Reset on tap-to-focus / language change.
    session: std::sync::Arc<translator::live_session::LiveSession>,
}

#[cfg(feature = "planar-tracker")]
#[uniffi::export]
impl LivePlanarTracker {
    #[uniffi::constructor]
    fn new() -> Arc<Self> {
        Arc::new(Self {
            state: std::sync::Mutex::new(translator::planar_engine::LivePlanarEngine::new(
                translator::planar_engine::EngineConfig::default(),
            )),
            smoothed_h: std::sync::Mutex::new(SmoothedHomography::default()),
            last_root_to_view: std::sync::Mutex::new(None),
            last_refresh_h: std::sync::Mutex::new(None),
            pending_refresh_target: std::sync::Mutex::new(None),
            generation: std::sync::atomic::AtomicU64::new(0),
            next_entry_id: std::sync::atomic::AtomicU64::new(1),
            pending_compose: std::sync::Mutex::new(None),
            matted_strips: std::sync::Mutex::new(std::collections::HashMap::new()),
            session: std::sync::Arc::new(translator::live_session::LiveSession::new()),
        })
    }

    /// Return this tracker's address on the Rust heap as a `u64`. Pair
    /// with [`PlanarRenderJni.renderInto`] — Kotlin passes this back as
    /// a `jlong`, the JNI shim casts it to `&LivePlanarTracker` and
    /// reads `pending_bitmap`. The `Arc` keeps the address stable as
    /// long as Kotlin holds the wrapper.
    fn raw_address_for_jni(&self) -> u64 {
        self as *const LivePlanarTracker as u64
    }

    /// Upsert the resident overlay item with id `id`. If the content
    /// (tight box + texts + language) matches what was already
    /// rasterized for this id, this is a no-op — we keep the cached
    /// bitmap. Otherwise we recompute the visual box, raster a fresh
    /// bitmap, and replace the slot.
    ///
    /// Kotlin's job is to call this whenever a new detection /
    /// recognition / translation result arrives. Kotlin does *not*
    /// know about visual-box inflation, font sizing, bitmap bounds,
    /// or hashing — Rust owns all of that.
    /// Upsert one translation block: a set of strips (each an oriented
    /// rect in surface coords) plus its source + translated text. The
    /// block is the universal overlay unit — a single-line label is a
    /// 1-strip block, a paragraph is N strips. The rasterizer reflows
    /// `translated_text` across the strips in reading order.
    ///
    /// Content-hashed on (strips + texts + language). If a previous
    /// upsert for the same `id` produced the same hash, this is a
    /// no-op — no re-raster, no slot write.
    /// Drop every resident overlay item. Compositor will draw a
    /// camera-only frame after this.
    fn clear_overlay(&self) {
        self.session.clear_overlays();
    }

    /// Bump the generation counter, clear the engine state, drop all
    /// resident overlay items. Any in-flight `run_acquire_pipeline`
    /// will notice the generation move and bail out at its next
    /// check. Use this on tap-to-focus, language change, anchor reset.
    fn reset(&self) {
        self.generation.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        if let Ok(mut engine) = self.state.lock() {
            engine.clear();
        }
        if let Ok(mut sm) = self.smoothed_h.lock() {
            *sm = SmoothedHomography::default();
        }
        if let Ok(mut slot) = self.last_root_to_view.lock() {
            *slot = None;
        }
        if let Ok(mut slot) = self.last_refresh_h.lock() {
            *slot = None;
        }
        if let Ok(mut slot) = self.pending_refresh_target.lock() {
            *slot = None;
        }
        // Clears overlays + anchor states + refresh counters in one go.
        self.session.clear();
    }

    /// Read the current generation. Callers about to launch
    /// `run_acquire_pipeline` snapshot this value and pass it back; the
    /// pipeline aborts if the value has moved on by then.
    fn current_generation(&self) -> u64 {
        self.generation.load(std::sync::atomic::Ordering::SeqCst)
    }

    /// Whole acquire pipeline: detect text in the frame → acquire an
    /// anchor in the tracker engine → run recognition in batches of
    /// `REC_BATCH_SIZE` → translate each batch via
    /// `translate_mixed_texts` (one FFI-free Rust call, not N
    /// per-text calls) → upsert per-item overlays. Runs synchronously
    /// on the caller's thread (typically a Kotlin coroutine worker).
    ///
    /// At every potentially-slow boundary we check
    /// `self.generation == generation`; if not, we abort with
    /// `canceled = true`. That replaces the Kotlin
    /// `globalGeneration++` cancellation scaffolding.
    fn run_acquire_pipeline(
        &self,
        catalog: Arc<CatalogHandle>,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        anchor_padding_px: u32,
        timestamp_ns: u64,
        from_lang_code: String,
        to_lang_code: String,
        is_auto_source: bool,
        generation: u64,
    ) -> AcquirePipelineOutcome {
        let gen_check =
            || -> bool { self.generation.load(std::sync::atomic::Ordering::SeqCst) == generation };
        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }

        let t_overall = Instant::now();

        // ---- Detect ----
        let t_detect = Instant::now();
        let detected: Vec<translator::DetectedTextBox> = {
            let mut state = match frame.state.lock() {
                Ok(s) => s,
                Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
            };
            // OCR pipeline uses `cached` built on the *visible region*
            // — detect's cost is linear in pixel count and the user
            // can't see anything outside this crop anyway. The tracker
            // still gets the full-display gray via `cached_tracker`
            // (populated by the per-frame `process_and_composite`).
            if ensure_oriented_with_rgb_locked(&mut state, display_crop, det_max_pixels).is_err() {
                return AcquirePipelineOutcome::error("ensure_oriented failed");
            }
            let oriented = state
                .cached
                .as_ref()
                .expect("ensure_oriented filled cache");
            let raw = match catalog
                .session
                .detect_text_in_oriented_image(oriented)
            {
                Ok(r) => r,
                Err(e) => {
                    log::warn!("detect failed: {e:?}");
                    return AcquirePipelineOutcome::error("detect failed");
                }
            };
            let scale = oriented.det_to_full_scale;
            let rgb = oriented.rgb.as_ref().expect("with_rgb path");
            let max_w = rgb.width();
            let max_h = rgb.height();
            raw.into_iter()
                .map(|b| scale_detected_box(b, scale, max_w, max_h))
                .collect()
        };
        let detect_ms = t_detect.elapsed().as_secs_f64() * 1_000.0;
        log::debug!(
            "[acquire] detect: {:.1}ms found={}",
            detect_ms,
            detected.len()
        );

        if is_auto_source {
            log::info!(
                "auto mode triggered, {} detections, running PULC script classifier (target={})",
                detected.len(),
                to_lang_code,
            );
        }

        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }
        if detected.is_empty() {
            return AcquirePipelineOutcome {
                anchor_id: 0,
                detected_count: 0,
                rec_ok_count: 0,
                rec_empty_count: 0,
                cache_hits: 0,
                rec_called_count: 0,
                total_ms: t_overall.elapsed().as_secs_f64() * 1_000.0,
                canceled: false,
                error: None,
            };
        }

        // ---- Acquire anchor ----
        let t_acquire = Instant::now();
        let anchor_id = {
            let mut state = match frame.state.lock() {
                Ok(s) => s,
                Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
            };
            // Anchor MUST be built from the *full-sensor* gray that
            // the per-frame tracker also uses, otherwise the anchor's
            // feature positions live in visible-region coords while
            // the tracker tries to match against full-sensor coords —
            // mismatch → zero inliers → never locks. Ensure the
            // tracker-side OrientedImage is populated (it might be
            // missing on the very first acquire before the per-frame
            // path has fired).
            if ensure_tracker_oriented_locked(&mut state, det_max_pixels).is_err() {
                return AcquirePipelineOutcome::error("ensure_tracker failed");
            }
            let tracker_oriented = state
                .cached_tracker
                .as_ref()
                .expect("ensure_tracker filled cache");
            // `detected` boxes are in visible-region display coords
            // (PPOCR ran on `cached` at `display_crop`). Convert to
            // *full-display* by translating by the visible region's
            // top-left, then to sensor coords via the standard
            // display→sensor rotation. The regions land in the
            // tracker_oriented.gray's coordinate system (full sensor).
            let sensor_w = state.width;
            let sensor_h = state.height;
            let rotation = state.rotation_degrees;
            // tracker_oriented.gray is downsampled to det_max_pixels;
            // its pixel coords are 1 / det_to_full_scale of full sensor
            // coords. Scale region tuples down to match the gray we're
            // about to hand to acquire_now_in_regions — otherwise the
            // engine filters keypoints in small-pixel coords against
            // full-coord regions and lets nothing through.
            let scale_down = if tracker_oriented.det_to_full_scale > 0.0 {
                1.0 / tracker_oriented.det_to_full_scale
            } else {
                1.0
            };
            let regions: Vec<(u32, u32, u32, u32)> = detected
                .iter()
                .filter_map(|d| {
                    let full_display_rect = translator::Rect {
                        left: d.rect.left + display_crop.left,
                        top: d.rect.top + display_crop.top,
                        right: d.rect.right + display_crop.left,
                        bottom: d.rect.bottom + display_crop.top,
                    };
                    translator::live_frame::display_crop_to_sensor(
                        full_display_rect,
                        sensor_w,
                        sensor_h,
                        rotation,
                    )
                    .ok()
                    .map(|r| {
                        let scale_u32 = |v: u32| ((v as f32) * scale_down).round() as u32;
                        (
                            scale_u32(r.left),
                            scale_u32(r.top),
                            scale_u32(r.right),
                            scale_u32(r.bottom),
                        )
                    })
                })
                .collect();
            let mut engine = match self.state.lock() {
                Ok(g) => g,
                Err(_) => return AcquirePipelineOutcome::error("engine.state poisoned"),
            };
            engine
                .acquire_now_in_regions(
                    &tracker_oriented.gray,
                    &regions,
                    anchor_padding_px,
                    timestamp_ns,
                )
                .unwrap_or(0)
        };
        let acquire_ms = t_acquire.elapsed().as_secs_f64() * 1_000.0;
        log::debug!("[acquire] acquire_now: {:.1}ms id={}", acquire_ms, anchor_id);

        if anchor_id == 0 {
            return AcquirePipelineOutcome::error("acquire_now returned 0");
        }
        // Fresh-acquire wipe: the engine may have re-assigned a
        // previously-used id (e.g. after `engine.clear()` from an AF
        // reset). The session-side `AnchorState` and overlay items
        // keyed by id would otherwise persist, rendering at stale
        // surface coords through the new anchor's H — "overlay
        // stuck to an arbitrary offset" after a fast pan → loss →
        // re-lock cycle. Wipe before `run_post_detect` so the new
        // acquire starts from a clean slate.
        self.session.reset_anchor_state(anchor_id);
        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }

        // ---- Color matting ----
        // Disabled for now: when the per-strip uniform-bg detection
        // works it looks great, but when it fails or flips between
        // acquires the pill colour jarringly snaps in and out. White-
        // on-dark is consistent and that's what we ship. Flip
        // `ENABLE_COLOR_MATTING` to true to re-enable; the algorithm
        // is in `translator::color_matting`, the histogram-peak
        // uniformity check on ring samples is in `uniform_bg_argb`.
        // See `FUTURE_SURFACE_MAP.md` "Color matting" for the full
        // design + open algorithmic questions.
        const ENABLE_COLOR_MATTING: bool = false;
        if ENABLE_COLOR_MATTING {
            let t_mat = Instant::now();
            let matted: Vec<Option<translator::color_matting::MattedStrip>> = {
                let state = match frame.state.lock() {
                    Ok(s) => s,
                    Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
                };
                let oriented = state
                    .cached
                    .as_ref()
                    .expect("oriented still cached");
                translator::color_matting::mat_detections(
                    &oriented
                        .rgb
                        .as_ref()
                        .expect("with_rgb path")
                        .to_rgba8(),
                    &detected,
                )
            };
            let mat_count = matted.iter().filter(|m| m.is_some()).count();
            log::debug!(
                "[acquire] mat: {:.1}ms ok={}/{}",
                t_mat.elapsed().as_secs_f64() * 1_000.0,
                mat_count,
                matted.len(),
            );
            if let Ok(mut store) = self.matted_strips.lock() {
                store.insert(anchor_id, matted);
            }
        }

        let total = detected.len();

        // Pre-compute the catalog's installed language codes for
        // `translate_mixed_texts`. Only used when forced_source_code
        // is None (auto-detect); ignored otherwise. We pass it
        // unconditionally so the auto-source path doesn't need a
        // separate branch.
        let available_codes: Vec<translator::LanguageCode> = catalog
            .session
            .language_rows()
            .into_iter()
            .map(|row| translator::LanguageCode::from(row.language.code.as_str()))
            .collect();

        let matted_strips: Vec<Option<translator::color_matting::MattedStrip>> = match self
            .matted_strips
            .lock()
        {
            Ok(g) => g.get(&anchor_id).cloned().unwrap_or_default(),
            Err(_) => Vec::new(),
        };

        // Snapshot the state metadata we'll need below (rotation +
        // dims for h_disp_to_sensor). Drop the lock so the per-frame
        // composite thread isn't blocked while we run rec.
        let (state_width, state_height, state_rotation_degrees) = {
            let state = match frame.state.lock() {
                Ok(s) => s,
                Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
            };
            (state.width, state.height, state.rotation_degrees)
        };
        // PPOCR boundary: `detected` boxes are in visible-region
        // display coords (PPOCR ran on `cached` which is sized to the
        // user's visible region). The anchor canonical is full-sensor
        // (the tracker uses `cached_tracker` which is built on the
        // full display). Compose:
        //   visible-region-display → full-display via translate by
        //   the visible-region's top-left in display coords;
        //   full-display → full-sensor via the standard rotation H
        //   parameterised on full-sensor dims.
        let h_disp_full_to_sensor = translator::live_frame::display_to_sensor_homography(
            state_width,
            state_height,
            state_rotation_degrees,
        );
        let translate_visible_to_full = [
            1.0, 0.0, display_crop.left as f32,
            0.0, 1.0, display_crop.top as f32,
            0.0, 0.0, 1.0,
        ];
        let h_disp_to_sensor =
            translator::homography::mat3_mul(&h_disp_full_to_sensor, &translate_visible_to_full);

        let cancel = || {
            self.generation.load(std::sync::atomic::Ordering::SeqCst) != generation
        };
        let session_ref: &translator::TranslatorSession = &catalog.session;
        let outcome = {
            let state = match frame.state.lock() {
                Ok(s) => s,
                Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
            };
            let oriented = match state
                .cached
                .as_ref()
                .filter(|oi| oi.display_crop == display_crop)
            {
                Some(o) => o,
                None => return AcquirePipelineOutcome::error("oriented cache miss"),
            };
            let outcome = self.session.run_post_detect(
                translator::live_session::PostDetectInput {
                    detections: &detected,
                    oriented,
                    h_view_to_surface: Some(h_disp_to_sensor),
                    anchor_id,
                    from_lang: &from_lang_code,
                    to_lang: &to_lang_code,
                    is_auto_source,
                    available_codes: &available_codes,
                    font_provider: &crate::android_font_provider::AndroidFontProvider,
                    matted_strips: &matted_strips,
                    rec_batch_size: 4,
                },
                &session_ref,
                &session_ref,
                &cancel,
            );
            drop(state);
            outcome
        };
        // The session marks `on_acquire` here so the detect-on-track
        // refresh trigger doesn't immediately fire on the next Locked
        // frame after a brand-new acquire.
        self.session.on_acquire();

        if outcome.canceled {
            return AcquirePipelineOutcome::canceled();
        }
        // `last_lock_h` is lazy-initialised by `process_and_composite`
        // on the first Locked frame after this acquire completes —
        // by that point the engine's H reflects whatever motion
        // happened during the ~1 s acquire window. Pinning identity
        // here would lag the camera by that window.

        let rec_ok = outcome.rec_ok_count as usize;
        let rec_empty = outcome.rec_empty_count as usize;

        // If nothing recognised, the tracker locked onto garbage. Clear
        // so the next stable frame re-acquires somewhere useful.
        if rec_ok == 0 && rec_empty + rec_ok == total {
            if let Ok(mut engine) = self.state.lock() {
                engine.clear();
            }
            self.clear_overlay();
        }

        // Keep the session's anchor-state HashMap aligned with the
        // engine's anchor cache so we don't carry per-anchor state
        // for surfaces the engine has already evicted. Uses
        // `cached_root_ids` (not `cached_handle_ids`!) because the
        // session keys by root id — the engine's internal cache
        // handles are a different id space; handoffs grow internal
        // ids without changing the root, so the two diverge fast on
        // pans.
        if let Ok(engine) = self.state.lock() {
            let keep = engine.cached_root_ids();
            drop(engine);
            self.session.retain_anchors(&keep);
        }

        AcquirePipelineOutcome {
            anchor_id,
            detected_count: total as u32,
            rec_ok_count: rec_ok as u32,
            rec_empty_count: rec_empty as u32,
            cache_hits: outcome.cache_hits,
            rec_called_count: outcome.rec_called_count,
            total_ms: t_overall.elapsed().as_secs_f64() * 1_000.0,
            canceled: false,
            error: None,
        }
    }

    /// Detect-on-tracking-frame refresh: while Locked on an existing
    /// anchor, run detection on the current camera frame, project the
    /// detected boxes back into surface coords via the stashed
    /// `H_root→view`, and feed the result into `run_post_detect`.
    /// Unlike `run_acquire_pipeline` we do *not* call `acquire_now`;
    /// the anchor stays put. This is the engine that fires
    /// `MergedAndExtended` / `MergedUnchanged` outcomes on the surface
    /// map after the initial acquire — the user-visible "pan reveals
    /// new text → it gets OCR'd, held text hits the cache" behaviour.
    ///
    /// Caller (Kotlin) should gate this on `should_refresh_detect` in
    /// the latest `PlanarComposeResult` and dedupe against any
    /// in-flight acquire.
    fn run_refresh_pipeline(
        &self,
        catalog: Arc<CatalogHandle>,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        from_lang_code: String,
        to_lang_code: String,
        is_auto_source: bool,
        generation: u64,
    ) -> AcquirePipelineOutcome {
        let gen_check =
            || -> bool { self.generation.load(std::sync::atomic::Ordering::SeqCst) == generation };
        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }

        let t_overall = Instant::now();

        // Consume the snapshot the trigger pinned for us. This is
        // the (anchor, H) pair that was active when
        // `should_refresh_detect = true` was decided — *not*
        // whatever the engine has since snapped to. Taking the slot
        // (not just reading it) ensures one trigger fires at most
        // one refresh; an unsolicited `run_refresh_pipeline` call
        // from Kotlin (shouldn't happen, but defensive) returns an
        // error.
        let (anchor_id, h_root_to_view) = match self.pending_refresh_target.lock() {
            Ok(mut g) => match g.take() {
                Some((id, h)) => (id, h),
                None => {
                    return AcquirePipelineOutcome::error("refresh without armed trigger")
                }
            },
            Err(_) => {
                return AcquirePipelineOutcome::error("pending_refresh_target poisoned")
            }
        };
        // Sensor-view → sensor-surface (engine's H is in sensor coords).
        let h_sensor_view_to_surface = match translator::homography::invert(&h_root_to_view) {
            Some(h) => h,
            None => return AcquirePipelineOutcome::error("H_root→view not invertible"),
        };

        // Detect on the current frame, restricted to `display_crop`
        // (the SurfaceView's visible region). Two reasons to match the
        // acquire pipeline's crop here rather than `full_display_rect`:
        // (1) the downstream `translate_visible_to_full` composition
        //     below assumes detected boxes live in visible-region
        //     display coords; (2) the oriented-cache lookup further
        //     down filters on `display_crop == display_crop`, which a
        //     full-display ensure would silently fail.
        let detected: Vec<translator::DetectedTextBox> = {
            let mut state = match frame.state.lock() {
                Ok(s) => s,
                Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
            };
            if ensure_oriented_with_rgb_locked(&mut state, display_crop, det_max_pixels).is_err() {
                return AcquirePipelineOutcome::error("ensure_oriented failed");
            }
            let oriented = state
                .cached
                .as_ref()
                .expect("ensure_oriented filled cache");
            let raw = match catalog.session.detect_text_in_oriented_image(oriented) {
                Ok(r) => r,
                Err(e) => {
                    log::warn!("[refresh] detect failed: {e:?}");
                    return AcquirePipelineOutcome::error("detect failed");
                }
            };
            let scale = oriented.det_to_full_scale;
            let rgb = oriented.rgb.as_ref().expect("with_rgb path");
            let max_w = rgb.width();
            let max_h = rgb.height();
            raw.into_iter()
                .map(|b| scale_detected_box(b, scale, max_w, max_h))
                .collect()
        };
        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }
        if detected.is_empty() {
            return AcquirePipelineOutcome {
                anchor_id,
                detected_count: 0,
                rec_ok_count: 0,
                rec_empty_count: 0,
                cache_hits: 0,
                rec_called_count: 0,
                total_ms: t_overall.elapsed().as_secs_f64() * 1_000.0,
                canceled: false,
                error: None,
            };
        }

        let available_codes: Vec<translator::LanguageCode> = catalog
            .session
            .language_rows()
            .into_iter()
            .map(|row| translator::LanguageCode::from(row.language.code.as_str()))
            .collect();

        let state = match frame.state.lock() {
            Ok(s) => s,
            Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
        };
        let oriented = match state
            .cached
            .as_ref()
            .filter(|oi| oi.display_crop == display_crop)
        {
            Some(o) => o,
            None => return AcquirePipelineOutcome::error("oriented cache miss"),
        };
        let cancel = || {
            self.generation.load(std::sync::atomic::Ordering::SeqCst) != generation
        };
        // PPOCR boundary: detected boxes are in visible-region display
        // coords (OrientedImage at `display_crop`). The anchor
        // canonical is full-sensor. Compose: visible-region-display →
        // full-display (translate by crop top-left) → full-sensor
        // (standard rotation on full dims) → sensor-surface (engine
        // inverse).
        let h_disp_full_to_sensor = translator::live_frame::display_to_sensor_homography(
            state.width,
            state.height,
            state.rotation_degrees,
        );
        let translate_visible_to_full = [
            1.0, 0.0, display_crop.left as f32,
            0.0, 1.0, display_crop.top as f32,
            0.0, 0.0, 1.0,
        ];
        let h_disp_to_sensor =
            translator::homography::mat3_mul(&h_disp_full_to_sensor, &translate_visible_to_full);
        let h_view_to_surface_composed =
            translator::homography::mat3_mul(&h_sensor_view_to_surface, &h_disp_to_sensor);
        let session_ref: &translator::TranslatorSession = &catalog.session;
        // Atomic re-lock semantics: wipe the anchor's surface map +
        // overlay items immediately before `run_post_detect` so the
        // existing `add_or_merge` insert logic lands in an empty map
        // and produces a clean replace (FUTURE_RELOCK_MODEL.md's
        // "hard cut, all-at-once" swap). Done here — not at the top
        // of the function — so an early-exit failure (oriented cache
        // miss, detect failed) leaves the existing overlays in place
        // rather than wiping them and then bailing.
        self.session.clear_anchor_state_for_relock(anchor_id);
        let outcome = self.session.run_post_detect(
            translator::live_session::PostDetectInput {
                detections: &detected,
                oriented,
                h_view_to_surface: Some(h_view_to_surface_composed),
                anchor_id,
                from_lang: &from_lang_code,
                to_lang: &to_lang_code,
                is_auto_source,
                available_codes: &available_codes,
                font_provider: &crate::android_font_provider::AndroidFontProvider,
                matted_strips: &[],
                rec_batch_size: 4,
            },
            &session_ref,
            &session_ref,
            &cancel,
        );
        drop(state);

        if outcome.canceled {
            return AcquirePipelineOutcome::canceled();
        }
        // `last_lock_h` is lazy-initialised by `process_and_composite`
        // on the next Locked frame — the trigger cleared it when it
        // fired so that initialisation uses the engine's *then-current*
        // H rather than the now-stale `h_root_to_view` pinned ~1.5 s
        // ago at trigger fire.

        AcquirePipelineOutcome {
            anchor_id,
            detected_count: outcome.detected_count,
            rec_ok_count: outcome.rec_ok_count,
            rec_empty_count: outcome.rec_empty_count,
            cache_hits: outcome.cache_hits,
            rec_called_count: outcome.rec_called_count,
            total_ms: t_overall.elapsed().as_secs_f64() * 1_000.0,
            canceled: false,
            error: None,
        }
    }

    /// Build a per-frame composited display image from the camera RGBA
    /// (in the FrameHandle) plus the resident overlay, warped by
    /// `h_surface_to_viewport`. Writes the result into the
    /// `pending_display` slot; pair with
    /// `PlanarRenderJni.compositeInto` for a JNI memcpy into a
    /// Kotlin-owned DirectByteBuffer.
    ///
    /// `display_width`/`display_height` are the target display-orient
    /// pixel dimensions — must equal the sensor's W/H swapped per the
    /// rotation reported in the FrameHandle. Caller's destination
    /// One-shot per-frame entry point: tracker step → smooth H →
    /// composite. Replaces the old
    /// `process_frame_with_imu` + `composite_frame` pair, which paid
    /// two uniffi roundtrips and a Kotlin-side H-smoothing detour per
    /// camera frame. Now: single call in, single JNI memcpy out.
    ///
    /// Internal H selection mirrors what Kotlin used to do:
    /// - `Locked` → EMA-smooth the new H against the cached one;
    ///   use the smoothed value; reset the LOST streak.
    /// - `Lost` with streak < `LOSS_HIDE_AFTER_FRAMES` → reuse the
    ///   last good smoothed H, so a single-frame tracker loss doesn't
    ///   flicker the overlay off.
    /// - `Lost` with sustained loss → no H → overlay omitted.
    /// - `Idle` / `Acquiring` → no H → overlay omitted.
    ///
    /// All overlay decisions land at the per-frame H. Block content
    /// (`current_overlay_items`) is updated separately by
    /// `run_acquire_pipeline` and consumed here as-is.
    fn process_and_composite(
        &self,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        imu_stable: bool,
        timestamp_ns: u64,
        display_width: u32,
        display_height: u32,
    ) -> Result<PlanarComposeResult, CatalogError> {
        // 1. Tracker step. We need to lock the frame state for
        // `ensure_oriented_locked` to populate the cached grayscale,
        // then engine state to run the tracker. The composite call
        // below re-locks frame state to read the camera RGBA — that's
        // a brief re-acquire (the mutex is uncontended in steady
        // state) and lets us keep composite_frame as a self-contained
        // method we can call directly.
        let t_orient_start = Instant::now();
        // Capture frame metadata before dropping the state lock so the
        // perspective re-skew composition below can compute
        // `h_disp_to_sensor` without re-locking.
        let frame_state_dims: (u32, u32, i32);
        let tracker_det_to_full: f32;
        let cmd = {
            let mut state = frame.state.lock().map_err(|_| poisoned())?;
            // Tracker gets `cached_tracker`, downsampled to
            // `det_max_pixels` so per-frame detect+describe is
            // linear-cost cheap (~2× speedup vs full-res). Anchor +
            // per-frame match in the same small-coord system; we
            // conjugate the engine's H back to full-display coords
            // below before handing to the compositor.
            ensure_tracker_oriented_locked(&mut state, det_max_pixels)?;
            frame_state_dims = (state.width, state.height, state.rotation_degrees);
            let t_orient_end = Instant::now();
            let oriented = state
                .cached_tracker
                .as_ref()
                .expect("ensure_tracker filled cache");
            tracker_det_to_full = oriented.det_to_full_scale;
            let mut engine = self.state.lock().map_err(|_| poisoned())?;
            let t_engine_start = Instant::now();
            let cmd = engine.process_frame(&oriented.gray, imu_stable, timestamp_ns);
            let t_engine_end = Instant::now();
            if PER_FRAME_TIMING_LOG {
                log::info!(
                    target: "planar_timing",
                    "outer: orient={:.1}ms engine={:.1}ms",
                    (t_orient_end - t_orient_start).as_secs_f64() * 1000.0,
                    (t_engine_end - t_engine_start).as_secs_f64() * 1000.0,
                );
            }
            cmd
        };
        let mut result = cmd_to_result(cmd);
        // Engine ran on the downsampled tracker gray, so its H maps
        // `anchor_small → frame_small`. Conjugate back into
        // full-display coords (`anchor_full → frame_full`) before any
        // downstream smoothing / compositing sees it — overlay surface
        // positions still live in full coords.
        if result.homography.len() == 9 && tracker_det_to_full != 1.0 {
            result.homography = scale_homography(&result.homography, tracker_det_to_full);
        }

        // 2. Decide which H to use for compositing, updating the
        // smoothed-H state along the way. Stash `(anchor_id, h)` so
        // the follow-up `compositeIntoBuffer` JNI call has everything
        // it needs to warp the active anchor's overlay items directly
        // into the Kotlin-owned DirectByteBuffer.
        // Smoothing's corner-delta measure tests at corners (0, 0),
        // (frame_w, frame_h) etc. in surface coords; the result is in
        // view-pixels. With H in full-sensor coords and the user
        // seeing only the visible-region bitmap, using full-sensor
        // dims here makes rotation-induced deltas ~2× larger than
        // the user actually perceives — `SMOOTH_HIGH_PX = 9` then
        // trips on tiny rotations and the H snaps aggressively,
        // producing visible UI jumps on small motion. Use the
        // visible-region dims (= bitmap dims) instead so the
        // threshold band matches user-perceived motion. Translation
        // motion is 1:1 either way.
        let h_engine = self.select_compose_h(
            &result,
            display_width as f32,
            display_height as f32,
        );
        let h_for_compose = h_engine;
        if let Ok(mut slot) = self.pending_compose.lock() {
            *slot = h_for_compose.map(|h| (result.anchor_id, h));
        }
        let bytes = (display_width as u32)
            .saturating_mul(display_height as u32)
            .saturating_mul(4);

        // Tick the detect-on-tracking-frame counter on Locked frames.
        // Refresh fires only when ALL THREE gates clear, in this
        // order (cheapest first):
        //   1. cadence  — at least `refresh_every_n_locked_frames`
        //      ticks since the previous fire (rate limiter).
        //   2. motion   — H_root→view has moved by more than
        //      [`MIN_REFRESH_DELTA_PX`] at the viewport corners
        //      since `last_refresh_h`. Held cameras + handoff drift
        //      stay below; intentional pans clear it.
        //   3. coverage — the current viewport, projected to the
        //      active anchor's surface coords, isn't already inside
        //      that anchor's `covered_region` (no new pixels would
        //      be revealed).
        //
        // The motion gate kills "still wobble": even with the
        // covered-region check, RANSAC noise + handoff micro-drift
        // can shift the viewport-AABB just past covered → fire →
        // detector returns slightly different boxes → MergedAnd-
        // Extended on detector noise → overlay re-raster on a held
        // camera. Motion-gating first removes that whole class.
        // Re-lock trigger (FUTURE_RELOCK_MODEL.md): single overlap
        // check against the anchor's `lock_viewport` (set by the most
        // recent successful detect+OCR+translate pass). Fires when
        //   area(intersect) / max(area(viewport), area(lock_viewport))
        //     < RELOCK_OVERLAP_THRESHOLD
        // — symmetric in zoom direction (zoom-in shrinks viewport so
        // max=lock; zoom-out grows it so max=viewport; pan crosses
        // zero overlap regardless).
        //
        // Coord-system note: both the lock_viewport stored by
        // `run_post_detect` and the current viewport computed here
        // must live in the same canonical anchor frame for the
        // overlap math to mean anything. `run_post_detect` projects
        // `(cropDispW, cropDispH)` display-crop corners through
        // `h_view_to_surface = h_disp_to_sensor` (acquire) or
        // `inv(engine_H) · h_disp_to_sensor` (refresh). We mirror
        // that here: build the same composition from the current
        // frame state + `engine_H`, then project the same display-
        // crop corners. With a steady camera the two AABBs coincide
        // (overlap ≈ 1); a real pan/zoom shifts the corners through
        // a different `inv(engine_H)` and the overlap drops.
        let should_refresh_detect = if matches!(result.state, PlanarTrackerState::Locked) {
            let current_h: Option<[f32; 9]> = if result.homography.len() == 9 {
                let mut h = [0.0f32; 9];
                h.copy_from_slice(&result.homography[..9]);
                if let Ok(mut slot) = self.last_root_to_view.lock() {
                    *slot = Some((result.anchor_id, h));
                }
                Some(h)
            } else {
                None
            };
            match current_h {
                Some(h) => {
                    // Lazy init: the first Locked frame for an
                    // anchor (or the first after a trigger fire
                    // invalidated `last_lock_h`) seeds the reference
                    // pose from the current engine H. We skip the
                    // overlap check on this frame — comparing the
                    // current H against itself would trivially pass
                    // anyway. Subsequent frames compare against this
                    // initialised value.
                    if !self.session.has_last_lock_h(result.anchor_id) {
                        self.session.set_last_lock_h(result.anchor_id, h);
                        false
                    } else {
                        // Full-display dims (the engine's view —
                        // `cached_tracker` is built on the full
                        // display).
                        let r = ((frame_state_dims.2 % 360) + 360) % 360;
                        let (full_view_w, full_view_h) = if r == 90 || r == 270 {
                            (frame_state_dims.1 as f32, frame_state_dims.0 as f32)
                        } else {
                            (frame_state_dims.0 as f32, frame_state_dims.1 as f32)
                        };
                        if self.session.should_relock_by_view(
                            result.anchor_id,
                            &h,
                            full_view_w,
                            full_view_h,
                            RELOCK_OVERLAP_THRESHOLD,
                        ) {
                            // Invalidate `last_lock_h` so the next
                            // Locked frame re-seeds from the engine's
                            // *then-current* H — not this trigger-
                            // fire H, which would lag the camera by
                            // the ~1-2 s refresh pipeline duration.
                            self.session.clear_last_lock_h(result.anchor_id);
                            // Pin (anchor, H) so the refresh worker
                            // projects through the H the trigger
                            // fired *for*, not whatever the engine
                            // has snapped to by pickup time.
                            if let Ok(mut slot) = self.pending_refresh_target.lock() {
                                *slot = Some((result.anchor_id, h));
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
                None => false,
            }
        } else {
            false
        };

        Ok(PlanarComposeResult {
            state: result.state,
            anchor_id: result.anchor_id,
            inliers: result.inliers,
            composite_byte_size: bytes,
            should_refresh_detect,
        })
    }

    /// `h_surface_to_viewport` is a 9-element row-major homography; an
    /// empty slice (or length != 9) is treated as "no overlay this
    /// frame, just blit the camera".
    ///
    /// `active_anchor_id` filters `session.overlay_items` to only the
    /// items whose surface coords correspond to the H we're about to
    /// warp them by — items from previously-active anchors are still
    /// in the store (LRU evict aligned with the engine), but they
    /// can't be warped by *this* anchor's H without landing at
    /// random viewport positions.
    ///

    /// Feed one camera frame through the state machine. The current
    /// `display_crop` is what the engine should treat as the working
    /// region — we use its cached grayscale.
    fn process_frame(
        &self,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        imu_stable: bool,
        timestamp_ns: u64,
    ) -> Result<PlanarFrameResult, CatalogError> {
        let mut state = frame.state.lock().map_err(|_| poisoned())?;
        ensure_oriented_locked(&mut state, display_crop, det_max_pixels)?;
        let oriented = state.cached.as_ref().expect("ensure_oriented filled cache");
        let mut engine = self.state.lock().map_err(|_| poisoned())?;
        let cmd = engine.process_frame(&oriented.gray, imu_stable, timestamp_ns);
        Ok(cmd_to_result(cmd))
    }

    /// Force a fresh acquisition from the current frame's grayscale.
    /// Returns the new anchor id, or 0 if no anchor could be built (the
    /// cooldown blocked it or the frame had no usable features).
    fn acquire_now(
        &self,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        timestamp_ns: u64,
    ) -> Result<u64, CatalogError> {
        let mut state = frame.state.lock().map_err(|_| poisoned())?;
        ensure_oriented_locked(&mut state, display_crop, det_max_pixels)?;
        let oriented = state.cached.as_ref().expect("ensure_oriented filled cache");
        let mut engine = self.state.lock().map_err(|_| poisoned())?;
        Ok(engine.acquire_now(&oriented.gray, timestamp_ns).unwrap_or(0))
    }

    /// Like `acquire_now` but limits anchor features to those inside any
    /// of the given axis-aligned regions (full-crop coords; same space
    /// as detected text boxes scaled up via `detect_text_in_frame`).
    /// Padded by `pad_px` on each side so the anchor includes a small
    /// border around each region.
    fn acquire_now_in_regions(
        &self,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        regions: Vec<translator::Rect>,
        pad_px: u32,
        timestamp_ns: u64,
    ) -> Result<u64, CatalogError> {
        let mut state = frame.state.lock().map_err(|_| poisoned())?;
        ensure_oriented_locked(&mut state, display_crop, det_max_pixels)?;
        let oriented = state.cached.as_ref().expect("ensure_oriented filled cache");
        let tuples: Vec<(u32, u32, u32, u32)> = regions
            .iter()
            .map(|r| (r.left, r.top, r.right, r.bottom))
            .collect();
        let engine_wait_start = Instant::now();
        let mut engine = self.state.lock().map_err(|_| poisoned())?;
        let engine_acquired = Instant::now();
        let result = engine
            .acquire_now_in_regions(&oriented.gray, &tuples, pad_px, timestamp_ns)
            .unwrap_or(0);
        let engine_released = Instant::now();
        drop(engine);
        log_lock_timing(
            "engine/acquire_now_in_regions",
            engine_acquired - engine_wait_start,
            engine_released - engine_acquired,
        );
        Ok(result)
    }



    fn current_anchor(&self) -> u64 {
        match self.state.lock() {
            Ok(g) => g.current_anchor().unwrap_or(0),
            Err(_) => 0,
        }
    }

    fn clear(&self) {
        if let Ok(mut g) = self.state.lock() {
            g.clear();
        }
    }
}

#[cfg(feature = "planar-tracker")]
/// Conjugate a homography `H` by an isotropic scale `s`:
/// `H' = diag(s,s,1) · H · diag(1/s,1/s,1)`. If `H` maps points in a
/// downsampled coord system to points in the same downsampled coord
/// system, `H'` maps the equivalent points in the upscaled (full) coord
/// system. Used to lift the engine's small-coord H back to full-display
/// coords for the compositor.
#[cfg(feature = "planar-tracker")]
fn scale_homography(h: &[f32], s: f32) -> Vec<f32> {
    debug_assert_eq!(h.len(), 9);
    let inv_s = 1.0 / s;
    vec![
        h[0],          h[1],          h[2] * s,
        h[3],          h[4],          h[5] * s,
        h[6] * inv_s,  h[7] * inv_s,  h[8],
    ]
}

#[cfg(feature = "planar-tracker")]
fn cmd_to_result(cmd: translator::planar_engine::TrackerCommand) -> PlanarFrameResult {
    use translator::planar_engine::TrackerCommand as C;
    match cmd {
        C::Idle => PlanarFrameResult {
            state: PlanarTrackerState::Idle,
            anchor_id: 0,
            homography: Vec::new(),
            is_new: false,
            inliers: 0,
        },
        C::Acquiring => PlanarFrameResult {
            state: PlanarTrackerState::Acquiring,
            anchor_id: 0,
            homography: Vec::new(),
            is_new: false,
            inliers: 0,
        },
        C::Locked {
            anchor_id,
            homography,
            is_new,
            inliers,
        } => PlanarFrameResult {
            state: PlanarTrackerState::Locked,
            anchor_id,
            homography: homography.to_vec(),
            is_new,
            inliers: inliers as u32,
        },
        C::Lost { last_anchor_id } => PlanarFrameResult {
            state: PlanarTrackerState::Lost,
            anchor_id: last_anchor_id,
            homography: Vec::new(),
            is_new: false,
            inliers: 0,
        },
    }
}

/// Tuning for the per-frame H smoother. Same values that lived in
/// Kotlin's `LivePlanarOcrEngine` before this got moved into Rust.
///
/// `LOW`/`HIGH` are corner-delta thresholds in pixels (projected
/// through the new vs previously-smoothed H at the canonical frame's
/// four corners). Below `LOW` we treat the per-frame change as RANSAC
/// noise and average heavily; above `HIGH` we treat it as real motion
/// and snap. Linear blend in between.
#[cfg(feature = "planar-tracker")]
const SMOOTH_LOW_PX: f32 = 3.0;
#[cfg(feature = "planar-tracker")]
const SMOOTH_HIGH_PX: f32 = 9.0;
#[cfg(feature = "planar-tracker")]
const SMOOTH_MIN_ALPHA: f32 = 0.35;

/// Frames of sustained tracker LOST before we hide the overlay.
/// Set to 1 to hide *immediately* on the first Lost frame — keeping
/// it higher renders the overlay through the prior frame's (stale)
/// H while the compositor writes fresh camera RGBA, producing the
/// "camera moves but UI doesn't" visual artefact during the hide
/// grace window. With `lost_after_frames = 5` upstream the engine
/// is already permissive about brief failures; the Lost→Hide
/// transition should be instant.
#[cfg(feature = "planar-tracker")]
const LOSS_HIDE_AFTER_FRAMES: u32 = 4;

/// Re-lock trigger threshold (FUTURE_RELOCK_MODEL.md). Fire a fresh
/// detect+OCR+translate pass when
///   `area(intersect) / max(area(viewport), area(lock_viewport)) <
///    RELOCK_OVERLAP_THRESHOLD`.
///
/// At 0.65 the trigger corresponds to roughly:
///   - Translation: pan covering ~1/3 of viewport width.
///   - Zoom: scale change past ~1.25× in either direction
///     (zoom²=1.56; ratio drops to 1/1.56 ≈ 0.64).
///   - In-plane rotation alone: overlap ≈ 1.0, never fires (matches
///     observed Google Translate behaviour).
#[cfg(feature = "planar-tracker")]
const RELOCK_OVERLAP_THRESHOLD: f32 = 0.65;

/// Bypass the EMA H smoother and use the tracker's raw per-frame H
/// directly. The smoother was designed for the old two-surface
/// architecture (preview + overlay) where preview-vs-H timing
/// mismatch under motion contributed to perceived wobble. With the
/// new same-frame composite, that source is gone — only intrinsic
/// RANSAC jitter remains. Toggle to A/B whether smoothing still earns
/// its keep on static dense pages vs the lag it adds during slow pans.
#[cfg(feature = "planar-tracker")]
const DISABLE_SMOOTH_H: bool = false;

/// Smoothed-homography state held across `process_and_composite`
/// calls. `h == None` means "no smoothed H yet for the current
/// anchor" — reset on anchor switch, on `reset()`, and at start.
#[cfg(feature = "planar-tracker")]
#[derive(Default)]
struct SmoothedHomography {
    h: Option<[f32; 9]>,
    anchor_id: u64,
    consecutive_lost: u32,
}

/// Non-uniffi helpers on `LivePlanarTracker`. Kept out of the
/// `#[uniffi::export] impl` block because their signatures use
/// `[f32; 9]` and `&PlanarFrameResult` shapes that uniffi can't
/// introspect.
#[cfg(feature = "planar-tracker")]
impl LivePlanarTracker {
    /// Pop the `(anchor_id, h_surface_to_viewport)` pair stashed by
    /// the most recent `process_and_composite`. Used by the JNI
    /// `compositeInto` shim to decide which anchor's overlays to warp
    /// + at what H. `None` means "no overlay this frame — blit the
    /// camera only".
    pub(crate) fn take_pending_compose(&self) -> Option<(u64, [f32; 9])> {
        self.pending_compose.lock().ok().and_then(|mut s| s.take())
    }

    /// Composite directly into the caller-supplied destination slice
    /// — the zero-copy JNI fast path. Locks the session's
    /// `overlay_items` for the duration of the composite so the
    /// bitmaps can be borrowed without cloning. Output is
    /// **sensor-orient** (same dims as the camera buffer); the
    /// SurfaceView rotates it for display at scanout.
    /// `h_surface_to_viewport` is `None` on Idle / Acquiring /
    /// sustained Lost — we still want the camera frame on screen,
    /// just with no overlay warp.
    pub(crate) fn composite_into_slice(
        &self,
        frame: &FrameHandle,
        dst: &mut [u8],
        bitmap_w: u32,
        bitmap_h: u32,
        h_surface_to_viewport: Option<[f32; 9]>,
        active_anchor_id: u64,
    ) -> Result<(), translator::live_compositor::CompositeError> {
        let state = frame.state.lock().expect("frame mutex poisoned");
        let sensor_w = state.width;
        let sensor_h = state.height;
        // Bitmap dims are the *visible-region-sensor* dims (= the
        // FILL_CENTER preview region in sensor coords). The source
        // RGBA is the full sensor frame; compute the centred crop
        // offset to feed only the visible portion to the compositor.
        // When dims match (no crop case), this collapses to a 0-offset
        // copy — equivalent to today's full-frame composite.
        let src_offset_x = sensor_w.saturating_sub(bitmap_w) / 2;
        let src_offset_y = sensor_h.saturating_sub(bitmap_h) / 2;
        let overlay_guard = self.session.overlay_items.lock().ok();
        let items_vec: Vec<translator::live_compositor::OverlayItem<'_>> =
            match (&overlay_guard, h_surface_to_viewport) {
                (Some(items), Some(_)) => items
                    .iter()
                    .filter(|it| it.anchor_id == active_anchor_id)
                    .map(|it| translator::live_compositor::OverlayItem {
                        bitmap_rgba: &it.bitmap,
                        bitmap_width: it.width,
                        bitmap_height: it.height,
                        bitmap_origin_surface_x: it.surface_origin_x,
                        bitmap_origin_surface_y: it.surface_origin_y,
                    })
                    .collect(),
                _ => Vec::new(),
            };
        let h_for_call =
            h_surface_to_viewport.unwrap_or([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]);
        // Surface coords are in *full-sensor view* (anchor canonical
        // lives on the full sensor). The compositor's bitmap is the
        // *visible-region* of that view, with its (0,0) at full-sensor
        // (src_offset_x, src_offset_y). Translate the H so overlay
        // items end up in bitmap-local coords rather than full-sensor
        // coords — otherwise overlay-engine_H would project everything
        // to full-sensor positions, and a centred bitmap would catch
        // only the part of an overlay that happens to fall inside its
        // sensor crop.
        let translate = [
            1.0, 0.0, -(src_offset_x as f32),
            0.0, 1.0, -(src_offset_y as f32),
            0.0, 0.0, 1.0,
        ];
        let h_translated = translator::homography::mat3_mul(&translate, &h_for_call);
        translator::live_compositor::composite_frame_into_cropped(
            dst,
            bitmap_w,
            bitmap_h,
            state.rgba_bytes(),
            sensor_w,
            sensor_h,
            src_offset_x,
            src_offset_y,
            &h_translated,
            &items_vec,
        )
    }

    /// Decide which H to feed into the compositor for one frame,
    /// updating the smoothed-H state in the process. Returns `None`
    /// when we should skip the overlay warp entirely (Idle,
    /// Acquiring, sustained Lost).
    fn select_compose_h(
        &self,
        result: &PlanarFrameResult,
        frame_w: f32,
        frame_h: f32,
    ) -> Option<[f32; 9]> {
        let mut sm = match self.smoothed_h.lock() {
            Ok(s) => s,
            Err(_) => return None,
        };
        match result.state {
            PlanarTrackerState::Locked => {
                sm.consecutive_lost = 0;
                if result.homography.len() != 9 {
                    return None;
                }
                let mut incoming = [0f32; 9];
                incoming.copy_from_slice(&result.homography[..9]);
                if DISABLE_SMOOTH_H {
                    sm.h = Some(incoming);
                    sm.anchor_id = result.anchor_id;
                    Some(incoming)
                } else {
                    let smoothed = smooth_homography(
                        &mut sm,
                        result.anchor_id,
                        &incoming,
                        frame_w,
                        frame_h,
                    );
                    Some(smoothed)
                }
            }
            PlanarTrackerState::Lost => {
                sm.consecutive_lost = sm.consecutive_lost.saturating_add(1);
                if sm.consecutive_lost < LOSS_HIDE_AFTER_FRAMES {
                    sm.h
                } else {
                    None
                }
            }
            PlanarTrackerState::Idle | PlanarTrackerState::Acquiring => {
                sm.consecutive_lost = 0;
                None
            }
        }
    }
}

/// EMA-smooth an incoming homography against the previously-smoothed
/// one. The blend factor scales with how far the four canonical-frame
/// corners have moved between the two Hs: tiny corner deltas (RANSAC
/// jitter) get heavy smoothing, large deltas (real camera motion)
/// snap immediately. Mirrors what the old Kotlin `smoothHomography`
/// did; consolidated here so the per-frame call stays in Rust.
#[cfg(feature = "planar-tracker")]
fn smooth_homography(
    sm: &mut SmoothedHomography,
    anchor_id: u64,
    incoming: &[f32; 9],
    frame_w: f32,
    frame_h: f32,
) -> [f32; 9] {
    // Anchor switch (or first frame on this anchor) → reset.
    if sm.anchor_id != anchor_id || sm.h.is_none() {
        sm.h = Some(*incoming);
        sm.anchor_id = anchor_id;
        return *incoming;
    }
    let prev = sm.h.expect("checked above");
    let corners = [
        (0.0_f32, 0.0_f32),
        (frame_w, 0.0),
        (frame_w, frame_h),
        (0.0, frame_h),
    ];
    let mut max_delta = 0.0_f32;
    for &(cx, cy) in &corners {
        let pn = translator::homography::project(incoming, cx, cy);
        let pp = translator::homography::project(&prev, cx, cy);
        if let (Some(pn), Some(pp)) = (pn, pp) {
            let dx = pn.0 - pp.0;
            let dy = pn.1 - pp.1;
            let d = (dx * dx + dy * dy).sqrt();
            if d > max_delta {
                max_delta = d;
            }
        }
    }
    let alpha = if max_delta <= SMOOTH_LOW_PX {
        SMOOTH_MIN_ALPHA
    } else if max_delta >= SMOOTH_HIGH_PX {
        1.0
    } else {
        let t = (max_delta - SMOOTH_LOW_PX) / (SMOOTH_HIGH_PX - SMOOTH_LOW_PX);
        SMOOTH_MIN_ALPHA + t * (1.0 - SMOOTH_MIN_ALPHA)
    };
    let mut out = [0.0_f32; 9];
    for i in 0..9 {
        out[i] = alpha * incoming[i] + (1.0 - alpha) * prev[i];
    }
    sm.h = Some(out);
    sm.anchor_id = anchor_id;
    out
}

/// Result of one `process_and_composite` call. Same shape as the
/// old `PlanarFrameResult` minus `homography` and `is_new` (the H is
/// internal now; `is_new` was unused on the Kotlin side), plus a
/// `composite_byte_size` so the caller knows the JNI memcpy size to
/// expect.
#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Record)]
pub struct PlanarComposeResult {
    pub state: PlanarTrackerState,
    pub anchor_id: u64,
    pub inliers: u32,
    /// Number of bytes the compositor wrote to `pending_display`.
    /// `0` means no composite happened this frame (display dims
    /// zero, frame buffer empty, etc.); Kotlin should skip the JNI
    /// memcpy.
    pub composite_byte_size: u32,
    /// Detect-on-tracking-frame trigger: true when the session's
    /// per-frame counter says it's time to fire a fresh detection
    /// pass on this Locked frame. Caller should launch a worker that
    /// invokes `run_refresh_pipeline` (deduping against
    /// `acquireInFlight` so an initial-acquire and a refresh don't
    /// fan out together). False on Idle / Acquiring / Lost.
    pub should_refresh_detect: bool,
}

/// Outcome reported by `run_acquire_pipeline`. The pipeline either ran
/// to completion (`canceled = false`, `error = None`), bailed out
/// because the tracker's generation moved on while it was running
/// (`canceled = true`), or hit a fatal error during one of its stages
/// (`error = Some(...)`). Kotlin uses this for the debug pill +
/// post-acquire decisions ("anchor produced zero usable text, hide
/// the overlay" lives inside the pipeline now, so Kotlin's only job
/// is to log the outcome).
#[cfg(feature = "planar-tracker")]
#[derive(uniffi::Record)]
pub struct AcquirePipelineOutcome {
    pub anchor_id: u64,
    pub detected_count: u32,
    pub rec_ok_count: u32,
    pub rec_empty_count: u32,
    /// Detections that hit the surface-map cache (no ppocr rec
    /// call). On a held camera this should approach `detected_count`
    /// — the diagnostic signal that the cache path is doing its job.
    pub cache_hits: u32,
    /// Detections that actually went through ppocr rec this run.
    /// Sums with `cache_hits` to `detected_count` (minus cancels).
    pub rec_called_count: u32,
    pub total_ms: f64,
    pub canceled: bool,
    pub error: Option<String>,
}

#[cfg(feature = "planar-tracker")]
impl AcquirePipelineOutcome {
    fn canceled() -> Self {
        Self {
            anchor_id: 0,
            detected_count: 0,
            rec_ok_count: 0,
            rec_empty_count: 0,
            cache_hits: 0,
            rec_called_count: 0,
            total_ms: 0.0,
            canceled: true,
            error: None,
        }
    }
    fn error(reason: &str) -> Self {
        Self {
            anchor_id: 0,
            detected_count: 0,
            rec_ok_count: 0,
            rec_empty_count: 0,
            cache_hits: 0,
            rec_called_count: 0,
            total_ms: 0.0,
            canceled: false,
            error: Some(reason.to_string()),
        }
    }
}




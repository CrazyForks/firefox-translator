use std::sync::Arc;
use std::time::Instant;
use std::{fs, path::Path};

/// Diagnostic logging for the live pipeline. Flip to `true` while
/// investigating contention or per-stage costs in the planar
/// acquire/composite path; otherwise leave off — the lock timing +
/// raster body lines are noisy in normal use.
#[cfg(feature = "planar-tracker")]
const LIVE_PIPELINE_DIAG: bool = false;

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
        ensure_oriented_locked(&mut state, crop, det_max_pixels)?;
        let oriented = state
            .cached
            .as_ref()
            .expect("ensure_oriented populated cache");
        let raw = self
            .session
            .detect_text_in_oriented_image(oriented)
            .map_err(CatalogError::from)?;
        let scale = oriented.det_to_full_scale;
        let max_w = oriented.rgb.width();
        let max_h = oriented.rgb.height();
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
    state: std::sync::Mutex<FrameState>,
}

pub(crate) struct FrameState {
    pub rgba: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub rotation_degrees: i32,
    pub cached: Option<translator::live_frame::OrientedImage>,
}

impl FrameHandle {
    fn new(initial_capacity: usize) -> Self {
        FrameHandle {
            state: std::sync::Mutex::new(FrameState {
                rgba: Vec::with_capacity(initial_capacity),
                width: 0,
                height: 0,
                rotation_degrees: 0,
                cached: None,
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
        state.width = width;
        state.height = height;
        state.rotation_degrees = rotation_degrees;
        state.cached = None;
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
            &state.rgba,
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
#[cfg(feature = "planar-tracker")]
struct CurrentOverlayItem {
    id: u64,
    bitmap: Vec<u8>,
    width: u32,
    height: u32,
    surface_origin_x: f32,
    surface_origin_y: f32,
    content_hash: u64,
}

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
    current_overlay_items: std::sync::Mutex<Vec<CurrentOverlayItem>>,
    /// Bumped on every `reset()` (tap-to-focus, language change, etc.).
    /// In-flight acquire pipelines pass the generation they captured at
    /// launch as a parameter; before each potentially-slow step
    /// (detect, recognize, translate) the pipeline checks whether the
    /// generation has moved on and bails if so.
    generation: std::sync::atomic::AtomicU64,
    /// Per-block id source for items shipped to `upsert_overlay_block`.
    /// We keep stable ids across rec batches so each block's rasterized
    /// bitmap is reused unchanged when only its translation is added.
    next_entry_id: std::sync::atomic::AtomicU64,
    /// EMA-smoothed homography state, kept across consecutive
    /// `process_and_composite` calls. Holds the last smoothed H + the
    /// anchor it belongs to + the streak of LOST frames since the
    /// previous Locked. Lives in Rust so the per-frame call can do
    /// `tracker step → smooth → composite` in one trip instead of
    /// pinging back to Kotlin for the H math.
    smoothed_h: std::sync::Mutex<SmoothedHomography>,
    /// Output buffer produced by `composite_frame` (uniffi) and
    /// consumed by `Java_..._PlanarRenderJni_compositeInto` (JNI).
    /// Holds the fully-composited camera + overlay RGBA at display
    /// resolution. Vec<u8>-via-JNI-memcpy avoids uniffi marshalling for
    /// an 8 MB buffer per frame.
    pending_display: std::sync::Mutex<Option<Vec<u8>>>,
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
            current_overlay_items: std::sync::Mutex::new(Vec::new()),
            smoothed_h: std::sync::Mutex::new(SmoothedHomography::default()),
            generation: std::sync::atomic::AtomicU64::new(0),
            next_entry_id: std::sync::atomic::AtomicU64::new(1),
            pending_display: std::sync::Mutex::new(None),
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
    fn upsert_overlay_block(
        &self,
        id: u64,
        strips: Vec<translator::ocr::OrientedRect>,
        source_text: String,
        translated_text: String,
        language: String,
    ) {
        if strips.is_empty() {
            return;
        }
        let display_text = pick_display_text(&source_text, &translated_text);
        let hash = block_content_hash(&strips, &display_text, &language);
        // Fast path: same content → keep the cached bitmap.
        {
            if let Ok(items) = self.current_overlay_items.lock() {
                if let Some(existing) = items.iter().find(|it| it.id == id) {
                    if existing.content_hash == hash {
                        return;
                    }
                }
            }
        }
        let raster_start = Instant::now();
        let raster = match render_block_bitmap(&strips, &display_text, &language) {
            Some(r) => r,
            None => return,
        };
        let raster_end = Instant::now();
        if let Ok(mut items) = self.current_overlay_items.lock() {
            let new_item = CurrentOverlayItem {
                id,
                bitmap: raster.bitmap,
                width: raster.width,
                height: raster.height,
                surface_origin_x: raster.surface_origin_x,
                surface_origin_y: raster.surface_origin_y,
                content_hash: hash,
            };
            if let Some(slot) = items.iter_mut().find(|it| it.id == id) {
                *slot = new_item;
            } else {
                items.push(new_item);
            }
        }
        if LIVE_PIPELINE_DIAG {
            let raster_ms = (raster_end - raster_start).as_secs_f64() * 1_000.0;
            if raster_ms > LOCK_LOG_THRESHOLD_MS {
                log::debug!(
                    "[work] block raster: id={} {:.1}ms strips={} text={:?}",
                    id,
                    raster_ms,
                    strips.len(),
                    display_text,
                );
            }
        }
    }

    /// Drop any resident overlay item whose id is not in `ids`. Used
    /// when an anchor finishes acquire (final list of ids known) or
    /// when an anchor switches.
    fn retain_overlay_items(&self, ids: Vec<u64>) {
        let keep: std::collections::HashSet<u64> = ids.into_iter().collect();
        if let Ok(mut items) = self.current_overlay_items.lock() {
            items.retain(|it| keep.contains(&it.id));
        }
    }

    /// Drop every resident overlay item. Compositor will draw a
    /// camera-only frame after this.
    fn clear_overlay(&self) {
        if let Ok(mut items) = self.current_overlay_items.lock() {
            items.clear();
        }
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
        self.clear_overlay();
        if let Ok(mut sm) = self.smoothed_h.lock() {
            *sm = SmoothedHomography::default();
        }
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
            if ensure_oriented_locked(&mut state, display_crop, det_max_pixels).is_err() {
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
            let max_w = oriented.rgb.width();
            let max_h = oriented.rgb.height();
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
                total_ms: t_overall.elapsed().as_secs_f64() * 1_000.0,
                canceled: false,
                error: None,
            };
        }

        // ---- Acquire anchor ----
        let t_acquire = Instant::now();
        let anchor_id = {
            let state = match frame.state.lock() {
                Ok(s) => s,
                Err(_) => return AcquirePipelineOutcome::error("frame.state poisoned"),
            };
            let oriented = state
                .cached
                .as_ref()
                .expect("oriented still cached");
            let regions: Vec<(u32, u32, u32, u32)> = detected
                .iter()
                .map(|d| (d.rect.left, d.rect.top, d.rect.right, d.rect.bottom))
                .collect();
            let mut engine = match self.state.lock() {
                Ok(g) => g,
                Err(_) => return AcquirePipelineOutcome::error("engine.state poisoned"),
            };
            engine
                .acquire_now_in_regions(&oriented.gray, &regions, anchor_padding_px, timestamp_ns)
                .unwrap_or(0)
        };
        let acquire_ms = t_acquire.elapsed().as_secs_f64() * 1_000.0;
        log::debug!("[acquire] acquire_now: {:.1}ms id={}", acquire_ms, anchor_id);

        if anchor_id == 0 {
            return AcquirePipelineOutcome::error("acquire_now returned 0");
        }
        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }

        // Build per-strip state local to this pipeline call. Each
        // entry tracks one detected line's rec progress; blocks group
        // these entries (by index) once `group_entries_into_blocks`
        // runs below.
        let mut entries: Vec<AcquireEntry> = detected
            .into_iter()
            .map(|d| AcquireEntry {
                tight: d.tight_box,
                source_text: String::new(),
                source_code: from_lang_code.clone(),
                rec_attempted: false,
                rec_box: d,
            })
            .collect();
        let total = entries.len();

        let source_selection = if is_auto_source {
            translator::OcrSourceSelection::Auto
        } else {
            translator::OcrSourceSelection::Specific {
                language_code: translator::LanguageCode::from(from_lang_code.as_str()),
            }
        };

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

        // ---- Group strips into translation blocks ----
        //
        // The block is the unit of overlay state: a 1-line label is a
        // 1-strip block, a paragraph is N strips. Translation happens
        // per block (one merged source text → one translation), and
        // the renderer reflows that translation across the strips so
        // the visual reads as the original text shape.
        //
        // Grouping runs on the surface-coord tight rects right after
        // detect, before rec, so we can immediately upsert per-block
        // pending placeholders. Stable coords → grouping is invariant
        // to camera motion; recognising the same anchor later (cache
        // hit) reuses the same blocks.
        let t_group = Instant::now();
        let block_strip_indices: Vec<Vec<usize>> = group_entries_into_blocks(&entries);
        let block_strips: Vec<Vec<translator::ocr::OrientedRect>> = block_strip_indices
            .iter()
            .map(|idxs| idxs.iter().map(|&i| entries[i].tight.clone()).collect())
            .collect();
        let block_ids: Vec<u64> = (0..block_strip_indices.len())
            .map(|_| self.next_entry_id.fetch_add(1, std::sync::atomic::Ordering::Relaxed))
            .collect();
        log::debug!(
            "[acquire] group: {:.1}ms strips={} → blocks={}",
            t_group.elapsed().as_secs_f64() * 1_000.0,
            total,
            block_ids.len(),
        );

        // Pending placeholders: per-strip bg rects, no text. Visible
        // immediately so the user sees "we detected text here" while
        // rec+translate run. retain_overlay_items drops any leftover
        // items from a prior anchor.
        for (i, &id) in block_ids.iter().enumerate() {
            self.upsert_overlay_block(
                id,
                block_strips[i].clone(),
                String::new(),
                String::new(),
                to_lang_code.clone(),
            );
        }
        self.retain_overlay_items(block_ids.clone());

        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }

        // ---- Recognise strips in batches ----
        //
        // We rec all strips in detection order (batches of 4) and
        // track per-block completion. As soon as every strip of a
        // block is rec'd, the block is "ready" — we translate (with
        // any other blocks ready in the same wave) and upsert with
        // the final text. The pending placeholder stays visible until
        // its block's final translation lands.
        const REC_BATCH_SIZE: usize = 4;
        let mut block_of_entry = vec![0usize; total];
        for (bi, idxs) in block_strip_indices.iter().enumerate() {
            for &ei in idxs {
                block_of_entry[ei] = bi;
            }
        }
        let mut block_rec_remaining: Vec<usize> = block_strip_indices
            .iter()
            .map(|idxs| idxs.len())
            .collect();
        let mut block_translated = vec![false; block_ids.len()];

        let mut start = 0;
        while start < total {
            if !gen_check() {
                return AcquirePipelineOutcome::canceled();
            }
            let end = (start + REC_BATCH_SIZE).min(total);
            let t_batch = Instant::now();

            let batch_boxes: Vec<translator::DetectedTextBox> =
                entries[start..end].iter().map(|e| e.rec_box.clone()).collect();
            let lines: Vec<translator::ocr::RecognizedTextLine> = {
                let state = match frame.state.lock() {
                    Ok(s) => s,
                    Err(_) => break,
                };
                let oriented = match state
                    .cached
                    .as_ref()
                    .filter(|oi| oi.display_crop == display_crop)
                {
                    Some(o) => o,
                    None => break,
                };
                match catalog.session.recognize_in_oriented_image(
                    oriented,
                    &batch_boxes,
                    source_selection.clone(),
                ) {
                    Ok(l) => l,
                    Err(e) => {
                        if is_auto_source {
                            log::info!(
                                "auto mode: recognize failed (batch start={}, size={}): {:?}",
                                start,
                                end - start,
                                e,
                            );
                        } else {
                            log::warn!("recognize failed: {e:?}");
                        }
                        break;
                    }
                }
            };

            for (i, line) in lines.iter().enumerate() {
                let idx = start + i;
                if idx >= entries.len() {
                    break;
                }
                entries[idx].source_text = line.text.trim().to_string();
                entries[idx].rec_attempted = true;
                if is_auto_source {
                    if let Some(code) = &line.source_code {
                        entries[idx].source_code = code.clone();
                    }
                }
                let bi = block_of_entry[idx];
                if block_rec_remaining[bi] > 0 {
                    block_rec_remaining[bi] -= 1;
                }
            }

            if !gen_check() {
                return AcquirePipelineOutcome::canceled();
            }

            // Which blocks just finished rec'ing all their strips?
            let mut ready_blocks: Vec<usize> = (0..block_ids.len())
                .filter(|&bi| block_rec_remaining[bi] == 0 && !block_translated[bi])
                .collect();

            if !ready_blocks.is_empty() {
                // Build each ready block's source text by joining its
                // strips' rec'd texts. Newline join keeps reading
                // order; the translator treats each line as a sentence.
                let block_sources: Vec<String> = ready_blocks
                    .iter()
                    .map(|&bi| {
                        block_strip_indices[bi]
                            .iter()
                            .map(|&i| entries[i].source_text.as_str())
                            .filter(|t| !t.is_empty())
                            .collect::<Vec<_>>()
                            .join("\n")
                    })
                    .collect();
                // Drop blocks whose entire concat is empty (every
                // strip's rec failed). They stay as pending; we'll
                // skip them at the very end by not upserting.
                let kept: Vec<(usize, String)> = ready_blocks
                    .drain(..)
                    .zip(block_sources)
                    .filter(|(_, s)| !s.trim().is_empty())
                    .collect();
                if !kept.is_empty() {
                    let inputs: Vec<String> =
                        kept.iter().map(|(_, s)| s.clone()).collect();
                    let t_tr = Instant::now();
                    let forced = if is_auto_source {
                        None
                    } else {
                        Some(from_lang_code.as_str())
                    };
                    let result = catalog.session.translate_mixed_texts(
                        &inputs,
                        forced,
                        &to_lang_code,
                        &available_codes,
                    );
                    let tr_ms = t_tr.elapsed().as_secs_f64() * 1_000.0;
                    log::debug!(
                        "[acquire] block translate {:.1}ms blocks={}",
                        tr_ms,
                        kept.len(),
                    );
                    let by_src: std::collections::HashMap<String, String> = match result {
                        Ok(res) => res
                            .translations
                            .into_iter()
                            .map(|t| (t.source_text, t.translated_text))
                            .collect(),
                        Err(e) => {
                            log::warn!("translate batch failed: {e:?}");
                            std::collections::HashMap::new()
                        }
                    };
                    for (bi, src) in kept {
                        if !gen_check() {
                            return AcquirePipelineOutcome::canceled();
                        }
                        let translated = by_src.get(&src).cloned().unwrap_or_default();
                        // Only keep strips that actually rec'd
                        // something. Otherwise rec-failed strips
                        // still display as empty bg rects inside the
                        // block (visible as orange/teal placeholders
                        // floating where no text could be read). The
                        // renderer reflows the translation across the
                        // surviving strips only.
                        let kept_strips: Vec<translator::ocr::OrientedRect> =
                            block_strip_indices[bi]
                                .iter()
                                .filter(|&&i| !entries[i].source_text.is_empty())
                                .map(|&i| entries[i].tight.clone())
                                .collect();
                        if kept_strips.is_empty() {
                            continue;
                        }
                        self.upsert_overlay_block(
                            block_ids[bi],
                            kept_strips,
                            src,
                            translated,
                            to_lang_code.clone(),
                        );
                        block_translated[bi] = true;
                    }
                }
            }

            let batch_ms = t_batch.elapsed().as_secs_f64() * 1_000.0;
            let recd_ok = lines.iter().filter(|l| !l.text.trim().is_empty()).count();
            log::debug!(
                "[acquire] batch {}/{}: {:.1}ms rec_ok={}/{}",
                start / REC_BATCH_SIZE + 1,
                (total + REC_BATCH_SIZE - 1) / REC_BATCH_SIZE,
                batch_ms,
                recd_ok,
                end - start,
            );
            start = end;
        }

        // Drop any block whose final source text was entirely empty
        // (every strip's rec failed). Those still have a pending
        // placeholder on screen; remove them.
        let surviving_ids: Vec<u64> = block_ids
            .iter()
            .enumerate()
            .filter_map(|(bi, &id)| if block_translated[bi] { Some(id) } else { None })
            .collect();
        self.retain_overlay_items(surviving_ids);

        let rec_ok = entries
            .iter()
            .filter(|e| e.rec_attempted && !e.source_text.is_empty())
            .count();
        let rec_empty = entries
            .iter()
            .filter(|e| e.rec_attempted && e.source_text.is_empty())
            .count();

        if is_auto_source {
            let mut by_code: std::collections::BTreeMap<&str, usize> =
                std::collections::BTreeMap::new();
            for e in &entries {
                if e.rec_attempted && !e.source_text.is_empty() {
                    *by_code.entry(e.source_code.as_str()).or_default() += 1;
                }
            }
            let codes = if by_code.is_empty() {
                "<none>".to_string()
            } else {
                by_code
                    .iter()
                    .map(|(c, n)| format!("{}={}", c, n))
                    .collect::<Vec<_>>()
                    .join(",")
            };
            log::info!(
                "auto mode result: {} detections, rec_ok={} rec_empty={}, chose codes: {}",
                total,
                rec_ok,
                rec_empty,
                codes,
            );
        }

        // If nothing recognised, the tracker locked onto garbage. Clear
        // so the next stable frame re-acquires somewhere useful.
        if rec_ok == 0 && rec_empty + rec_ok == total {
            if let Ok(mut engine) = self.state.lock() {
                engine.clear();
            }
            self.clear_overlay();
        }

        AcquirePipelineOutcome {
            anchor_id,
            detected_count: total as u32,
            rec_ok_count: rec_ok as u32,
            rec_empty_count: rec_empty as u32,
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
        imu_rotation_dev: Vec<f32>,
        intrinsics_fx: f32,
        intrinsics_fy: f32,
        intrinsics_cx: f32,
        intrinsics_cy: f32,
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
        let cmd = {
            let mut state = frame.state.lock().map_err(|_| poisoned())?;
            ensure_oriented_locked(&mut state, display_crop, det_max_pixels)?;
            let oriented = state.cached.as_ref().expect("ensure_oriented filled cache");
            let mut engine = self.state.lock().map_err(|_| poisoned())?;
            if imu_rotation_dev.len() == 9 {
                let mut rot = [0.0f32; 9];
                rot.copy_from_slice(&imu_rotation_dev[..9]);
                let intr = translator::imu_prior::CameraIntrinsics {
                    fx: intrinsics_fx,
                    fy: intrinsics_fy,
                    cx: intrinsics_cx,
                    cy: intrinsics_cy,
                };
                engine.process_frame_with_imu(&oriented.gray, imu_stable, timestamp_ns, &rot, &intr)
            } else {
                engine.process_frame(&oriented.gray, imu_stable, timestamp_ns)
            }
        };
        let result = cmd_to_result(cmd);

        // 2. Decide which H to use for compositing, updating the
        // smoothed-H state along the way.
        let h_for_compose = self.select_compose_h(
            &result,
            display_width as f32,
            display_height as f32,
        );

        // 3. Composite. An empty `h` means "skip the overlay warp" —
        // we still want the camera frame on screen, so call composite
        // unconditionally and just hand it the appropriate H (or none).
        let h_vec: Vec<f32> = h_for_compose
            .map(|h| h.to_vec())
            .unwrap_or_default();
        let bytes = self.composite_frame(frame, display_width, display_height, h_vec);

        Ok(PlanarComposeResult {
            state: result.state,
            anchor_id: result.anchor_id,
            inliers: result.inliers,
            composite_byte_size: bytes,
        })
    }

    /// `h_surface_to_viewport` is a 9-element row-major homography; an
    /// empty slice (or length != 9) is treated as "no overlay this
    /// frame, just blit the camera".
    ///
    /// Returns the byte length of the composited buffer (0 on failure).
    fn composite_frame(
        &self,
        frame: Arc<FrameHandle>,
        display_width: u32,
        display_height: u32,
        h_surface_to_viewport: Vec<f32>,
    ) -> u32 {
        let frame_state_wait = Instant::now();
        let state = match frame.state.lock() {
            Ok(s) => s,
            Err(_) => return 0,
        };
        let frame_state_acquired = Instant::now();
        let sensor_w = state.width;
        let sensor_h = state.height;
        let rotation = state.rotation_degrees;
        if sensor_w == 0 || sensor_h == 0 {
            return 0;
        }
        let expected_src = (sensor_w as usize) * (sensor_h as usize) * 4;
        if state.rgba.len() != expected_src {
            return 0;
        }
        let dst_bytes = (display_width as usize) * (display_height as usize) * 4;
        let mut dst = vec![0u8; dst_bytes];
        let overlay_wait = Instant::now();
        let overlay_guard = self.current_overlay_items.lock().ok();
        let overlay_acquired = Instant::now();
        let h_arr: Option<[f32; 9]> = if h_surface_to_viewport.len() == 9 {
            let mut a = [0.0f32; 9];
            a.copy_from_slice(&h_surface_to_viewport[..9]);
            Some(a)
        } else {
            None
        };
        let items_vec: Vec<translator::live_compositor::OverlayItem<'_>> =
            match (&overlay_guard, &h_arr) {
                (Some(items), Some(_)) => items
                    .iter()
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
        let h_for_call = h_arr.unwrap_or([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]);
        let composite_start = Instant::now();
        let result = translator::live_compositor::composite_frame_into(
            &mut dst,
            display_width,
            display_height,
            &state.rgba,
            sensor_w,
            sensor_h,
            rotation,
            &h_for_call,
            &items_vec,
        );
        let composite_end = Instant::now();
        let items_count = items_vec.len();
        drop(items_vec);
        drop(overlay_guard);
        let overlay_released = Instant::now();
        log_lock_timing(
            "frame.state/composite_frame",
            frame_state_acquired - frame_state_wait,
            composite_end - frame_state_acquired,
        );
        log_lock_timing(
            "current_overlay_items/composite_frame",
            overlay_acquired - overlay_wait,
            overlay_released - overlay_acquired,
        );
        if LIVE_PIPELINE_DIAG {
            let composite_ms = (composite_end - composite_start).as_secs_f64() * 1_000.0;
            if composite_ms > LOCK_LOG_THRESHOLD_MS {
                log::debug!(
                    "[work] composite_frame body: {:.1}ms ({}x{}, items={})",
                    composite_ms,
                    display_width,
                    display_height,
                    items_count,
                );
            }
        }
        if result.is_err() {
            return 0;
        }
        let len = dst.len() as u32;
        if let Ok(mut slot) = self.pending_display.lock() {
            *slot = Some(dst);
        }
        len
    }

    /// Exposes the pending-display slot to the JNI shim (same crate).
    pub(crate) fn take_pending_display(&self) -> Option<Vec<u8>> {
        self.pending_display.lock().ok().and_then(|mut s| s.take())
    }

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

    /// Like `process_frame` but seeds RANSAC with an IMU-derived
    /// homography prior. `imu_rotation_dev` is the 9-element row-major
    /// device-frame rotation matrix at this camera frame (the Android
    /// gyro fusion output, e.g. from
    /// `ImuService.currentRotation`). `intrinsics` are the camera's
    /// fx/fy/cx/cy in the same pixel space as the analyser frame
    /// (after rotation to display orientation). Pass empty
    /// `imu_rotation_dev` (length != 9) to disable the prior for that
    /// frame.
    fn process_frame_with_imu(
        &self,
        frame: Arc<FrameHandle>,
        display_crop: translator::Rect,
        det_max_pixels: u32,
        imu_stable: bool,
        timestamp_ns: u64,
        imu_rotation_dev: Vec<f32>,
        intrinsics_fx: f32,
        intrinsics_fy: f32,
        intrinsics_cx: f32,
        intrinsics_cy: f32,
    ) -> Result<PlanarFrameResult, CatalogError> {
        let mut state = frame.state.lock().map_err(|_| poisoned())?;
        ensure_oriented_locked(&mut state, display_crop, det_max_pixels)?;
        let oriented = state.cached.as_ref().expect("ensure_oriented filled cache");
        let engine_wait_start = Instant::now();
        let mut engine = self.state.lock().map_err(|_| poisoned())?;
        let engine_acquired = Instant::now();
        let cmd = if imu_rotation_dev.len() == 9 {
            let mut rot = [0.0f32; 9];
            rot.copy_from_slice(&imu_rotation_dev[..9]);
            let intr = translator::imu_prior::CameraIntrinsics {
                fx: intrinsics_fx,
                fy: intrinsics_fy,
                cx: intrinsics_cx,
                cy: intrinsics_cy,
            };
            engine.process_frame_with_imu(&oriented.gray, imu_stable, timestamp_ns, &rot, &intr)
        } else {
            engine.process_frame(&oriented.gray, imu_stable, timestamp_ns)
        };
        let engine_released = Instant::now();
        drop(engine);
        log_lock_timing(
            "engine/process_frame_with_imu",
            engine_acquired - engine_wait_start,
            engine_released - engine_acquired,
        );
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

/// Frames of sustained tracker LOST before we hide the overlay (vs
/// keep showing the last good H so a single missed frame doesn't
/// flicker). ~270 ms @ 30 fps.
#[cfg(feature = "planar-tracker")]
const LOSS_HIDE_AFTER_FRAMES: u32 = 8;

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
            total_ms: 0.0,
            canceled: false,
            error: Some(reason.to_string()),
        }
    }
}

/// Local per-detection state owned by `run_acquire_pipeline`. One
/// `AcquireEntry` per detected line: stores the tight rect (the
/// strip), the source text once rec lands, the detected source code
/// (auto-source mode), and the original `DetectedTextBox` we need to
/// hand back to `recognize_in_oriented_image` (it works in the
/// detector's full-crop coord space).
///
/// Blocks group multiple `AcquireEntry`s by index — the `id` field
/// here is no longer the overlay-block id (those are minted later in
/// the pipeline, one per block, not one per entry).
#[cfg(feature = "planar-tracker")]
struct AcquireEntry {
    tight: translator::ocr::OrientedRect,
    source_text: String,
    /// Set to the per-line detected source from rec when running in
    /// auto-source mode; otherwise stays as the caller's
    /// `from_lang_code`. Unused right now but kept so a future
    /// content map can record mixed-language detections per strip.
    #[allow(dead_code)]
    source_code: String,
    rec_attempted: bool,
    rec_box: translator::DetectedTextBox,
}

/// Visual-box tuning: the detector's `tight` rect covers ink-only
/// extent. We inflate it vertically to leave room for ascenders /
/// descenders and add a small horizontal pad so the rendered text
/// doesn't sit flush against the rounded-rect bg edge. Matches what
/// Kotlin used to do client-side (`TIGHT_VERTICAL_INFLATE` +
/// `HORIZONTAL_PAD_PX`); centralised here now that Rust owns
/// rendering.
#[cfg(feature = "planar-tracker")]
const TIGHT_VERTICAL_INFLATE: f32 = 2.4;
#[cfg(feature = "planar-tracker")]
const HORIZONTAL_PAD_PX: f32 = 8.0;
/// Pad around each item's visual quad when sizing the bitmap. Gives
/// the rounded-corner AA + alpha edges a couple of pixels of buffer
/// so we don't clip during the warp.
#[cfg(feature = "planar-tracker")]
const ITEM_BITMAP_PAD_PX: f32 = 4.0;
/// Debug toggle: render the source text instead of waiting on
/// translation. Matches the old Kotlin `DEBUG_TRACKER_VIEW_PLANAR`.
#[cfg(feature = "planar-tracker")]
const RENDER_SOURCE_AS_FALLBACK: bool = false;

/// Pick the string to render for a given item. Translation wins when
/// available; otherwise we render source text (handy for diagnosing
/// detection / recognition without waiting for translation).
#[cfg(feature = "planar-tracker")]
fn pick_display_text(source_text: &str, translated_text: &str) -> String {
    if !translated_text.trim().is_empty() {
        translated_text.to_string()
    } else if RENDER_SOURCE_AS_FALLBACK {
        source_text.to_string()
    } else {
        String::new()
    }
}

/// Content hash for `upsert_overlay_block` change detection. Same
/// hash → same rasterized bitmap, no need to re-render. Hashes the
/// strip list verbatim so any change in strip geometry, count, or
/// order forces a re-raster.
#[cfg(feature = "planar-tracker")]
fn block_content_hash(
    strips: &[translator::ocr::OrientedRect],
    display_text: &str,
    language: &str,
) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    (strips.len() as u64).hash(&mut h);
    for s in strips {
        s.cx.to_bits().hash(&mut h);
        s.cy.to_bits().hash(&mut h);
        s.width.to_bits().hash(&mut h);
        s.height.to_bits().hash(&mut h);
        s.angle_radians.to_bits().hash(&mut h);
    }
    display_text.hash(&mut h);
    language.hash(&mut h);
    h.finish()
}

/// Per-item raster result: an RGBA bitmap with bounded dimensions
/// plus the surface-coord position of its top-left pixel.
#[cfg(feature = "planar-tracker")]
struct ItemRaster {
    bitmap: Vec<u8>,
    width: u32,
    height: u32,
    surface_origin_x: f32,
    surface_origin_y: f32,
}

/// Inflate the detector's tight box into a visual box and rasterize a
/// single item's overlay onto its own small bitmap. Returns `None`
/// when the visual box is degenerate or `render_text_overlay_bitmap`
/// fails.
#[cfg(feature = "planar-tracker")]
/// Rasterize a *block*: N per-line strips share one bitmap, one
/// `translated_text`, and one set of background fills (one per strip).
/// The text gets reflowed across the strips by `image_render` using
/// the strips' widths as target line widths — so a paragraph that
/// translates to fewer/more words than the source still reads as a
/// paragraph, just using more or fewer of the available line slots.
///
/// `strips` must be ordered top-to-bottom (the natural reading
/// order — the renderer assigns words to slots in that order).
/// Single-strip blocks are not special-cased; pass a 1-element vec.
///
/// `display_text` is the translation; when empty, the block renders
/// as a "pending" placeholder (per-strip bg fills, no glyphs).
fn render_block_bitmap(
    strips: &[translator::ocr::OrientedRect],
    display_text: &str,
    language: &str,
) -> Option<ItemRaster> {
    use translator::ocr::{
        OrientedRect, OverlayLayoutHints, OverlayLayoutMode, PreparedImageOverlay,
        PreparedTextBlock, PreparedTextLine, Rect,
    };
    if strips.is_empty() {
        return None;
    }

    // 1. Inflate each strip into a "visual" box (vertical extra for
    //    ascenders/descenders, horizontal pad so glyphs don't kiss
    //    the rounded bg edge). Mirrors the old single-strip logic.
    let visuals: Vec<OrientedRect> = strips
        .iter()
        .filter_map(|s| {
            let v = OrientedRect {
                cx: s.cx,
                cy: s.cy,
                width: s.width + 2.0 * HORIZONTAL_PAD_PX,
                height: s.height * TIGHT_VERTICAL_INFLATE,
                angle_radians: s.angle_radians,
            };
            if v.width <= 0.0 || v.height <= 0.0 {
                None
            } else {
                Some(v)
            }
        })
        .collect();
    if visuals.is_empty() {
        return None;
    }

    // 2. Bitmap dims = AABB of all visual strips + small pad for the
    //    rounded-corner AA. The bitmap origin in surface coords is
    //    what the compositor uses to warp onto the camera frame.
    let (mut min_x, mut min_y) = (f32::INFINITY, f32::INFINITY);
    let (mut max_x, mut max_y) = (f32::NEG_INFINITY, f32::NEG_INFINITY);
    for v in &visuals {
        for (x, y) in oriented_corners(v) {
            min_x = min_x.min(x);
            min_y = min_y.min(y);
            max_x = max_x.max(x);
            max_y = max_y.max(y);
        }
    }
    let pad = ITEM_BITMAP_PAD_PX;
    let origin_x = (min_x - pad).max(0.0);
    let origin_y = (min_y - pad).max(0.0);
    let bitmap_w = ((max_x + pad - origin_x).ceil() as i32).max(1) as u32;
    let bitmap_h = ((max_y + pad - origin_y).ceil() as i32).max(1) as u32;

    // 3. Start the canvas + paint per-strip bg fills. The bg fill is
    //    the same colour for every strip in the block; only their
    //    positions differ. This gives the per-strip "label box" look,
    //    where each detected line gets its own rounded rect — even
    //    when they all share one translation.
    let pixels = (bitmap_w as usize) * (bitmap_h as usize);
    let mut rgba = vec![0u8; pixels * 4];
    let bg_color = [0x10, 0x10, 0x10, 0xC8];
    let visuals_local: Vec<OrientedRect> = visuals
        .iter()
        .map(|v| OrientedRect {
            cx: v.cx - origin_x,
            cy: v.cy - origin_y,
            width: v.width,
            height: v.height,
            angle_radians: v.angle_radians,
        })
        .collect();
    for v in &visuals_local {
        translator::planar_engine::fill_oriented_rect_blended(
            &mut rgba, bitmap_w, bitmap_h, v, bg_color,
        );
    }

    // 4. If no text yet (pending placeholder), we're done — return the
    //    bitmap with just the bg fills painted.
    if display_text.trim().is_empty() {
        return Some(ItemRaster {
            bitmap: rgba,
            width: bitmap_w,
            height: bitmap_h,
            surface_origin_x: origin_x,
            surface_origin_y: origin_y,
        });
    }

    // 5. Build a `PreparedTextBlock` with one `PreparedTextLine` per
    //    strip. `OverlayLayoutMode::PerLine` will reflow the block's
    //    `translated_text` across these slots, word by word, using
    //    each line's `oriented_box.width` as the slot's target width.
    //    Horizontally inset the text box (not the bg) so the text
    //    doesn't sit flush against the rounded edge.
    let lines: Vec<PreparedTextLine> = visuals_local
        .iter()
        .map(|v| {
            let text_box = OrientedRect {
                cx: v.cx,
                cy: v.cy,
                width: (v.width - 2.0 * translator::planar_engine::OVERLAY_TEXT_HORIZONTAL_INSET_PX).max(1.0),
                height: v.height,
                angle_radians: v.angle_radians,
            };
            let aabb = text_box.to_aabb();
            let bbox = Rect {
                left: aabb.left.min(bitmap_w.saturating_sub(1)),
                top: aabb.top.min(bitmap_h.saturating_sub(1)),
                right: aabb.right.min(bitmap_w),
                bottom: aabb.bottom.min(bitmap_h),
            };
            PreparedTextLine {
                // Per-line text is unused — the block's `translated_text`
                // is what `render_per_line` reflows across slots.
                text: String::new(),
                bounding_box: bbox.clone(),
                oriented_box: text_box,
                word_rects: vec![bbox],
                background_argb: 0,
                foreground_argb: 0xFFFF_FFFF,
            }
        })
        .collect();
    // Suggested font size: derive from the dominant strip height so
    // long paragraphs (where strips agree) get a stable size, and
    // mixed-height detections (rare; headings + body in one block)
    // settle on the larger one.
    let suggested_font_px = visuals
        .iter()
        .map(|v| v.height)
        .fold(0.0_f32, f32::max)
        .clamp(10.0, 120.0);
    let block_bbox = Rect {
        left: 0,
        top: 0,
        right: bitmap_w,
        bottom: bitmap_h,
    };
    let block = PreparedTextBlock {
        source_text: String::new(),
        translated_text: display_text.to_string(),
        bounding_box: block_bbox,
        lines,
        layout_hints: OverlayLayoutHints {
            layout_mode: OverlayLayoutMode::PerLine,
            suggested_font_size_px: suggested_font_px,
        },
        background_argb: bg_argb_u32(),
        foreground_argb: 0xFFFF_FFFF,
    };

    // 6. Run the shared `image_render::render_overlay` rasterizer.
    //    Its `PerLine` mode treats the block's translated text as one
    //    string and greedy-breaks it across our line slots.
    let prepared = PreparedImageOverlay {
        rgba_bytes: rgba,
        width: bitmap_w,
        height: bitmap_h,
        extracted_text: String::new(),
        translated_text: String::new(),
        blocks: vec![block],
    };
    let opts = translator::image_render::RenderOptions {
        language: language.to_string(),
        min_font_size_px: 6.0,
    };
    let final_bytes = translator::image_render::render_overlay(
        &prepared,
        &crate::android_font_provider::AndroidFontProvider,
        &opts,
    )
    .ok()?;
    Some(ItemRaster {
        bitmap: final_bytes,
        width: bitmap_w,
        height: bitmap_h,
        surface_origin_x: origin_x,
        surface_origin_y: origin_y,
    })
}

#[cfg(feature = "planar-tracker")]
fn bg_argb_u32() -> u32 {
    0xC810_1010
}

/// Group detected entries (one per line) into translation blocks
/// (paragraphs / labels). Returns `Vec<Vec<usize>>` where each inner
/// vec is a block's entry indices in reading order (top → bottom).
///
/// We feed empty `text` to `ocr::group_live_lines_into_blocks` because
/// rec hasn't happened yet — grouping is pure geometry (baseline,
/// vertical adjacency, horizontal alignment). The `is_live_measurement_token`
/// special-case inside the grouper short-circuits on empty text, so we
/// just lose the "don't merge mg/g/kg into the body" guard. That's
/// acceptable for the live-camera UX; if it becomes an issue we can
/// regroup after rec.
///
/// Output ordering: blocks in top → bottom order, strips within a
/// block also top → bottom. Stable across acquires on the same
/// anchor (surface coords don't change).
#[cfg(feature = "planar-tracker")]
fn group_entries_into_blocks(entries: &[AcquireEntry]) -> Vec<Vec<usize>> {
    use translator::ocr::TextLine;
    if entries.is_empty() {
        return Vec::new();
    }
    let lines: Vec<TextLine> = entries
        .iter()
        .map(|e| TextLine {
            text: String::new(),
            bounding_box: e.rec_box.rect,
            oriented_box: e.rec_box.oriented_box,
            tight_box: e.tight,
            word_rects: Vec::new(),
        })
        .collect();
    let blocks = translator::ocr::group_live_lines_into_blocks(lines);
    blocks
        .into_iter()
        .map(|b| {
            // Map back to indices in `entries` by tight_box equality.
            // Each detected box has a unique cx/cy so equality is
            // exact. If a line doesn't match (shouldn't happen), drop
            // it from the block rather than panic.
            b.lines
                .iter()
                .filter_map(|l| entries.iter().position(|e| e.tight == l.tight_box))
                .collect()
        })
        .filter(|v: &Vec<usize>| !v.is_empty())
        .collect()
}

/// Compute the four (TL, TR, BR, BL) corners of an OrientedRect in
/// surface coords. Mirrors the Kotlin `orientedCornersFlat`.
#[cfg(feature = "planar-tracker")]
fn oriented_corners(o: &translator::ocr::OrientedRect) -> [(f32, f32); 4] {
    let c = o.angle_radians.cos();
    let s = o.angle_radians.sin();
    let hw = o.width * 0.5;
    let hh = o.height * 0.5;
    let mut out = [(0.0, 0.0); 4];
    let locals = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)];
    for (i, &(lx, ly)) in locals.iter().enumerate() {
        let rx = c * lx - s * ly;
        let ry = s * lx + c * ly;
        out[i] = (o.cx + rx, o.cy + ry);
    }
    out
}


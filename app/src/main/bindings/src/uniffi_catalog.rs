use std::sync::Arc;
use std::time::Instant;
use std::{fs, path::Path};

/// Log threshold for mutex wait/hold times in the live pipeline. We
/// emit a single info line when either the wait or the hold exceeds
/// this, so steady-state runs stay quiet but a stall surfaces with the
/// label of the contending call site.
#[cfg(feature = "planar-tracker")]
const LOCK_LOG_THRESHOLD_MS: f64 = 3.0;

#[cfg(feature = "planar-tracker")]
fn log_lock_timing(label: &str, wait: std::time::Duration, hold: std::time::Duration) {
    let wait_ms = wait.as_secs_f64() * 1_000.0;
    let hold_ms = hold.as_secs_f64() * 1_000.0;
    if wait_ms > LOCK_LOG_THRESHOLD_MS || hold_ms > LOCK_LOG_THRESHOLD_MS {
        log::info!(
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

    /// Exposes the inner state mutex to the JNI shim (same crate). Not part of
    /// the uniffi-visible surface.
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
#[derive(uniffi::Record, Clone)]
pub struct PlanarOverlayInput {
    pub id: u64,
    /// 8 floats = 4 corners (x,y) in canonical-frame coords:
    /// [tlx, tly, trx, try, brx, bry, blx, bly].
    pub quad: Vec<f32>,
    pub payload: String,
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
    /// Legacy slot for `prepare_text_overlay_render` →
    /// `PlanarRenderJni.renderInto`. Still used by the old bitmap-
    /// overlay path while the composite-in-Rust refactor migrates.
    /// Kept for compatibility; will retire with task #6.
    pending_bitmap: std::sync::Mutex<Option<Vec<u8>>>,
    /// Per-item resident rasterized overlays for the composite
    /// pipeline. Each item is keyed by its stable id; `upsert_overlay_item`
    /// adds or replaces, `retain_overlay_items` drops anything outside
    /// a set. Persisted across many composite calls so dense pages only
    /// re-rasterize the handful of items whose text changed in the
    /// latest rec batch, not the whole set.
    current_overlay_items: std::sync::Mutex<Vec<CurrentOverlayItem>>,
    /// Bumped on every `reset()` (tap-to-focus, language change, etc.).
    /// In-flight acquire pipelines pass the generation they captured at
    /// launch as a parameter; before each potentially-slow step
    /// (detect, recognize, translate) the pipeline checks whether the
    /// generation has moved on and bails if so. Eliminates the
    /// Kotlin-side `globalGeneration` + per-step generation check
    /// scaffolding.
    generation: std::sync::atomic::AtomicU64,
    /// Per-entry id source for items shipped to `upsert_overlay_item`.
    /// We keep stable ids across rec batches so each item's rasterized
    /// bitmap is reused unchanged when only its translation is added.
    next_entry_id: std::sync::atomic::AtomicU64,
    /// Output buffer produced by `composite_frame` (uniffi) and
    /// consumed by `Java_..._PlanarRenderJni_compositeInto` (JNI).
    /// Holds the fully-composited camera + overlay RGBA at display
    /// resolution. Same Vec<u8>-via-JNI-memcpy trick as `pending_bitmap`
    /// to skip uniffi marshalling of an 8 MB buffer per frame.
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
            pending_bitmap: std::sync::Mutex::new(None),
            current_overlay_items: std::sync::Mutex::new(Vec::new()),
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

    /// Render the text-overlay bitmap and store it for retrieval by the
    /// JNI fast-path. Pair with `PlanarRenderJni.renderInto` — that
    /// reads `pending_bitmap` and memcpys it into a Kotlin-owned
    /// DirectByteBuffer, skipping the uniffi `Vec<u8>` marshalling +
    /// JVM ByteArray allocation we'd otherwise pay 3–4 times per
    /// acquire (each bitmap is ~800 KB – 1.2 MB).
    ///
    /// Returns the byte length of the rendered bitmap, or 0 on failure
    /// (zero dims, font provider exhausted). Caller must size its
    /// destination buffer to `width * height * 4`.
    fn prepare_text_overlay_render(
        &self,
        frame_width: u32,
        frame_height: u32,
        items: Vec<PlanarTextRenderItem>,
    ) -> u32 {
        let engine_items: Vec<translator::planar_engine::TextRenderItem> = items
            .into_iter()
            .filter_map(|it| {
                if it.quad.len() != 8 {
                    return None;
                }
                Some(translator::planar_engine::TextRenderItem {
                    id: it.id,
                    quad: [
                        (it.quad[0], it.quad[1]),
                        (it.quad[2], it.quad[3]),
                        (it.quad[4], it.quad[5]),
                        (it.quad[6], it.quad[7]),
                    ],
                    translated_text: it.translated_text,
                    source_text: it.source_text,
                    language: it.language,
                    bg_argb: it.bg_argb,
                    fg_argb: it.fg_argb,
                    suggested_font_px: it.suggested_font_px,
                })
            })
            .collect();
        // Rasterize without locking the engine — see the matching
        // comment in `prepare_overlay_for_composite` for why.
        let bytes = match translator::planar_engine::render_text_overlay_bitmap(
            frame_width,
            frame_height,
            &engine_items,
            &crate::android_font_provider::AndroidFontProvider,
        ) {
            Some(b) => b,
            None => return 0,
        };
        let len = bytes.len() as u32;
        if let Ok(mut slot) = self.pending_bitmap.lock() {
            *slot = Some(bytes);
        }
        len
    }

    /// Exposes the pending-bitmap slot to the JNI shim (same crate).
    /// Not part of the uniffi-visible surface — uniffi can't see
    /// `pub(crate)`.
    pub(crate) fn take_pending_bitmap(&self) -> Option<Vec<u8>> {
        self.pending_bitmap.lock().ok().and_then(|mut s| s.take())
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
    fn upsert_overlay_item(
        &self,
        id: u64,
        tight: translator::ocr::OrientedRect,
        source_text: String,
        translated_text: String,
        language: String,
    ) {
        let display_text = pick_display_text(&source_text, &translated_text);
        let hash = item_content_hash(&tight, &display_text, &language);
        // Fast path: same content as last time → keep the bitmap.
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
        let raster = match render_one_item_bitmap(&tight, &display_text, &language) {
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
        let raster_ms = (raster_end - raster_start).as_secs_f64() * 1_000.0;
        if raster_ms > LOCK_LOG_THRESHOLD_MS {
            log::info!(
                "[work] item raster: id={} {:.1}ms text={:?}",
                id,
                raster_ms,
                display_text,
            );
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
        log::info!(
            "[acquire] detect: {:.1}ms found={}",
            detect_ms,
            detected.len()
        );

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
        log::info!("[acquire] acquire_now: {:.1}ms id={}", acquire_ms, anchor_id);

        if anchor_id == 0 {
            return AcquirePipelineOutcome::error("acquire_now returned 0");
        }
        if !gen_check() {
            return AcquirePipelineOutcome::canceled();
        }

        // Build per-entry state — local to this pipeline call. Pushed
        // to the Rust overlay slot via `upsert_overlay_item` after each
        // batch so the user sees text light up progressively. (Type
        // defined at module scope so the `AcquireEntryView` impl is
        // visible to `push_entries_to_overlay`.)
        let mut entries: Vec<AcquireEntry> = detected
            .into_iter()
            .map(|d| AcquireEntry {
                id: self
                    .next_entry_id
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed),
                tight: d.tight_box.clone(),
                source_text: String::new(),
                source_code: from_lang_code.clone(),
                translated_text: String::new(),
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

        // ---- Rec + translate batches ----
        const REC_BATCH_SIZE: usize = 4;
        let mut start = 0;
        while start < total {
            if !gen_check() {
                return AcquirePipelineOutcome::canceled();
            }
            let end = (start + REC_BATCH_SIZE).min(total);
            let t_batch = Instant::now();

            // 1. Recognize this batch.
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
                        log::warn!("recognize failed: {e:?}");
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
            }

            if !gen_check() {
                return AcquirePipelineOutcome::canceled();
            }

            // 2. Translate the batch in one shot (slimt batches
            // internally; one call is dramatically faster than N
            // sequential calls).
            let texts_to_translate: Vec<String> = entries[start..end]
                .iter()
                .filter(|e| !e.source_text.is_empty())
                .map(|e| e.source_text.clone())
                .collect();
            if !texts_to_translate.is_empty() {
                let t_tr = Instant::now();
                let forced = if is_auto_source {
                    None
                } else {
                    Some(from_lang_code.as_str())
                };
                let result = catalog.session.translate_mixed_texts(
                    &texts_to_translate,
                    forced,
                    &to_lang_code,
                    &available_codes,
                );
                let tr_ms = t_tr.elapsed().as_secs_f64() * 1_000.0;
                match result {
                    Ok(res) => {
                        let by_src: std::collections::HashMap<String, String> = res
                            .translations
                            .into_iter()
                            .map(|t| (t.source_text, t.translated_text))
                            .collect();
                        for entry in entries[start..end].iter_mut() {
                            if entry.source_text.is_empty() {
                                continue;
                            }
                            if let Some(translated) = by_src.get(&entry.source_text) {
                                entry.translated_text = translated.clone();
                            }
                        }
                        log::info!(
                            "[acquire] batch translate {:.1}ms in_count={}",
                            tr_ms,
                            texts_to_translate.len(),
                        );
                    }
                    Err(e) => {
                        log::warn!("translate batch failed: {e:?}");
                    }
                }
            }

            // 3. Push the batch's freshly-known items to the overlay
            // slot. We upsert all renderable items (idempotent for
            // ones whose content hash hasn't changed) and retain the
            // union of all entry ids.
            push_entries_to_overlay(self, &entries, &to_lang_code);

            let batch_ms = t_batch.elapsed().as_secs_f64() * 1_000.0;
            let recd_ok = lines.iter().filter(|l| !l.text.trim().is_empty()).count();
            log::info!(
                "[acquire] batch {}/{}: {:.1}ms rec_ok={}/{}",
                start / REC_BATCH_SIZE + 1,
                (total + REC_BATCH_SIZE - 1) / REC_BATCH_SIZE,
                batch_ms,
                recd_ok,
                end - start,
            );
            start = end;
        }

        let rec_ok = entries
            .iter()
            .filter(|e| e.rec_attempted && !e.source_text.is_empty())
            .count();
        let rec_empty = entries
            .iter()
            .filter(|e| e.rec_attempted && e.source_text.is_empty())
            .count();

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
    /// buffer must be sized `display_width * display_height * 4`.
    ///
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
        let composite_ms = (composite_end - composite_start).as_secs_f64() * 1_000.0;
        if composite_ms > LOCK_LOG_THRESHOLD_MS {
            log::info!(
                "[work] composite_frame body: {:.1}ms ({}x{}, items={})",
                composite_ms,
                display_width,
                display_height,
                items_count,
            );
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

    /// Attach the canonical-frame overlays for an anchor (one call per
    /// OCR pass). Returns false if `anchor_id` is no longer in the LRU
    /// cache.
    fn set_overlays(&self, anchor_id: u64, overlays: Vec<PlanarOverlayInput>) -> bool {
        let mut engine = match self.state.lock() {
            Ok(g) => g,
            Err(_) => return false,
        };
        let converted: Vec<translator::planar_engine::CanonicalOverlay> = overlays
            .into_iter()
            .filter_map(overlay_input_to_canonical)
            .collect();
        engine.set_overlays(anchor_id, converted)
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

#[cfg(feature = "planar-tracker")]
fn overlay_input_to_canonical(
    o: PlanarOverlayInput,
) -> Option<translator::planar_engine::CanonicalOverlay> {
    if o.quad.len() != 8 {
        return None;
    }
    Some(translator::planar_engine::CanonicalOverlay {
        id: o.id,
        quad: [
            (o.quad[0], o.quad[1]),
            (o.quad[2], o.quad[3]),
            (o.quad[4], o.quad[5]),
            (o.quad[6], o.quad[7]),
        ],
        payload: o.payload,
    })
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

/// Push the current entry state to the resident overlay slot. Used by
/// `run_acquire_pipeline` after each rec/translate batch so the user
/// sees text light up progressively.
#[cfg(feature = "planar-tracker")]
fn push_entries_to_overlay<E>(tracker: &LivePlanarTracker, entries: &[E], language: &str)
where
    E: AcquireEntryView,
{
    let mut retained: Vec<u64> = Vec::with_capacity(entries.len());
    for entry in entries {
        if entry.source_text().is_empty() && entry.rec_attempted() {
            // Empty rec result — drop from the renderable set.
            continue;
        }
        retained.push(entry.id());
        tracker.upsert_overlay_item(
            entry.id(),
            entry.tight().clone(),
            entry.source_text().to_string(),
            entry.translated_text().to_string(),
            language.to_string(),
        );
    }
    tracker.retain_overlay_items(retained);
}

/// Trait-of-fields so `push_entries_to_overlay` doesn't depend on the
/// pipeline's concrete entry layout — keeps the helper testable.
#[cfg(feature = "planar-tracker")]
trait AcquireEntryView {
    fn id(&self) -> u64;
    fn tight(&self) -> &translator::ocr::OrientedRect;
    fn source_text(&self) -> &str;
    fn translated_text(&self) -> &str;
    fn rec_attempted(&self) -> bool;
}

/// Local per-detection state owned by `run_acquire_pipeline`. Holds
/// the detector output plus the rec/translate progress so we can push
/// progressive overlay updates after each batch without losing the
/// per-entry ids (which would invalidate the per-item raster cache).
#[cfg(feature = "planar-tracker")]
struct AcquireEntry {
    id: u64,
    tight: translator::ocr::OrientedRect,
    source_text: String,
    /// Set to the per-line detected source from rec when running in
    /// auto-source mode; otherwise stays as the caller's
    /// `from_lang_code`. Unused right now but kept so a future content
    /// map can record mixed-language detections per item.
    #[allow(dead_code)]
    source_code: String,
    translated_text: String,
    rec_attempted: bool,
    /// The full detector record (rect, contour, score, oriented
    /// boxes). Needed for the recognize call, which works in the
    /// detector's full-crop coord space.
    rec_box: translator::DetectedTextBox,
}

#[cfg(feature = "planar-tracker")]
impl AcquireEntryView for AcquireEntry {
    fn id(&self) -> u64 {
        self.id
    }
    fn tight(&self) -> &translator::ocr::OrientedRect {
        &self.tight
    }
    fn source_text(&self) -> &str {
        &self.source_text
    }
    fn translated_text(&self) -> &str {
        &self.translated_text
    }
    fn rec_attempted(&self) -> bool {
        self.rec_attempted
    }
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

/// Content hash for `upsert_overlay_item` change detection. Same
/// hash → same rasterized bitmap, no need to re-render.
#[cfg(feature = "planar-tracker")]
fn item_content_hash(
    tight: &translator::ocr::OrientedRect,
    display_text: &str,
    language: &str,
) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    tight.cx.to_bits().hash(&mut h);
    tight.cy.to_bits().hash(&mut h);
    tight.width.to_bits().hash(&mut h);
    tight.height.to_bits().hash(&mut h);
    tight.angle_radians.to_bits().hash(&mut h);
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
fn render_one_item_bitmap(
    tight: &translator::ocr::OrientedRect,
    display_text: &str,
    language: &str,
) -> Option<ItemRaster> {
    let visual = translator::ocr::OrientedRect {
        cx: tight.cx,
        cy: tight.cy,
        width: tight.width + 2.0 * HORIZONTAL_PAD_PX,
        height: tight.height * TIGHT_VERTICAL_INFLATE,
        angle_radians: tight.angle_radians,
    };
    if visual.width <= 0.0 || visual.height <= 0.0 {
        return None;
    }
    let corners = oriented_corners(&visual);
    let (mut min_x, mut min_y) = (f32::INFINITY, f32::INFINITY);
    let (mut max_x, mut max_y) = (f32::NEG_INFINITY, f32::NEG_INFINITY);
    for (x, y) in &corners {
        if *x < min_x { min_x = *x; }
        if *y < min_y { min_y = *y; }
        if *x > max_x { max_x = *x; }
        if *y > max_y { max_y = *y; }
    }
    let pad = ITEM_BITMAP_PAD_PX;
    let origin_x = (min_x - pad).max(0.0);
    let origin_y = (min_y - pad).max(0.0);
    let bitmap_w = ((max_x + pad - origin_x).ceil() as i32).max(1) as u32;
    let bitmap_h = ((max_y + pad - origin_y).ceil() as i32).max(1) as u32;

    // Translate the visual quad into bitmap-local coords for the
    // rasterizer.
    let mut local_quad = [0.0f32; 8];
    for (i, (x, y)) in corners.iter().enumerate() {
        local_quad[i * 2] = x - origin_x;
        local_quad[i * 2 + 1] = y - origin_y;
    }
    let suggested_font_px = visual.height.clamp(10.0, 120.0);
    let item = translator::planar_engine::TextRenderItem {
        id: 0,
        quad: [
            (local_quad[0], local_quad[1]),
            (local_quad[2], local_quad[3]),
            (local_quad[4], local_quad[5]),
            (local_quad[6], local_quad[7]),
        ],
        translated_text: display_text.to_string(),
        source_text: String::new(),
        language: language.to_string(),
        bg_argb: 0xC8101010,
        fg_argb: 0xFFFF_FFFF,
        suggested_font_px,
    };
    let bytes = translator::planar_engine::render_text_overlay_bitmap(
        bitmap_w,
        bitmap_h,
        std::slice::from_ref(&item),
        &crate::android_font_provider::AndroidFontProvider,
    )?;
    Some(ItemRaster {
        bitmap: bytes,
        width: bitmap_w,
        height: bitmap_h,
        surface_origin_x: origin_x,
        surface_origin_y: origin_y,
    })
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

#[cfg(all(test, feature = "ppocr"))]
mod live_grouping_tests {
    use super::*;

    fn line(track_id: u64, left: u32, top: u32, right: u32, bottom: u32) -> LiveTextLineInput {
        let rect = translator::Rect {
            left,
            top,
            right,
            bottom,
        };
        let oriented = translator::ocr::OrientedRect::axis_aligned(rect);
        let tight = translator::ocr::OrientedRect {
            cx: oriented.cx,
            cy: oriented.cy,
            width: oriented.width,
            height: (oriented.height * 0.55).max(1.0),
            angle_radians: oriented.angle_radians,
        };
        LiveTextLineInput {
            track_id,
            rect,
            oriented_box: oriented,
            tight_box: tight,
        }
    }

    #[test]
    fn live_grouping_keeps_three_stacked_label_lines_in_one_group() {
        let groups = group_live_text_lines(vec![
            line(10, 100, 100, 260, 126),
            line(11, 101, 132, 258, 158),
            line(12, 99, 164, 255, 190),
        ]);

        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].track_ids, vec![10, 11, 12]);
    }
}

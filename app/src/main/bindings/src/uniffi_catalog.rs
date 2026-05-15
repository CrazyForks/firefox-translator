use std::sync::Arc;
use std::{fs, path::Path};

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

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct LiveMotionEstimate {
    pub valid: bool,
    pub dx: f32,
    pub dy: f32,
    pub confidence: f32,
    pub matches: u32,
    pub inliers: u32,
    pub reset: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LiveTextLineInput {
    pub track_id: u64,
    pub rect: translator::Rect,
    pub oriented_box: translator::ocr::OrientedRect,
    pub tight_box: translator::ocr::OrientedRect,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LiveTextGroup {
    pub track_ids: Vec<u64>,
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
        source_code: String,
        target_code: String,
        min_confidence: u32,
        reading_order: translator::ReadingOrder,
        background_mode: translator::BackgroundMode,
        preferred_engine: translator::PreferredOcrEngine,
    ) -> Result<translator::PreparedImageOverlay, CatalogError> {
        #[cfg(feature = "tesseract")]
        {
            return self
                .session
                .translate_image_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    max_image_size,
                    &source_code,
                    &target_code,
                    min_confidence,
                    reading_order,
                    background_mode,
                    preferred_engine,
                )
                .map_err(CatalogError::from);
        }
        #[cfg(not(feature = "tesseract"))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                max_image_size,
                source_code,
                target_code,
                min_confidence,
                reading_order,
                background_mode,
                preferred_engine,
            );
            Err(CatalogError::Other {
                reason: "tesseract feature disabled".to_string(),
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
    fn detect_in_frame(
        &self,
        frame: Arc<FrameHandle>,
        crop: translator::Rect,
        det_max_pixels: u32,
        source_code: String,
    ) -> Result<Vec<translator::DetectedTextBox>, CatalogError> {
        let mut state = frame.state.lock().map_err(|_| poisoned())?;
        ensure_oriented_locked(&mut state, crop, det_max_pixels)?;
        let oriented = state
            .cached
            .as_ref()
            .expect("ensure_oriented populated cache");
        let raw = self
            .session
            .detect_in_oriented_image(oriented, &source_code)
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
    /// have been previously passed to `detect_in_frame` (which built the cached
    /// oriented image used here). Boxes must be in full-crop coord space.
    #[cfg(feature = "ppocr")]
    fn recognize_in_frame(
        &self,
        frame: Arc<FrameHandle>,
        crop: translator::Rect,
        boxes: Vec<translator::DetectedTextBox>,
        source_code: String,
    ) -> Result<Vec<translator::RecognizedTextLine>, CatalogError> {
        let state = frame.state.lock().map_err(|_| poisoned())?;
        let oriented = state
            .cached
            .as_ref()
            .filter(|oi| oi.display_crop == crop)
            .ok_or_else(|| CatalogError::Other {
                reason: "recognize_in_frame called without prior detect_in_frame for this crop"
                    .to_string(),
            })?;
        self.session
            .recognize_in_oriented_image(oriented, &boxes, &source_code)
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
#[derive(uniffi::Object)]
pub struct LiveMotionTracker {
    state: std::sync::Mutex<translator::live_tracking::LiveFrameTracker>,
}

#[cfg(feature = "ppocr")]
#[uniffi::export]
impl LiveMotionTracker {
    #[uniffi::constructor]
    fn new() -> Arc<Self> {
        Arc::new(Self {
            state: std::sync::Mutex::new(translator::live_tracking::LiveFrameTracker::new()),
        })
    }

    fn reset(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.reset();
        }
    }

    fn update(
        &self,
        frame: Arc<FrameHandle>,
        crop: translator::Rect,
    ) -> Result<LiveMotionEstimate, CatalogError> {
        let frame_state = frame.state.lock().map_err(|_| poisoned())?;
        let mut tracker = self.state.lock().map_err(|_| CatalogError::Other {
            reason: "motion tracker mutex poisoned".to_string(),
        })?;
        let estimate = tracker
            .update(
                &frame_state.rgba,
                frame_state.width,
                frame_state.height,
                frame_state.rotation_degrees,
                crop,
            )
            .map_err(CatalogError::from)?;
        Ok(LiveMotionEstimate {
            valid: estimate.valid,
            dx: estimate.dx,
            dy: estimate.dy,
            confidence: estimate.confidence,
            matches: estimate.matches,
            inliers: estimate.inliers,
            reset: estimate.reset,
        })
    }
}

#[cfg(feature = "ppocr")]
#[uniffi::export]
pub fn group_live_text_lines(lines: Vec<LiveTextLineInput>) -> Vec<LiveTextGroup> {
    if lines.is_empty() {
        return Vec::new();
    }

    let text_lines: Vec<translator::ocr::TextLine> = lines
        .iter()
        .map(|line| translator::ocr::TextLine {
            text: format!("track{}", line.track_id),
            bounding_box: line.rect,
            oriented_box: line.oriented_box,
            tight_box: line.tight_box,
            word_rects: vec![line.rect],
        })
        .collect();

    translator::ocr::group_live_lines_into_blocks(text_lines)
        .into_iter()
        .filter_map(|block| {
            let mut ids = Vec::new();
            for line in block.lines {
                for token in line.text.split_whitespace() {
                    if let Some(raw_id) = token.strip_prefix("track") {
                        if let Ok(id) = raw_id.parse::<u64>() {
                            ids.push(id);
                        }
                    } else if let Ok(id) = token.parse::<u64>() {
                        ids.push(id);
                    }
                }
            }
            if ids.is_empty() {
                None
            } else {
                Some(LiveTextGroup { track_ids: ids })
            }
        })
        .collect()
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

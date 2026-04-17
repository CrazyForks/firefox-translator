use std::fs;
use std::path::PathBuf;
use std::sync::Arc;

use serde_json::Value;
use thiserror::Error;
use translator::{
    CatalogSnapshot, Feature, PackInstallChecker, TextTranslationOutcome,
    TranslationWarmOutcome, TranslatorSession, language_rows_in_snapshot, sample_overlay_colors,
};
#[cfg(feature = "dictionary")]
use translator::DictionaryLookupOutcome;
#[cfg(feature = "tts")]
use translator::{PcmSynthesisOutcome, SpeechChunkPlanningOutcome, TtsVoicesOutcome};

struct FsInstallChecker {
    base_dir: PathBuf,
}

impl FsInstallChecker {
    fn resolve(&self, relative_path: &str) -> PathBuf {
        self.base_dir.join(relative_path)
    }
}

impl PackInstallChecker for FsInstallChecker {
    fn file_exists(&self, install_path: &str) -> bool {
        self.resolve(install_path).exists()
    }

    fn install_marker_exists(&self, marker_path: &str, expected_version: i32) -> bool {
        let marker_file = self.resolve(marker_path);
        if !marker_file.exists() {
            return false;
        }

        let Ok(contents) = fs::read_to_string(marker_file) else {
            return false;
        };
        let Ok(json) = serde_json::from_str::<Value>(&contents) else {
            return false;
        };
        json.get("version")
            .and_then(Value::as_i64)
            .and_then(|value| i32::try_from(value).ok())
            == Some(expected_version)
    }
}

#[derive(Debug, Error, uniffi::Error)]
pub enum CatalogOpenError {
    #[error("failed to parse any catalog")]
    ParseFailed,
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
        let checker = FsInstallChecker {
            base_dir: PathBuf::from(&base_dir),
        };
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

    fn dictionary_info(
        &self,
        dictionary_code: translator::DictionaryCode,
    ) -> Option<translator::DictionaryInfo> {
        self.snapshot().catalog.dictionary_info(&dictionary_code)
    }

    fn lookup_dictionary(
        &self,
        language_code: translator::LanguageCode,
        word: String,
    ) -> Option<DictionaryWordRecord> {
        #[cfg(not(feature = "dictionary"))]
        {
            let _ = (language_code, word);
            return None;
        }

        #[cfg(feature = "dictionary")]
        match self.session.lookup_dictionary(language_code.as_str(), &word) {
            Ok(DictionaryLookupOutcome::Found(word)) => Some(map_dictionary_word(word)),
            Ok(DictionaryLookupOutcome::NotFound | DictionaryLookupOutcome::MissingDictionary) => {
                None
            }
            Err(_) => None,
        }
    }

    fn has_tts_voices(&self, language_code: translator::LanguageCode) -> bool {
        self.snapshot().catalog.has_tts_voices(&language_code)
    }

    fn tts_voice_picker_regions(
        &self,
        language_code: translator::LanguageCode,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        self.snapshot()
            .catalog
            .tts_voice_picker_regions(&language_code)
    }

    fn can_swap_languages(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
    ) -> bool {
        self.snapshot()
            .catalog
            .can_swap_languages(&from_code, &to_code)
    }

    fn can_translate(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
    ) -> bool {
        self.snapshot().can_translate(&from_code, &to_code)
    }

    fn warm_translation_models(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
    ) -> bool {
        self.session
            .warm(from_code.as_str(), to_code.as_str())
            .map(|outcome| matches!(outcome, TranslationWarmOutcome::Warmed))
            .unwrap_or(false)
    }

    fn translate_text(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
        text: String,
    ) -> Option<String> {
        self.session
            .translate_text(from_code.as_str(), to_code.as_str(), &text)
            .ok()
            .and_then(|outcome| match outcome {
                TextTranslationOutcome::Translated(value)
                | TextTranslationOutcome::Passthrough(value) => Some(value),
                TextTranslationOutcome::MissingLanguagePair => None,
            })
    }

    fn translate_mixed_texts(
        &self,
        inputs: Vec<String>,
        forced_source_code: Option<translator::LanguageCode>,
        target_code: translator::LanguageCode,
        available_language_codes: Vec<translator::LanguageCode>,
    ) -> translator::MixedTextTranslationResult {
        self.session
            .translate_mixed_texts(
                &inputs,
                forced_source_code.as_ref().map(translator::LanguageCode::as_str),
                target_code.as_str(),
                &available_language_codes,
            )
            .unwrap_or_else(|_| translator::MixedTextTranslationResult::default())
    }

    fn translate_structured_fragments(
        &self,
        fragments: Vec<translator::StructuredStyledFragment>,
        forced_source_code: Option<translator::LanguageCode>,
        target_code: translator::LanguageCode,
        available_language_codes: Vec<translator::LanguageCode>,
        screenshot: Option<translator::OverlayScreenshot>,
        background_mode: translator::BackgroundMode,
    ) -> translator::StructuredTranslationResult {
        self.session
            .translate_structured_fragments(
                &fragments,
                forced_source_code.as_ref().map(translator::LanguageCode::as_str),
                target_code.as_str(),
                &available_language_codes,
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
        source_code: translator::LanguageCode,
        target_code: translator::LanguageCode,
        min_confidence: u32,
        reading_order: translator::ReadingOrder,
        background_mode: translator::BackgroundMode,
    ) -> translator::ImageTranslationOutcome {
        #[cfg(feature = "tesseract")]
        {
            return self
                .session
                .translate_image_rgba(
                    &rgba_bytes,
                    width,
                    height,
                    source_code.as_str(),
                    target_code.as_str(),
                    min_confidence,
                    reading_order,
                    background_mode,
                )
                .unwrap_or(translator::ImageTranslationOutcome::MissingLanguagePair);
        }
        #[cfg(not(feature = "tesseract"))]
        {
            let _ = (
                rgba_bytes,
                width,
                height,
                source_code,
                target_code,
                min_confidence,
                reading_order,
                background_mode,
            );
            translator::ImageTranslationOutcome::MissingLanguagePair
        }
    }

    fn plan_language_download(
        &self,
        language_code: translator::LanguageCode,
    ) -> translator::DownloadPlan {
        self.session
            .plan_download(language_code.as_str(), Feature::Core, None)
            .unwrap_or_else(|| translator::DownloadPlan {
                total_size: 0,
                tasks: Vec::new(),
            })
    }

    fn plan_dictionary_download(
        &self,
        language_code: translator::LanguageCode,
    ) -> Option<translator::DownloadPlan> {
        self.session
            .plan_download(language_code.as_str(), Feature::Dictionary, None)
    }

    fn plan_tts_download(
        &self,
        language_code: translator::LanguageCode,
        selected_pack_id: Option<String>,
    ) -> Option<translator::DownloadPlan> {
        self.session.plan_download(
            language_code.as_str(),
            Feature::Tts,
            selected_pack_id.as_deref(),
        )
    }

    fn plan_delete_language(
        &self,
        language_code: translator::LanguageCode,
    ) -> translator::DeletePlan {
        self.session
            .prepare_delete(language_code.as_str(), Feature::Core)
    }

    fn plan_delete_dictionary(
        &self,
        language_code: translator::LanguageCode,
    ) -> translator::DeletePlan {
        self.session
            .prepare_delete(language_code.as_str(), Feature::Dictionary)
    }

    fn plan_delete_tts(&self, language_code: translator::LanguageCode) -> translator::DeletePlan {
        self.session
            .prepare_delete(language_code.as_str(), Feature::Tts)
    }

    fn plan_delete_superseded_tts(
        &self,
        language_code: translator::LanguageCode,
        selected_pack_id: String,
    ) -> translator::DeletePlan {
        self.session
            .prepare_delete_superseded_tts(language_code.as_str(), &selected_pack_id)
    }

    fn tts_size_bytes(&self, language_code: translator::LanguageCode) -> u64 {
        self.session.size_bytes(language_code.as_str(), Feature::Tts)
    }

    fn translation_size_bytes(&self, language_code: translator::LanguageCode) -> u64 {
        self.session
            .size_bytes(language_code.as_str(), Feature::Core)
    }

    fn default_tts_pack_id(&self, language_code: translator::LanguageCode) -> Option<String> {
        self.snapshot()
            .catalog
            .default_tts_pack_id_for_language(&language_code)
    }

    fn available_tts_voices(
        &self,
        language_code: translator::LanguageCode,
    ) -> Vec<translator::TtsVoiceOption> {
        #[cfg(feature = "tts")]
        {
            return match self.session.available_tts_voices(language_code.as_str()) {
                Ok(TtsVoicesOutcome::Available(voices)) => voices,
                Ok(TtsVoicesOutcome::MissingLanguage) | Err(_) => Vec::new(),
            };
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn plan_speech_chunks(
        &self,
        language_code: translator::LanguageCode,
        text: String,
    ) -> Vec<translator::SpeechChunk> {
        #[cfg(feature = "tts")]
        {
            return match self.session.plan_speech_chunks(language_code.as_str(), &text) {
                Ok(SpeechChunkPlanningOutcome::Planned(chunks)) => chunks,
                Ok(SpeechChunkPlanningOutcome::MissingLanguage) | Err(_) => Vec::new(),
            };
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text);
            Vec::new()
        }
    }

    fn synthesize_speech_pcm(
        &self,
        language_code: translator::LanguageCode,
        text: String,
        speech_speed: f32,
        voice_name: Option<translator::VoiceName>,
        is_phonemes: bool,
    ) -> Option<translator::PcmAudio> {
        #[cfg(feature = "tts")]
        {
            return match self.session.synthesize_pcm(
                language_code.as_str(),
                &text,
                speech_speed,
                voice_name.as_ref().map(translator::VoiceName::as_str),
                is_phonemes,
            ) {
                Ok(PcmSynthesisOutcome::Ready(audio)) => Some(audio),
                Ok(PcmSynthesisOutcome::MissingLanguage) | Err(_) => None,
            };
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text, speech_speed, voice_name, is_phonemes);
            None
        }
    }
}

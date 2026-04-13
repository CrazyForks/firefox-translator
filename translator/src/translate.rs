use std::path::Path;

use crate::catalog::{LanguageCatalog, PackInstallChecker, PackResolver};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslatedText {
    pub translated: String,
    pub transliterated: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct TokenAlignment {
    pub src_begin: usize,
    pub src_end: usize,
    pub tgt_begin: usize,
    pub tgt_end: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslationWithAlignment {
    pub source_text: String,
    pub translated_text: String,
    pub alignments: Vec<TokenAlignment>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslationStep {
    pub from_code: String,
    pub to_code: String,
    pub cache_key: String,
    pub config: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TranslationPlan {
    pub steps: Vec<TranslationStep>,
}

impl TranslationPlan {
    pub fn is_pivot(&self) -> bool {
        self.steps.len() == 2
    }
}

fn absolute_install_path(base_dir: &str, install_path: &str) -> String {
    Path::new(base_dir)
        .join(install_path)
        .to_string_lossy()
        .into_owned()
}

fn build_bergamot_config(base_dir: &str, step: &crate::language::LanguageDirection) -> String {
    let model_path = absolute_install_path(base_dir, &step.model.path);
    let src_vocab_path = absolute_install_path(base_dir, &step.src_vocab.path);
    let tgt_vocab_path = absolute_install_path(base_dir, &step.tgt_vocab.path);

    format!(
        "models:\n  - {model_path}\n\
vocabs:\n  - {src_vocab_path}\n  - {tgt_vocab_path}\n\
beam-size: 1\n\
normalize: 1.0\n\
word-penalty: 0\n\
max-length-break: 128\n\
mini-batch-words: 1024\n\
max-length-factor: 2.0\n\
skip-cost: true\n\
cpu-threads: 1\n\
quiet: true\n\
quiet-translation: true\n\
gemm-precision: int8shiftAlphaAll\n\
alignment: soft\n"
    )
}

fn cache_key(from_code: &str, to_code: &str) -> String {
    format!("{from_code}{to_code}")
}

impl LanguageCatalog {
    pub fn resolve_translation_plan<C>(
        &self,
        base_dir: &str,
        from_code: &str,
        to_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> Option<TranslationPlan>
    where
        C: PackInstallChecker,
    {
        if from_code == to_code {
            return Some(TranslationPlan::default());
        }

        let steps = if from_code == "en" {
            vec![self.resolve_translation_step(base_dir, "en", to_code, resolver)?]
        } else if to_code == "en" {
            vec![self.resolve_translation_step(base_dir, from_code, "en", resolver)?]
        } else {
            vec![
                self.resolve_translation_step(base_dir, from_code, "en", resolver)?,
                self.resolve_translation_step(base_dir, "en", to_code, resolver)?,
            ]
        };

        Some(TranslationPlan { steps })
    }

    fn resolve_translation_step<C>(
        &self,
        base_dir: &str,
        from_code: &str,
        to_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> Option<TranslationStep>
    where
        C: PackInstallChecker,
    {
        if !self.has_translation_direction_installed(from_code, to_code, resolver) {
            return None;
        }

        let direction = self.translation_direction(from_code, to_code)?;
        Some(TranslationStep {
            from_code: from_code.to_string(),
            to_code: to_code.to_string(),
            cache_key: cache_key(from_code, to_code),
            config: build_bergamot_config(base_dir, &direction),
        })
    }
}

use translator::{detect_language, detect_language_robust_code};

#[uniffi::export]
pub fn detect_language_record(
    text: String,
    hint: Option<String>,
) -> Option<translator::DetectionResult> {
    detect_language(&text, hint.as_deref())
}

#[uniffi::export]
pub fn detect_language_robust_code_record(
    text: String,
    hint: Option<String>,
    available_language_codes: Vec<String>,
) -> Option<String> {
    detect_language_robust_code(&text, hint.as_deref(), &available_language_codes)
}

#[uniffi::export]
pub fn transliterate_with_policy_record(
    text: String,
    language_code: String,
    source_script: translator::script::Script,
    target_script: translator::script::Script,
    japanese_dict_path: Option<String>,
    japanese_spaced: bool,
) -> Option<String> {
    #[cfg(feature = "transliterate")]
    {
        // A script this build cannot name is one it cannot romanize either.
        translator::transliterate::transliterate_with_policy_for_language(
            &text,
            &translator::LanguageCode::from(language_code),
            &translator::ScriptCode::from(source_script.iso15924()?),
            &translator::ScriptCode::from(target_script.iso15924()?),
            japanese_dict_path.as_deref(),
            japanese_spaced,
        )
    }
    #[cfg(not(feature = "transliterate"))]
    {
        let _ = (
            text,
            language_code,
            source_script,
            target_script,
            japanese_dict_path,
            japanese_spaced,
        );
        None
    }
}

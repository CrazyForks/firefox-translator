#[path = "catalog_model.rs"]
mod model;
#[path = "catalog_planner.rs"]
mod planner;
#[path = "catalog_wire.rs"]
mod wire;

pub use model::{
    AssetFileV2, AssetPackMetadataV2, CatalogSourcesV2, DeletePlan, DictionaryInfo, DownloadPlan,
    DownloadTask, LangAvailability, LanguageCatalog, LanguageFeature, LanguageTtsRegionV2,
    LanguageTtsV2, PackKind, PackRecord, ResolvedTtsVoiceFiles, TtsVoicePackInfo,
};
pub use planner::{PackInstallChecker, PackInstallStatus, PackResolver};
pub use wire::{parse_and_validate_catalog, parse_language_catalog, select_best_catalog};

#[cfg(test)]
#[path = "catalog_tests.rs"]
mod tests;

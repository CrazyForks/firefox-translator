use std::collections::{HashMap, HashSet};

use crate::language::Language;

use super::model::{
    AssetFileV2, DeletePlan, DownloadPlan, DownloadTask, LangAvailability, LanguageCatalog,
    PackKind, PackRecord, ResolvedTtsVoiceFiles,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackInstallStatus {
    pub pack_id: String,
    pub installed: bool,
    pub missing_files: Vec<AssetFileV2>,
    pub missing_dependency_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct MissingPackFile {
    pack_id: String,
    file: AssetFileV2,
}

pub trait PackInstallChecker {
    fn file_exists(&self, install_path: &str) -> bool;

    fn install_marker_exists(&self, marker_path: &str, expected_version: i32) -> bool;
}

pub struct PackResolver<'a, C> {
    catalog: &'a LanguageCatalog,
    install_checker: &'a C,
    status_cache: HashMap<String, PackInstallStatus>,
}

impl<'a, C> PackResolver<'a, C>
where
    C: PackInstallChecker,
{
    pub fn new(catalog: &'a LanguageCatalog, install_checker: &'a C) -> Self {
        Self {
            catalog,
            install_checker,
            status_cache: HashMap::new(),
        }
    }

    pub fn status(&mut self, pack_id: &str) -> Option<PackInstallStatus> {
        if let Some(status) = self.status_cache.get(pack_id) {
            return Some(status.clone());
        }

        let pack = self.catalog.pack(pack_id)?;
        let missing_files = pack
            .files
            .iter()
            .filter(
                |file| match (&file.install_marker_path, file.install_marker_version) {
                    (Some(marker_path), Some(version)) => !self
                        .install_checker
                        .install_marker_exists(marker_path, version),
                    _ => !self.install_checker.file_exists(&file.install_path),
                },
            )
            .cloned()
            .collect::<Vec<_>>();

        let missing_dependency_ids = pack
            .depends_on
            .iter()
            .filter(|dep_id| self.status(dep_id).is_none_or(|status| !status.installed))
            .cloned()
            .collect::<Vec<_>>();

        let status = PackInstallStatus {
            pack_id: pack_id.to_string(),
            installed: missing_files.is_empty() && missing_dependency_ids.is_empty(),
            missing_files,
            missing_dependency_ids,
        };
        self.status_cache
            .insert(pack_id.to_string(), status.clone());
        Some(status)
    }

    pub fn is_installed(&mut self, pack_id: &str) -> bool {
        self.status(pack_id).is_some_and(|status| status.installed)
    }

    fn missing_files<'b, I>(&mut self, pack_ids: I) -> Vec<MissingPackFile>
    where
        I: IntoIterator<Item = &'b str>,
    {
        let mut missing = Vec::new();
        let mut seen_install_paths = HashSet::new();

        for pack_id in self.catalog.dependency_closure(pack_ids) {
            let Some(pack) = self.catalog.pack(&pack_id) else {
                continue;
            };
            let Some(status) = self.status(&pack_id) else {
                continue;
            };
            for file in status.missing_files {
                if seen_install_paths.insert(file.install_path.clone()) {
                    missing.push(MissingPackFile {
                        pack_id: pack.id.clone(),
                        file,
                    });
                }
            }
        }

        missing
    }
}

impl LanguageCatalog {
    pub fn has_translation_direction_installed<C>(
        &self,
        from_code: &str,
        to_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> bool
    where
        C: PackInstallChecker,
    {
        self.translation_pack_id(from_code, to_code)
            .as_deref()
            .is_some_and(|pack_id| resolver.is_installed(pack_id))
    }

    pub fn can_translate<C>(
        &self,
        from_code: &str,
        to_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> bool
    where
        C: PackInstallChecker,
    {
        if from_code == to_code {
            return true;
        }

        if from_code == "en" {
            return self.has_translation_direction_installed("en", to_code, resolver);
        }
        if to_code == "en" {
            return self.has_translation_direction_installed(from_code, "en", resolver);
        }

        self.has_translation_direction_installed(from_code, "en", resolver)
            && self.has_translation_direction_installed("en", to_code, resolver)
    }

    pub fn can_swap_languages_installed<C>(
        &self,
        from_code: &str,
        to_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> bool
    where
        C: PackInstallChecker,
    {
        let to_can_be_source =
            to_code == "en" || self.has_translation_direction_installed(to_code, "en", resolver);
        let from_can_be_target = from_code == "en"
            || self.has_translation_direction_installed("en", from_code, resolver);
        to_can_be_source && from_can_be_target
    }

    pub fn installed_tts_pack_id_for_language<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> Option<String>
    where
        C: PackInstallChecker,
    {
        self.tts_pack_ids_for_language(language_code)
            .into_iter()
            .find(|pack_id| resolver.is_installed(pack_id))
    }

    pub fn compute_language_availability<C>(
        &self,
        resolver: &mut PackResolver<'_, C>,
    ) -> HashMap<Language, LangAvailability>
    where
        C: PackInstallChecker,
    {
        self.language_list()
            .into_iter()
            .map(|language| {
                let ocr_pack_id = self
                    .language_info(&language.code)
                    .and_then(|info| {
                        info.resources
                            .ocr_packs
                            .iter()
                            .find(|(engine, _)| engine == "tesseract")
                    })
                    .map(|(_, pack_id)| pack_id.clone());
                let dictionary_pack_id = self.dictionary_pack_id_for_language(&language.code);
                let availability = if language.is_english() {
                    LangAvailability {
                        has_from_english: true,
                        has_to_english: true,
                        ocr_files: ocr_pack_id
                            .as_deref()
                            .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                        dictionary_files: dictionary_pack_id
                            .as_deref()
                            .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                        tts_files: self
                            .installed_tts_pack_id_for_language(&language.code, resolver)
                            .is_some(),
                    }
                } else {
                    let from_pack_id = self.translation_pack_id("en", &language.code);
                    let to_pack_id = self.translation_pack_id(&language.code, "en");
                    LangAvailability {
                        has_from_english: from_pack_id
                            .as_deref()
                            .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                        has_to_english: to_pack_id
                            .as_deref()
                            .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                        ocr_files: ocr_pack_id
                            .as_deref()
                            .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                        dictionary_files: dictionary_pack_id
                            .as_deref()
                            .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                        tts_files: self
                            .installed_tts_pack_id_for_language(&language.code, resolver)
                            .is_some(),
                    }
                };
                (language, availability)
            })
            .collect()
    }

    pub fn resolve_tts_voice_files<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> Option<ResolvedTtsVoiceFiles>
    where
        C: PackInstallChecker,
    {
        let voice_pack_id = self.installed_tts_pack_id_for_language(language_code, resolver)?;
        let voice_pack = self.pack(&voice_pack_id)?;
        let PackKind::Tts(tts) = &voice_pack.kind else {
            return None;
        };
        let pack_files = self.pack_files_with_dependencies(&voice_pack_id);
        let model_asset = pack_files
            .iter()
            .find(|file| file.name.ends_with(".onnx") && !file.name.ends_with(".onnx.json"))?;
        let engine = tts.engine.clone().unwrap_or_else(|| "piper".to_string());
        let aux_asset = match engine.as_str() {
            "kokoro" => pack_files.iter().find(|file| file.name.ends_with(".bin")),
            "mms" => pack_files
                .iter()
                .find(|file| file.name.ends_with("tokens.txt")),
            "coqui_vits" | "sherpa_vits" => {
                pack_files.iter().find(|file| file.name == "config.json")
            }
            _ => pack_files
                .iter()
                .find(|file| file.name.ends_with(".onnx.json")),
        }?;
        Some(ResolvedTtsVoiceFiles {
            engine,
            model_install_path: model_asset.install_path.clone(),
            aux_install_path: aux_asset.install_path.clone(),
            language_code: language_code.to_string(),
            speaker_id: tts.default_speaker_id,
        })
    }

    fn download_task_for(pack: &PackRecord, file: &AssetFileV2) -> DownloadTask {
        DownloadTask {
            pack_id: pack.id.clone(),
            install_path: file.install_path.clone(),
            url: file.url.clone(),
            size_bytes: file.size_bytes,
            decompress: matches!(&pack.kind, PackKind::Translation(_))
                && file
                    .source_path
                    .as_deref()
                    .unwrap_or(file.url.as_str())
                    .ends_with(".gz"),
            archive_format: file.archive_format.clone(),
            extract_to: file.extract_to.clone(),
            delete_after_extract: file.delete_after_extract,
            install_marker_path: file.install_marker_path.clone(),
            install_marker_version: file.install_marker_version,
        }
    }

    fn download_plan_for_root_packs<C, I>(
        &self,
        root_pack_ids: I,
        resolver: &mut PackResolver<'_, C>,
    ) -> DownloadPlan
    where
        C: PackInstallChecker,
        I: IntoIterator,
        I::Item: AsRef<str>,
    {
        let root_pack_ids = root_pack_ids
            .into_iter()
            .map(|id| id.as_ref().to_string())
            .collect::<Vec<_>>();
        let tasks = resolver
            .missing_files(root_pack_ids.iter().map(String::as_str))
            .into_iter()
            .filter_map(|item| {
                let pack = self.pack(&item.pack_id)?;
                Some(Self::download_task_for(pack, &item.file))
            })
            .collect::<Vec<_>>();
        DownloadPlan {
            total_size: tasks.iter().map(|task| task.size_bytes).sum(),
            tasks,
        }
    }

    pub fn plan_language_download<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> DownloadPlan
    where
        C: PackInstallChecker,
    {
        self.download_plan_for_root_packs(self.core_pack_ids_for_language(language_code), resolver)
    }

    pub fn plan_dictionary_download<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> Option<DownloadPlan>
    where
        C: PackInstallChecker,
    {
        let pack_id = self.dictionary_pack_id_for_language(language_code)?;
        Some(self.download_plan_for_root_packs([pack_id], resolver))
    }

    pub fn plan_tts_download<C>(
        &self,
        language_code: &str,
        selected_pack_id: Option<&str>,
        resolver: &mut PackResolver<'_, C>,
    ) -> Option<DownloadPlan>
    where
        C: PackInstallChecker,
    {
        let selected_pack_id = match selected_pack_id {
            Some(pack_id)
                if self
                    .tts_pack_ids_for_language(language_code)
                    .iter()
                    .any(|candidate| candidate == pack_id) =>
            {
                pack_id.to_string()
            }
            Some(_) => return None,
            None => self.default_tts_pack_id_for_language(language_code)?,
        };
        Some(self.download_plan_for_root_packs([selected_pack_id], resolver))
    }

    fn delete_plan_for_pack_ids<'a, I>(&self, pack_ids: I) -> DeletePlan
    where
        I: IntoIterator<Item = &'a str>,
    {
        let mut file_paths = Vec::new();
        let mut file_seen = HashSet::new();
        let mut directory_paths = Vec::new();
        let mut directory_seen = HashSet::new();

        for pack_id in pack_ids {
            let Some(pack) = self.pack(pack_id) else {
                continue;
            };
            for file in &pack.files {
                if file_seen.insert(file.install_path.clone()) {
                    file_paths.push(file.install_path.clone());
                }
                if file.archive_format.as_deref() == Some("zip")
                    && let Some(marker_path) = file.install_marker_path.as_deref()
                    && let Some(parent) = std::path::Path::new(marker_path).parent()
                {
                    let path = parent.to_string_lossy().to_string();
                    if !path.is_empty() && directory_seen.insert(path.clone()) {
                        directory_paths.push(path);
                    }
                }
            }
        }

        DeletePlan {
            file_paths,
            directory_paths,
        }
    }

    pub fn plan_delete_dictionary<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> DeletePlan
    where
        C: PackInstallChecker,
    {
        let Some(target_pack) = self.dictionary_pack_id_for_language(language_code) else {
            return DeletePlan::default();
        };
        let keep_root_packs = self
            .language_list()
            .into_iter()
            .filter(|language| language.code != language_code)
            .filter_map(|language| self.dictionary_pack_id_for_language(&language.code))
            .filter(|pack_id| pack_id != &target_pack && resolver.is_installed(pack_id))
            .collect::<HashSet<_>>();
        let delete_pack_ids = self.delete_pack_ids([target_pack.as_str()], keep_root_packs);
        self.delete_plan_for_pack_ids(delete_pack_ids.iter().map(String::as_str))
    }

    pub fn plan_delete_language<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> DeletePlan
    where
        C: PackInstallChecker,
    {
        let target_root_packs = self.core_pack_ids_for_language(language_code);
        let keep_root_packs = self
            .language_list()
            .into_iter()
            .filter(|language| language.code != language_code)
            .flat_map(|language| self.core_pack_ids_for_language(&language.code))
            .filter(|pack_id| resolver.is_installed(pack_id))
            .collect::<HashSet<_>>();
        let delete_pack_ids = self.delete_pack_ids(
            target_root_packs.iter().map(String::as_str),
            keep_root_packs,
        );
        self.delete_plan_for_pack_ids(delete_pack_ids.iter().map(String::as_str))
    }

    pub fn plan_delete_tts<C>(
        &self,
        language_code: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> DeletePlan
    where
        C: PackInstallChecker,
    {
        let target_root_packs = self
            .tts_pack_ids_for_language(language_code)
            .into_iter()
            .filter(|pack_id| resolver.is_installed(pack_id))
            .collect::<HashSet<_>>();
        if target_root_packs.is_empty() {
            return DeletePlan::default();
        }
        let keep_root_packs = self
            .language_list()
            .into_iter()
            .filter(|language| language.code != language_code)
            .flat_map(|language| self.tts_pack_ids_for_language(&language.code))
            .filter(|pack_id| resolver.is_installed(pack_id))
            .collect::<HashSet<_>>();
        let delete_pack_ids = self.delete_pack_ids(
            target_root_packs.iter().map(String::as_str),
            keep_root_packs,
        );
        self.delete_plan_for_pack_ids(delete_pack_ids.iter().map(String::as_str))
    }

    pub fn plan_delete_superseded_tts<C>(
        &self,
        language_code: &str,
        selected_pack_id: &str,
        resolver: &mut PackResolver<'_, C>,
    ) -> DeletePlan
    where
        C: PackInstallChecker,
    {
        let installed_language_packs = self
            .tts_pack_ids_for_language(language_code)
            .into_iter()
            .filter(|pack_id| resolver.is_installed(pack_id))
            .collect::<HashSet<_>>();
        let superseded_root_packs = self
            .tts_pack_ids_for_language(language_code)
            .into_iter()
            .filter(|pack_id| {
                pack_id != selected_pack_id && installed_language_packs.contains(pack_id)
            })
            .collect::<HashSet<_>>();
        if superseded_root_packs.is_empty() {
            return DeletePlan::default();
        }
        let mut keep_root_packs = HashSet::new();
        if resolver.is_installed(selected_pack_id) {
            keep_root_packs.insert(selected_pack_id.to_string());
        }
        keep_root_packs.extend(
            self.language_list()
                .into_iter()
                .filter(|language| language.code != language_code)
                .flat_map(|language| self.tts_pack_ids_for_language(&language.code))
                .filter(|pack_id| resolver.is_installed(pack_id)),
        );
        let delete_pack_ids = self.delete_pack_ids(
            superseded_root_packs.iter().map(String::as_str),
            keep_root_packs,
        );
        self.delete_plan_for_pack_ids(delete_pack_ids.iter().map(String::as_str))
    }

    fn delete_pack_ids<'a, I>(
        &self,
        target_root_packs: I,
        keep_root_packs: HashSet<String>,
    ) -> HashSet<String>
    where
        I: IntoIterator<Item = &'a str>,
    {
        let target = self
            .dependency_closure(target_root_packs)
            .into_iter()
            .collect::<HashSet<_>>();
        let keep = self
            .dependency_closure(keep_root_packs.iter().map(String::as_str))
            .into_iter()
            .collect::<HashSet<_>>();
        target.difference(&keep).cloned().collect()
    }
}

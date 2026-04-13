#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum SpeechChunkBoundary {
    #[default]
    None,
    Sentence,
    Paragraph,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhonemeChunk {
    pub content: String,
    pub boundary_after: SpeechChunkBoundary,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TtsVoiceOption {
    pub name: String,
    pub speaker_id: i64,
    pub display_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PcmAudio {
    pub sample_rate: i32,
    pub pcm_samples: Vec<i16>,
}

impl PcmAudio {
    pub fn silence(sample_rate: i32, duration_ms: i32) -> Self {
        let clamped_duration_ms = duration_ms.max(1) as i64;
        let sample_count = ((sample_rate as i64) * clamped_duration_ms / 1000).max(1) as usize;
        Self {
            sample_rate,
            pcm_samples: vec![0; sample_count],
        }
    }
}

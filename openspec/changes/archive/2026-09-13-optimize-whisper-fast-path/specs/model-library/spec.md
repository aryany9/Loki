## ADDED Requirements

### Requirement: ASR models support artifact variants
A catalog ASR model record MAY declare multiple artifact variants distinguished by an optional `variant` field (`quantized`, `full-precision`). The quantized variant SHALL be preferred for download and loading when available, while the full-precision variant SHALL remain registered and downloadable as fallback. Each variant MUST carry its own size, sha256, and download URL.

#### Scenario: Catalog lists quantized and full-precision variants
- **WHEN** the ASR model record is provisioned on a fresh install
- **THEN** the quantized variant is available for download with verified integrity metadata
- **AND** the full-precision variant remains selectable in the model library as a fallback

#### Scenario: Existing install without quantized artifact
- **WHEN** a device already has only the full-precision artifact installed after upgrading
- **THEN** ASR remains functional and the quantized variant is fetched opportunistically without forcing a blocking download

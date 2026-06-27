use anyhow::Result;
use std::fs;
use std::io::Write;
use std::path::Path;

/// Write `content` to `path` atomically via a sibling temp file and rename.
pub fn atomic_write(path: &Path, content: &str) -> Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("path has no parent: {}", path.display()))?;
    fs::create_dir_all(parent)?;

    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or_else(|| anyhow::anyhow!("invalid path: {}", path.display()))?;
    let tmp_path = parent.join(format!("{file_name}.tmp"));

    {
        let mut file = fs::File::create(&tmp_path)?;
        file.write_all(content.as_bytes())?;
        file.sync_all()?;
    }

    fs::rename(&tmp_path, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn atomic_write_creates_file_with_content() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("data.json");
        atomic_write(&path, r#"{"ok":true}"#).unwrap();
        let content = fs::read_to_string(&path).unwrap();
        assert_eq!(content, r#"{"ok":true}"#);
    }
}

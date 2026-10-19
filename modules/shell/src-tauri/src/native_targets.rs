//! Native names shared with Java, Node and distribution staging.
use serde_json::Value;
use std::sync::OnceLock;

fn spec() -> &'static Value {
    static SPEC: OnceLock<Value> = OnceLock::new();
    SPEC.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../../contracts/native-targets/spec.v1.json"
        ))
        .expect("valid native-targets contract")
    })
}

fn native_name(kind: &str, id: &str) -> &'static str {
    let os = if cfg!(windows) {
        "windows"
    } else {
        std::env::consts::OS
    };
    let target = format!("{os}-{}", std::env::consts::ARCH);
    spec()["targets"][&target][kind][id]
        .as_str()
        .expect("supported native target and library")
}

pub(crate) fn library_name(id: &str) -> &'static str {
    native_name("libraries", id)
}

pub(crate) fn executable_name(id: &str) -> &'static str {
    native_name("executables", id)
}

pub(crate) fn relative_path(id: &str) -> &'static str { native_name("paths", id) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_fixtures_agree() {
        assert_eq!(spec()["schemaVersion"], 1);
        for target in ["windows-x86_64", "linux-x86_64"] {
            assert!(spec()["targets"][target]["executables"]["llama-server"].is_string());
        }
        assert_eq!(
            library_name("ort-cuda"),
            if cfg!(windows) {
                "onnxruntime_providers_cuda.dll"
            } else {
                "libonnxruntime_providers_cuda.so"
            }
        );
    }
}

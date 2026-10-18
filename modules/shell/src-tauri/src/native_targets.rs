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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_fixtures_agree() {
        assert_eq!(spec()["schemaVersion"], 1);
        for fixture in spec()["fixtures"].as_array().unwrap() {
            let target = fixture["target"].as_str().unwrap();
            let mut expected = fixture.as_object().unwrap().clone();
            expected.remove("target");
            assert_eq!(
                spec()["targets"][target],
                Value::Object(expected),
                "{target}"
            );
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

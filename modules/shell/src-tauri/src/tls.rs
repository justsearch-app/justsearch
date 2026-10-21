/// Install the shell's TLS provider before any default-dependent client is built.
pub(crate) fn initialize() {
    // rustls keeps the first process-wide provider; another install attempt is harmless.
    let _ = rustls::crypto::ring::default_provider().install_default();
}

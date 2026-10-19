// GitHub clones unchanged jobs into a rerun with the CURRENT run_attempt but
// their ORIGINAL execution timestamps. Artifact creation time identifies which
// part execution produced evidence; run_attempt alone cannot do that.
export function selectJobArtifact(artifacts, baseName, job) {
  const start = Date.parse(job.started_at || job.startedAt || '');
  const end = Date.parse(job.completed_at || job.completedAt || '');
  const expected = `${baseName}-attempt-${job.run_attempt || 1}`;
  return artifacts.filter((artifact) => {
    if (artifact.expired) return false;
    if (artifact.name !== baseName && !(artifact.name.startsWith(`${baseName}-attempt-`)
      && /^\d+$/.test(artifact.name.slice(baseName.length + '-attempt-'.length)))) return false;
    const created = Date.parse(artifact.created_at || artifact.createdAt || '');
    if ([start, end, created].every(Number.isFinite)) return created >= start && created <= end;
    // Historical fixtures / evidence without timestamps cannot prove an earlier
    // attempt belongs to this execution. Never fill a current hole with it.
    return artifact.name === baseName || artifact.name === expected;
  }).sort((a, b) =>
    Date.parse(b.created_at || b.createdAt || '') - Date.parse(a.created_at || a.createdAt || '')
    || Number(b.name.split('-attempt-')[1] || 0) - Number(a.name.split('-attempt-')[1] || 0)
  )[0] || null;
}

---
evolution-rule: additive-optional
---
WP2.3a adds `SearchFilters.size_bytes` (17, JSON `sizeBytes`) and the `ByteRange`
message with optional inclusive `fromBytes` and `toBytes` endpoints. Presence keeps
zero distinct from unset. The field describes the Search response's applied-filter echo.

The Engine's internal Search and RetrieveContext messages also gain the optional range;
Head and Worker ship together. Existing fields, field numbers and index fields are unchanged.
Generated Java projections and record-to-schema-to-TypeScript projections are regenerated
from their existing sources.

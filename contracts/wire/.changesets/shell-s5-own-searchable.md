---
evolution-rule: additive-optional
---
`CoreIndexView.own_searchable_documents` reports the default-search population minus the
application's internal collections, counted on the same acquired serving reader as
`searchable_documents`. It has optional presence: an older worker or an unknown index
means null/absent, never a measured zero. The IPC counterpart is optional too. Existing
searchable and indexed counts keep their meanings. Generated JSON readers permit an
absent own count; first-use advice remains unknown in that case. Protobuf readers
ignore this additive field. Older strict JSON readers may reject the additional field;
production's existing status-drift handling applies.

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/** Freshness of a derived count; a stale reading retains its original observation time. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CountObservationView(String state, long observedAtMs) {}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** A failed acceptance or durable transition must never be mistaken for permission to execute. */
public final class OperationStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public enum Code { STORAGE_FAILED, OPERATION_KEY_REUSED, OPERATION_EXPIRED, INVALID_OPERATION_KEY,
    OPERATIONS_CAPACITY, OPERATION_PREPARATION_UNAVAILABLE }
  private final Code code;
  private final long retryAfterMillis;

  public OperationStoreException(Code code, Throwable cause) {
    this(code, cause, 0);
  }
  public OperationStoreException(Code code, Throwable cause, long retryAfterMillis) {
    super(code.name(), cause);
    this.code = code;
    this.retryAfterMillis = Math.max(0, retryAfterMillis);
  }
  public Code code() { return code; }
  /** Missing current-time keys cannot be minted safely until an ahead-of-clock fence is crossed. */
  public long retryAfterMillis() { return retryAfterMillis; }
}

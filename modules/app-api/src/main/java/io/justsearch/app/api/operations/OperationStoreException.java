/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** A failed acceptance or durable transition must never be mistaken for permission to execute. */
public final class OperationStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public enum Code { STORAGE_FAILED, OPERATION_KEY_REUSED, OPERATION_EXPIRED, INVALID_OPERATION_KEY,
    CHILD_ACCEPTANCE_REFUSED }
  private final Code code;

  public OperationStoreException(Code code, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
  }
  public Code code() { return code; }
}

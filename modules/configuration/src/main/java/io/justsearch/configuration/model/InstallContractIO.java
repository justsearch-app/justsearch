/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the install contract JSON file.
 *
 * <p>The contract is stored as {@code install-contract.v2.json} in the AI Home directory. The
 * install pipeline writes it after successful completion; the runtime reads it on startup.
 */
public final class InstallContractIO {
  public static final int CURRENT_SCHEMA_VERSION = 2;

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private InstallContractIO() {}

  /** Reads the install contract from the AI Home directory. Returns null if not found. */
  public static InstallContract read(Path homeDir) {
    Path contractPath = homeDir.resolve(InstallContract.CONTRACT_FILENAME);
    if (!Files.isRegularFile(contractPath)) {
      return null;
    }
    try {
      InstallContract contract = JSON.readValue(contractPath.toFile(), InstallContract.class);
      if (contract == null) {
        throw new CorruptDurableStoreException("ai-install-contract", "JSON document is empty");
      }
      StoreFormatVersions.requireReadable(
          "ai-install-contract",
          contract.schemaVersion(),
          CURRENT_SCHEMA_VERSION,
          CURRENT_SCHEMA_VERSION);
      return contract;
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "ai-install-contract", "cannot parse " + contractPath, e);
    }
  }

  /** Writes the install contract to the AI Home directory. Creates parent directories. */
  public static void write(InstallContract contract, Path homeDir) {
    Path contractPath = homeDir.resolve(InstallContract.CONTRACT_FILENAME);
    try {
      Files.createDirectories(homeDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create contract directory: " + homeDir, e);
    }
    if (contract.schemaVersion() != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Install contract schemaVersion must be " + CURRENT_SCHEMA_VERSION);
    }
    try {
      AtomicFileWrites.replace(
          contractPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(contract));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write install contract: " + contractPath, e);
    }
  }
}

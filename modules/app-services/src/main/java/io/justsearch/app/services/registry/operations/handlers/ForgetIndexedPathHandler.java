/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.ForgetPlan;
import io.justsearch.app.api.operations.ForgetService;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** UI consent binds the exact server-classified target, never a result-hit ownership label. */
public final class ForgetIndexedPathHandler implements OperationHandler {
  private final Supplier<ForgetService> owner;

  public ForgetIndexedPathHandler(Supplier<ForgetService> owner) { this.owner = Objects.requireNonNull(owner, "owner"); }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Forget requires its accepted server preparation");
  }

  @Override public OperationPreparation prepare(String argumentsJson,
      InvocationProvenance provenance, EngineContext context) {
    requireWebview(context);
    try {
      var request = ForgetPlan.request(argumentsJson);
      final ForgetPlan plan;
      try { plan = owner.get().prepare(request.path(), request.scope(), context); }
      catch (IllegalArgumentException refused) {
        // The public request has already passed strict validation. This reason belongs to
        // the trusted target owner, rather than a parser error or caller-supplied preview.
        throw new OperationPreparationRefused(OperationResult.failure(refused.getMessage(),
            "FORGET_PREPARATION_REFUSED", Map.of(), false));
      }
      var prepared = plan.prepare(argumentsJson);
      approvalPreview(prepared); // Downstream approval envelope must fit before acceptance/effects.
      return prepared;
    } catch (SettingsCommitOwner.Refused refused) {
      throw new OperationPreparationRefused(OperationResult.failure(refused.response().message(),
          "FORGET_PREPARATION_REFUSED", Map.of(), refused.response().retryable().orElse(false)));
    } catch (IllegalArgumentException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "The exact forget target is invalid, protected or cannot be represented safely", "FORGET_PREPARATION_REFUSED", Map.of(), false));
    } catch (IllegalStateException unavailable) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Forget is unavailable while the index or its authority is changing", "FORGET_PREPARATION_REFUSED", Map.of(), true));
    }
  }

  @Override public void validatePreparation(OperationPreparation prepared) { ForgetPlan.validate(prepared); }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    var plan = ForgetPlan.validate(prepared);
    return new OperationApprovalPreview("Forget indexed " + plan.scope().name().toLowerCase(java.util.Locale.ROOT)
        + " " + exactPath(plan.path()) + ". Remove it from search and the index. "
        + (plan.keepOutRequired()
            ? "Keep this exact path out of later indexing; folder keep-out includes descendants. "
            : "This file is outside watched roots: no permanent keep-out is added, and a new explicit add or import can restore it. ")
        + "Original files and retained answers, logs and backups are not erased.");
  }

  static String exactPath(String path) {
    String quoted = HandlerJson.MAPPER.writeValueAsString(path);
    var visible = new StringBuilder(quoted.length());
    quoted.codePoints().forEach(code -> {
      if (code != ' ' && (Character.isWhitespace(code) || Character.isSpaceChar(code)
          || Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)) {
        for (char unit : Character.toChars(code)) {
          visible.append("\\u").append(Integer.toHexString(unit | 0x10000).substring(1));
        }
      } else visible.appendCodePoint(code);
    });
    int trailing = 0;
    for (int i = path.length(); i > 0 && Character.isWhitespace(path.charAt(i - 1)); i--) trailing++;
    return visible + (trailing == 0 ? "" : " (" + trailing + " trailing whitespace character(s))");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    requireWebview(context);
    validatePreparation(prepared);
    return owner.get().execute(Objects.requireNonNull(record, "Accepted forget record"), context);
  }

  static void requireWebview(EngineContext context) {
    if (context == null || context.clientKind() != EngineContext.ClientKind.WEBVIEW) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Only the webview can forget indexed paths", "FORGET_REQUIRES_USER", Map.of(), false));
    }
  }
}

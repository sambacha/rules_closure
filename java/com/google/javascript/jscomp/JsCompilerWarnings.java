/*
 * Copyright 2016 The Closure Rules Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.javascript.jscomp.JsCheckerHelper.convertPathToModuleName;
import static com.google.javascript.jscomp.JsCheckerHelper.isInSyntheticCode;

import com.google.common.collect.Multimap;
import java.util.List;
import java.util.Set;

/**
 * Compiler warnings configuration for {@link JsCompiler}.
 *
 * <p>This class determines which compiler passes should be enabled and whether or not the errors
 * they produce should be ignored or treated as warnings. By default we enable all checks and treat
 * them as errors, with certain exceptions. See {@link JsCompiler} for more information.
 */
final class JsCompilerWarnings extends WarningsGuard {

  private final List<String> roots;
  private final Set<String> legacyModules;
  private final Multimap<String, DiagnosticType> suppressions;
  private final Set<DiagnosticType> globalSuppressions;

  JsCompilerWarnings(
      List<String> roots,
      Set<String> legacyModules,
      Multimap<String, DiagnosticType> suppressions,
      Set<DiagnosticType> globalSuppressions) {
    this.roots = roots;
    this.legacyModules = legacyModules;
    this.suppressions = suppressions;
    this.globalSuppressions = globalSuppressions;
  }

  @Override
  protected boolean enables(DiagnosticGroup group) {
    // Oh yes, we want all the wonderful checks the Closure Compiler performs. That is with the
    // exception of the things JsChecker alone is responsible for checking.
    return !Diagnostics.JSCHECKER_ONLY_GROUPS.contains(group);
  }

  @Override
  public CheckLevel level(JSError error) {
    CheckLevel level = CheckLevel.ERROR;

    // Closure Rules will always ignore these checks no matter what.
    if (Diagnostics.IGNORE_ALWAYS.contains(error.getType())) {
      return CheckLevel.OFF;
    }

    // Some checks, e.g. linting, are handled *exclusively* by JsChecker.
    if (Diagnostics.JSCHECKER_ONLY_SUPPRESS_CODES.contains(error.getType().key)) {
      return CheckLevel.OFF;
    }

    // Synthetic code is generated by compiler passes.
    if (isInSyntheticCode(error)) {
      // These are the checks we know synthetic code currently isn't very good at following.
      if (Diagnostics.IGNORE_FOR_SYNTHETIC.contains(error.getType())) {
        return CheckLevel.OFF;
      }
      // If there are any others, we'll display a warning, because it's impossible for the user to
      // address these errors. We're not ignoring them entirely because we want to encourage users
      // to file bugs so the compiler pass can be fixed.
      return CheckLevel.WARNING;
    }

    // Some errors are independent of code, e.g. flag misuse.
    if (error.sourceName == null) {
      return CheckLevel.ERROR;
    }

    for (String module : convertPathToModuleName(error.sourceName, roots).asSet()) {
      if (legacyModules.contains(module)) {
        // Ignore it entirely if it's very noisy.
        if (Diagnostics.IGNORE_FOR_LEGACY.contains(error.getType())) {
          return CheckLevel.OFF;
        }
        // Otherwise downgrade to a warning, since it's not easily actionable.
        level = CheckLevel.WARNING;
      } else if (suppressions.containsEntry(module, error.getType())) {
        // If a closure_js_library() defined this source file, then check if that library rule
        // defined a suppress code to make this error go away.
        return CheckLevel.OFF;
      }
    }

    // We provide an escape hatch for situations in which it's not possible (or too burdensome) to
    // add a suppress code to the closure_js_library() rule responsible for the error.
    if (globalSuppressions.contains(error.getType())) {
      return CheckLevel.OFF;
    }

    // Otherwise we'll be cautious and just assume it's bad.
    return level;
  }
}
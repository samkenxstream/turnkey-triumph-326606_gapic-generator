/* Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.transformer;

import com.google.api.codegen.OutputSpec;
import com.google.api.codegen.SampleValueSet;
import com.google.api.codegen.config.FieldConfig;
import com.google.api.codegen.config.FieldModel;
import com.google.api.codegen.config.MethodModel;
import com.google.api.codegen.config.TypeModel;
import com.google.api.codegen.util.Name;
import com.google.api.codegen.util.Scanner;
import com.google.api.codegen.viewmodel.OutputView;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

class OutputTransformer {
  private static final String RESPONSE_PLACEHOLDER = "$resp";

  static List<OutputSpec> defaultOutputSpecs(MethodModel method) {
    if (method.isOutputTypeEmpty()) {
      return Collections.emptyList();
    }
    return Collections.singletonList(
        OutputSpec.newBuilder().addPrint("%s").addPrint(RESPONSE_PLACEHOLDER).build());
  }

  static List<OutputView> toViews(
      List<OutputSpec> configs, MethodContext context, SampleValueSet valueSet) {
    ScopeTable localVars = new ScopeTable();
    return configs
        .stream()
        .map(s -> OutputTransformer.toView(s, context, valueSet, localVars))
        .collect(ImmutableList.toImmutableList());
  }

  private static OutputView toView(
      OutputSpec config, MethodContext context, SampleValueSet valueSet, ScopeTable localVars) {
    Runnable once =
        new Runnable() {
          boolean ran;

          @Override
          public void run() {
            Preconditions.checkArgument(
                !ran,
                "%s:%s: only one field of OutputSpec may be set",
                context.getMethodModel().getSimpleName(),
                valueSet.getId());
            ran = true;
          }
        };

    OutputView view = null;
    if (config.hasLoop()) {
      once.run();
      view = loopView(config.getLoop(), context, valueSet, localVars);
    }
    if (config.getPrintCount() > 0) {
      once.run();
      view = printView(config.getPrintList(), context, valueSet, localVars);
    }
    if (!config.getDefine().isEmpty()) {
      once.run();
      view = defineView(new Scanner(config.getDefine()), context, valueSet, localVars);
    }

    return Preconditions.checkNotNull(
        view,
        "%s:%s: one field of OutputSpec must be set",
        context.getMethodModel().getSimpleName(),
        valueSet.getId());
  }

  private static OutputView.PrintView printView(
      List<String> config, MethodContext context, SampleValueSet valueSet, ScopeTable localVars) {
    Preconditions.checkArgument(
        !config.isEmpty(),
        "%s:%s: print spec cannot be empty",
        context.getMethodModel().getSimpleName(),
        valueSet.getId());

    return OutputView.PrintView.newBuilder()
        .format(context.getNamer().getPrintSpec(config.get(0)))
        .args(
            config
                .subList(1, config.size())
                .stream()
                .map(a -> accessor(new Scanner(a), context, valueSet, localVars))
                .collect(ImmutableList.toImmutableList()))
        .build();
  }

  private static OutputView.LoopView loopView(
      OutputSpec.LoopStatement loop,
      MethodContext context,
      SampleValueSet valueSet,
      ScopeTable localVars) {

    ScopeTable scope = localVars.newChild();
    OutputView.VariableView accessor =
        accessorNewVariable(
            new Scanner(loop.getCollection()), context, valueSet, scope, loop.getVariable(), true);
    OutputView.LoopView ret =
        OutputView.LoopView.newBuilder()
            .variableType(scope.getTypeName(loop.getVariable()))
            .variableName(context.getNamer().localVarName(Name.from(loop.getVariable())))
            .collection(accessor)
            .body(
                loop.getBodyList()
                    .stream()
                    .map(body -> toView(body, context, valueSet, scope))
                    .collect(ImmutableList.toImmutableList()))
            .build();
    return ret;
  }

  private static OutputView.DefineView defineView(
      Scanner definition, MethodContext context, SampleValueSet valueSet, ScopeTable localVars) {
    Preconditions.checkArgument(
        definition.scan() == Scanner.IDENT,
        "%s:%s: expected identifier: %s",
        context.getMethodModel().getSimpleName(),
        valueSet.getId(),
        definition.input());
    String identifier = definition.tokenStr();
    Preconditions.checkArgument(
        definition.scan() == '=',
        "%s:%s invalid definition, expecting '=': %s",
        context.getMethodModel().getSimpleName(),
        valueSet.getId(),
        definition.input());
    OutputView.VariableView reference =
        accessorNewVariable(definition, context, valueSet, localVars, identifier, false);
    return OutputView.DefineView.newBuilder()
        .variableType(localVars.getTypeName(identifier))
        .variableName(context.getNamer().localVarName(Name.from(identifier)))
        .reference(reference)
        .build();
  }

  private static OutputView.VariableView accessor(
      Scanner config, MethodContext context, SampleValueSet valueSet, ScopeTable localVars) {
    return accessorNewVariable(config, context, valueSet, localVars, null, false);
  }

  /**
   * Parses config and returns accessor the config describes.
   *
   * <p>The config is type-checked. For example, indexing into a scalar field is not allowed. If
   * config refers to a local variable, the variable is looked up in {@code localVars}. If {@code
   * newVar} is not null, it is registered into {@code localVars}. If {@code intoScalar} is true,
   * the config must resolve to a collection type, and the type of the elements is registered
   * instead.
   *
   * <pre><code>
   * Syntax:
   * accessor:
   *   identifier
   *   accessor '[' number ']'
   *   accessor '.' identifier
   * </code></pre>
   */
  private static OutputView.VariableView accessorNewVariable(
      Scanner config,
      MethodContext context,
      SampleValueSet valueSet,
      ScopeTable localVars,
      @Nullable String newVar,
      boolean intoScalar) {

    OutputView.VariableView.Builder view = OutputView.VariableView.newBuilder();

    Preconditions.checkArgument(
        config.scan() == Scanner.IDENT,
        "%s:%s: expected identifier: %s",
        context.getMethodModel().getSimpleName(),
        valueSet.getId(),
        config.input());
    String baseIdentifier = config.tokenStr();

    TypeModel type;
    if (baseIdentifier.equals(RESPONSE_PLACEHOLDER)) {
      view.variable(context.getNamer().getSampleResponseVarName(context));

      if (context.getMethodConfig().getPageStreaming() != null) {
        FieldConfig resourceFieldConfig =
            context.getMethodConfig().getPageStreaming().getResourcesFieldConfig();

        // change the element of the output of page streaming call to a resource name
        if (context.getFeatureConfig().useResourceNameFormatOption(resourceFieldConfig)) {
          String typeName =
              context
                  .getNamer()
                  .getAndSaveElementResourceTypeName(context.getTypeTable(), resourceFieldConfig);

          Preconditions.checkArgument(
              config.scan() == Scanner.EOF,
              "%s:%s: accessing fields of resource names is not supported",
              context.getMethodModel().getSimpleName(),
              valueSet.getId());

          if (newVar != null) {
            Preconditions.checkArgument(
                !intoScalar,
                "%s:%s: cannot parse a resource name into scalar",
                context.getMethodModel().getSimpleName(),
                valueSet.getId());

            if (!localVars.put(newVar, null, typeName)) {
              throw new IllegalStateException(
                  String.format(
                      "%s:%s: duplicated variable declaration not allowed: %s",
                      context.getMethodModel().getSimpleName(), valueSet.getId(), newVar));
            }
          }
          return view.accessors(ImmutableList.<String>of()).build();
        } else {
          type =
              context
                  .getMethodConfig()
                  .getPageStreaming()
                  .getResourcesFieldConfig()
                  .getField()
                  .getType()
                  .makeOptional();
        }
      } else {
        type = context.getMethodModel().getOutputType();
      }
    } else {
      view.variable(context.getNamer().localVarName(Name.from(baseIdentifier)));
      type = localVars.getType(baseIdentifier);
      if (type == null) {
        String typeName =
            Preconditions.checkNotNull(
                localVars.getTypeName(baseIdentifier),
                "%s:%s: variable not defined: %s",
                context.getMethodModel().getSimpleName(),
                valueSet.getId(),
                baseIdentifier);
        Preconditions.checkArgument(
            config.scan() == Scanner.EOF,
            "%s:%s: accessing fields of resource names is not supported",
            context.getMethodModel().getSimpleName(),
            valueSet.getId());
        return view.accessors(ImmutableList.<String>of()).build();
      }
    }

    int token;
    ImmutableList.Builder<String> accessors = ImmutableList.builder();
    while ((token = config.scan()) != Scanner.EOF) {
      if (token == '.') {
        Preconditions.checkArgument(
            type.isMessage(),
            "%s:%s: %s is not a message",
            context.getMethodModel().getSimpleName(),
            valueSet.getId(),
            config.input());
        Preconditions.checkArgument(
            !type.isRepeated() && !type.isMap(),
            "%s:%s: %s is not scalar",
            context.getMethodModel().getSimpleName(),
            valueSet.getId(),
            config.input());

        Preconditions.checkArgument(
            config.scan() == Scanner.IDENT,
            "%s:%s: expected identifier: %s",
            context.getMethodModel().getSimpleName(),
            valueSet.getId(),
            config.input());
        String fieldName = config.tokenStr();
        FieldModel field =
            Preconditions.checkNotNull(
                type.getField(fieldName),
                "%s:%s: type %s does not have field %s",
                context.getMethodModel().getSimpleName(),
                valueSet.getId(),
                type,
                fieldName);

        type = field.getType();
        accessors.add(context.getNamer().getFieldGetFunctionName(field));
      } else if (token == '[') {
        Preconditions.checkArgument(
            type.isRepeated() && !type.isMap(),
            "%s:%s: %s is not a repeated field",
            context.getMethodModel().getSimpleName(),
            valueSet.getId(),
            config.input());
        throw new UnsupportedOperationException("array indexing not supported yet");
      } else {
        throw new IllegalArgumentException(
            String.format(
                "%s:%s: unexpected character: %c (%d)",
                context.getMethodModel().getSimpleName(), valueSet.getId(), token, token));
      }
    }

    if (newVar != null) {
      if (intoScalar) {
        Preconditions.checkArgument(
            type.isRepeated() && !type.isMap(),
            "%s:%s: %s is not a repeated field",
            context.getMethodModel().getSimpleName(),
            valueSet.getId(),
            config.input());
        type = type.makeOptional(); // "optional" is how protobuf defines singular fields
      }
      String typeName = context.getNamer().getAndSaveTypeName(context.getTypeTable(), type);
      if (!localVars.put(newVar, type, typeName)) {
        throw new IllegalStateException(
            String.format(
                "%s:%s: duplicated variable declaration not allowed: %s",
                context.getMethodModel().getSimpleName(), valueSet.getId(), newVar));
      }
    }

    return view.accessors(accessors.build()).build();
  }

  /**
   * Tracks the variables that were defined for this sample and the subset that is currently in
   * scope. We do this by maintaining two scopes: sample and local.
   *
   * <p>Sample keeps track of all variable declared by the output specs. We need this because
   * variables are function-scoped in many dynamic languages, and we should error if the spec
   * declares a variable with the same name twice.
   *
   * <p>Local keeps track of variables in the current block. We need this because variables are
   * block-scoped in many static languages, and we should error if the spec uses a variable not in
   * the nested blocks currently in scope.
   */
  private static class ScopeTable {
    private final Set<String> sample;
    @Nullable private final ScopeTable parent;
    private final Map<String, TypeModel> types = new HashMap<>();
    private final Map<String, String> typeNames = new HashMap<>();

    private ScopeTable() {
      sample = new HashSet<>();
      parent = null;
    }

    private ScopeTable(ScopeTable parent) {
      Preconditions.checkNotNull(parent);
      sample = parent.sample;
      this.parent = parent;
    }

    /** Gets the type of the variable. Returns null if the variable is not found. */
    private TypeModel getType(String name) {
      ScopeTable table = this;
      while (table != null) {
        TypeModel type = table.types.get(name);
        if (type != null) {
          return type;
        }
        table = table.parent;
      }
      return null;
    }

    /**
     * Gets the type name of the variable. Returns null if the variable is not found. This is mostly
     * used for resource name since they do not have a {@code TypeModel}.
     */
    private String getTypeName(String name) {
      ScopeTable table = this;
      while (table != null) {
        String typeName = table.typeNames.get(name);
        if (typeName != null) {
          return typeName;
        }
        table = table.parent;
      }
      return null;
    }

    /**
     * Associates a variable with a type in the current scope. Returns whether the insertion was
     * successful.
     *
     * <p>{@code type} could be left null if {@code typeName} is not associated with a {@code
     * TypeModel}, for example, when {@code typeName} is a resource name.
     */
    private boolean put(String name, @Nullable TypeModel type, String typeName) {
      if (!sample.add(name)) {
        return false;
      }
      if (type == null) {
        typeNames.put(name, typeName);
        return true;
      }
      types.put(name, type);
      typeNames.put(name, typeName);
      return true;
    }

    private ScopeTable newChild() {
      return new ScopeTable(this);
    }
  }
}

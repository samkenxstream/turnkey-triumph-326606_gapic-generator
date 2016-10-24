/* Copyright 2016 Google Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.config;

import com.google.api.codegen.VisibilityProto;
import com.google.api.codegen.transformer.SurfaceNamer;
import com.google.api.codegen.util.Name;
import com.google.common.collect.ImmutableMap;

public enum VisibilityConfig {
  PUBLIC,
  PRIVATE,
  DISABLED;

  private static ImmutableMap<VisibilityProto, VisibilityConfig> protoMap =
      ImmutableMap.of(
          VisibilityProto.PUBLIC,
          PUBLIC,
          VisibilityProto.PRIVATE,
          PRIVATE,
          VisibilityProto.DISABLED,
          DISABLED);

  public static VisibilityConfig fromProto(VisibilityProto proto) {
    return protoMap.get(proto);
  }

  public String methodName(SurfaceNamer namer, Name name) {
    switch (this) {
      case PUBLIC:
        return namer.publicMethodName(name);
      case PRIVATE:
        return namer.privateMethodName(name);
      case DISABLED:
        throw new IllegalStateException("disabled methods cannot be named");
    }
    throw new IllegalStateException("unhandled case " + this);
  }
}

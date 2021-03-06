// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.packages;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.protobuf.TextFormat;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** An instance (in the Skylark sense, not Java) of a {@link Provider}. */
@SkylarkModule(
  name = "struct",
  category = SkylarkModuleCategory.BUILTIN,
  doc =
      "A generic object with fields. See the global <a href=\"globals.html#struct\"><code>struct"
          + "</code></a> function for more details."
          + "<p>Structs fields cannot be reassigned once the struct is created. Two structs are "
          + "equal if they have the same fields and if corresponding field values are equal."
)
public abstract class Info implements ClassObject, SkylarkValue, Serializable {

  /** The {@link Provider} that describes the type of this instance. */
  private final Provider provider;

  /**
   * The Skylark location where this provider instance was created.
   *
   * <p>Built-in provider instances may use {@link Location#BUILTIN}.
   */
  @VisibleForSerialization protected final Location location;

  /**
   * Constructs an {@link Info}.
   *
   * @param provider the provider describing the type of this instance
   * @param location the Skylark location where this instance is created. If null, defaults to
   *     {@link Location#BUILTIN}.
   */
  protected Info(Provider provider, @Nullable Location location) {
    this.provider = Preconditions.checkNotNull(provider);
    this.location = location == null ? Location.BUILTIN : location;
  }

  /**
   * Preprocesses a map of field values to convert the field names and field values to
   * Skylark-acceptable names and types.
   *
   * <p>This preserves the order of the map entries.
   */
  protected static ImmutableMap<String, Object> copyValues(Map<String, Object> values) {
    Preconditions.checkNotNull(values);
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    for (Map.Entry<String, Object> e : values.entrySet()) {
      builder.put(
          Attribute.getSkylarkName(e.getKey()),
          SkylarkType.convertToSkylark(e.getValue(), (Environment) null));
    }
    return builder.build();
  }

  /**
   * Returns the Skylark location where this provider instance was created.
   *
   * <p>Builtin provider instances may return {@link Location#BUILTIN}.
   */
  public Location getCreationLoc() {
    return location;
  }

  public Provider getProvider() {
    return provider;
  }

  /**
   * Returns whether the given field name exists.
   *
   * <p>This conceptually extends the API for {@link ClassObject}.
   */
  public abstract boolean hasField(String name);

  /**
   * <p>Wraps {@link ClassObject#getValue(String)}, returning null in cases where
   * {@link EvalException} would have been thrown.
   */
  @VisibleForTesting
  public Object getValueOrNull(String name) {
    try {
      return getValue(name);
    } catch (EvalException e) {
      return null;
    }
  }

  /**
   * Returns the result of {@link #getValue(String)}, cast as the given type, throwing {@link
   * EvalException} if the cast fails.
   */
  public <T> T getValue(String key, Class<T> type) throws EvalException {
    Object obj = getValue(key);
    if (obj == null) {
      return null;
    }
    SkylarkType.checkType(obj, type, key);
    return type.cast(obj);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overrides {@link ClassObject#getFieldNames()}, but does not allow {@link EvalException} to
   * be thrown.
   */
  @Override
  public abstract ImmutableCollection<String> getFieldNames();

  /**
   * Returns the error message format to use for unknown fields.
   *
   * <p>By default, it is the one specified by the provider.
   */
  protected String getErrorMessageFormatForUnknownField() {
    return provider.getErrorMessageFormatForUnknownField();
  }

  @Override
  public String getErrorMessageForUnknownField(String name) {
    String suffix = "Available attributes: "
        + Joiner.on(", ").join(Ordering.natural().sortedCopy(getFieldNames()));
    return String.format(getErrorMessageFormatForUnknownField(), name) + "\n" + suffix;
  }

  @Override
  public boolean equals(Object otherObject) {
    if (!(otherObject instanceof Info)) {
      return false;
    }
    Info other = (Info) otherObject;
    if (this == other) {
      return true;
    }
    if (!this.provider.equals(other.provider)) {
      return false;
    }
    // Compare objects' fields and their values
    if (!this.getFieldNames().equals(other.getFieldNames())) {
      return false;
    }
    for (String field : getFieldNames()) {
      if (!Objects.equal(this.getValueOrNull(field), other.getValueOrNull(field))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    List<String> fields = new ArrayList<>(getFieldNames());
    Collections.sort(fields);
    List<Object> objectsToHash = new ArrayList<>();
    objectsToHash.add(provider);
    for (String field : fields) {
      objectsToHash.add(field);
      objectsToHash.add(getValueOrNull(field));
    }
    return Objects.hashCode(objectsToHash.toArray());
  }

  /**
   * Convert the object to string using Skylark syntax. The output tries to be reversible (but there
   * is no guarantee, it depends on the actual values).
   */
  @Override
  public void repr(SkylarkPrinter printer) {
    boolean first = true;
    printer.append("struct(");
    // Sort by key to ensure deterministic output.
    for (String fieldName : Ordering.natural().sortedCopy(getFieldNames())) {
      if (!first) {
        printer.append(", ");
      }
      first = false;
      printer.append(fieldName);
      printer.append(" = ");
      printer.repr(getValueOrNull(fieldName));
    }
    printer.append(")");
  }

  @SkylarkCallable(
      name = "to_proto",
      doc =
          "Creates a text message from the struct parameter. This method only works if all "
              + "struct elements (recursively) are strings, ints, booleans, other structs or a "
              + "list of these types. Quotes and new lines in strings are escaped. "
              + "Keys are iterated in the sorted order. "
              + "Examples:<br><pre class=language-python>"
              + "struct(key=123).to_proto()\n# key: 123\n\n"
              + "struct(key=True).to_proto()\n# key: true\n\n"
              + "struct(key=[1, 2, 3]).to_proto()\n# key: 1\n# key: 2\n# key: 3\n\n"
              + "struct(key='text').to_proto()\n# key: \"text\"\n\n"
              + "struct(key=struct(inner_key='text')).to_proto()\n"
              + "# key {\n#   inner_key: \"text\"\n# }\n\n"
              + "struct(key=[struct(inner_key=1), struct(inner_key=2)]).to_proto()\n"
              + "# key {\n#   inner_key: 1\n# }\n# key {\n#   inner_key: 2\n# }\n\n"
              + "struct(key=struct(inner_key=struct(inner_inner_key='text'))).to_proto()\n"
              + "# key {\n#    inner_key {\n#     inner_inner_key: \"text\"\n#   }\n# }\n</pre>",
      useLocation = true
  )
  public String toProto(Location loc) throws EvalException {
    StringBuilder sb = new StringBuilder();
    printProtoTextMessage(this, sb, 0, loc);
    return sb.toString();
  }

  private void printProtoTextMessage(
      ClassObject object, StringBuilder sb, int indent, Location loc) throws EvalException {
    // For determinism sort the fields alphabetically.
    List<String> fields = new ArrayList<>(object.getFieldNames());
    Collections.sort(fields);
    for (String field : fields) {
      printProtoTextMessage(field, object.getValue(field), sb, indent, loc);
    }
  }

  private void printProtoTextMessage(
      String key, Object value, StringBuilder sb, int indent, Location loc, String container)
      throws EvalException {
    if (value instanceof ClassObject) {
      print(sb, key + " {", indent);
      printProtoTextMessage((ClassObject) value, sb, indent + 1, loc);
      print(sb, "}", indent);
    } else if (value instanceof String) {
      print(
          sb,
          key + ": \"" + escapeDoubleQuotesAndBackslashesAndNewlines((String) value) + "\"",
          indent);
    } else if (value instanceof Integer) {
      print(sb, key + ": " + value, indent);
    } else if (value instanceof Boolean) {
      // We're relying on the fact that Java converts Booleans to Strings in the same way
      // as the protocol buffers do.
      print(sb, key + ": " + value, indent);
    } else {
      throw new EvalException(
          loc,
          "Invalid text format, expected a struct, a string, a bool, or an int but got a "
              + EvalUtils.getDataTypeName(value)
              + " for "
              + container
              + " '"
              + key
              + "'");
    }
  }

  private void printProtoTextMessage(
      String key, Object value, StringBuilder sb, int indent, Location loc)
      throws EvalException {
    if (value instanceof SkylarkList) {
      for (Object item : ((SkylarkList) value)) {
        // TODO(bazel-team): There should be some constraint on the fields of the structs
        // in the same list but we ignore that for now.
        printProtoTextMessage(key, item, sb, indent, loc, "list element in struct field");
      }
    } else {
      printProtoTextMessage(key, value, sb, indent, loc, "struct field");
    }
  }

  private void print(StringBuilder sb, String text, int indent) {
    for (int i = 0; i < indent; i++) {
      sb.append("  ");
    }
    sb.append(text);
    sb.append("\n");
  }

  /**
   * Escapes the given string for use in proto/JSON string.
   *
   * <p>This escapes double quotes, backslashes, and newlines.
   */
  private static String escapeDoubleQuotesAndBackslashesAndNewlines(String string) {
    return TextFormat.escapeDoubleQuotesAndBackslashes(string).replace("\n", "\\n");
  }

  @SkylarkCallable(
      name = "to_json",
      doc =
          "Creates a JSON string from the struct parameter. This method only works if all "
              + "struct elements (recursively) are strings, ints, booleans, other structs or a "
              + "list of these types. Quotes and new lines in strings are escaped. "
              + "Examples:<br><pre class=language-python>"
              + "struct(key=123).to_json()\n# {\"key\":123}\n\n"
              + "struct(key=True).to_json()\n# {\"key\":true}\n\n"
              + "struct(key=[1, 2, 3]).to_json()\n# {\"key\":[1,2,3]}\n\n"
              + "struct(key='text').to_json()\n# {\"key\":\"text\"}\n\n"
              + "struct(key=struct(inner_key='text')).to_json()\n"
              + "# {\"key\":{\"inner_key\":\"text\"}}\n\n"
              + "struct(key=[struct(inner_key=1), struct(inner_key=2)]).to_json()\n"
              + "# {\"key\":[{\"inner_key\":1},{\"inner_key\":2}]}\n\n"
              + "struct(key=struct(inner_key=struct(inner_inner_key='text'))).to_json()\n"
              + "# {\"key\":{\"inner_key\":{\"inner_inner_key\":\"text\"}}}\n</pre>",
      useLocation = true
  )
  public String toJson(Location loc) throws EvalException {
    StringBuilder sb = new StringBuilder();
    printJson(this, sb, loc, "struct field", null);
    return sb.toString();
  }

  private void printJson(
      Object value, StringBuilder sb, Location loc, String container, String key)
      throws EvalException {
    if (value == Runtime.NONE) {
      sb.append("null");
    } else if (value instanceof ClassObject) {
      sb.append("{");

      String join = "";
      for (String field : ((ClassObject) value).getFieldNames()) {
        sb.append(join);
        join = ",";
        sb.append("\"");
        sb.append(field);
        sb.append("\":");
        printJson(((ClassObject) value).getValue(field), sb, loc, "struct field", field);
      }
      sb.append("}");
    } else if (value instanceof List) {
      sb.append("[");
      String join = "";
      for (Object item : ((List) value)) {
        sb.append(join);
        join = ",";
        printJson(item, sb, loc, "list element in struct field", key);
      }
      sb.append("]");
    } else if (value instanceof String) {
      sb.append("\"");
      sb.append(jsonEscapeString((String) value));
      sb.append("\"");
    } else if (value instanceof Integer || value instanceof Boolean) {
      sb.append(value);
    } else {
      String errorMessage =
          "Invalid text format, expected a struct, a string, a bool, or an int "
              + "but got a "
              + EvalUtils.getDataTypeName(value)
              + " for "
              + container;
      if (key != null) {
        errorMessage += " '" + key + "'";
      }
      throw new EvalException(loc, errorMessage);
    }
  }

  private String jsonEscapeString(String string) {
    return escapeDoubleQuotesAndBackslashesAndNewlines(string)
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  @Override
  public String toString() {
    return Printer.repr(this);
  }
}

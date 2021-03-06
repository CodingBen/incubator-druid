/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.orc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.ql.io.orc.OrcStruct;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

public class OrcHadoopInputRowParser implements InputRowParser<OrcStruct>
{

  static final String MAP_CHILD_TAG = "<CHILD>";
  static final String MAP_PARENT_TAG = "<PARENT>";
  static final String DEFAULT_MAP_FIELD_NAME_FORMAT = MAP_PARENT_TAG + "_" + MAP_CHILD_TAG;


  private final ParseSpec parseSpec;
  private final String typeString;
  private final String mapFieldNameFormat;
  private final String mapParentFieldNameFormat;
  private final List<String> dimensions;
  private final StructObjectInspector oip;



  @JsonCreator
  public OrcHadoopInputRowParser(
      @JsonProperty("parseSpec") ParseSpec parseSpec,
      @JsonProperty("typeString") String typeString,
      @JsonProperty("mapFieldNameFormat") String mapFieldNameFormat
  )
  {
    this.parseSpec = parseSpec;
    this.typeString = typeString == null ? typeStringFromParseSpec(parseSpec) : typeString;
    this.mapFieldNameFormat =
        mapFieldNameFormat == null ||
        !mapFieldNameFormat.contains(MAP_PARENT_TAG) ||
        !mapFieldNameFormat.contains(MAP_CHILD_TAG) ? DEFAULT_MAP_FIELD_NAME_FORMAT : mapFieldNameFormat;
    this.mapParentFieldNameFormat = StringUtils.replace(this.mapFieldNameFormat, MAP_PARENT_TAG, "%s");
    this.dimensions = parseSpec.getDimensionsSpec().getDimensionNames();
    this.oip = makeObjectInspector(this.typeString);
  }

  @SuppressWarnings("ArgumentParameterSwap")
  @Override
  public List<InputRow> parseBatch(OrcStruct input)
  {
    Map<String, Object> map = new HashMap<>();
    List<? extends StructField> fields = oip.getAllStructFieldRefs();
    for (StructField field : fields) {
      ObjectInspector objectInspector = field.getFieldObjectInspector();
      switch (objectInspector.getCategory()) {
        case PRIMITIVE:
          PrimitiveObjectInspector primitiveObjectInspector = (PrimitiveObjectInspector) objectInspector;
          map.put(
              field.getFieldName(),
              coercePrimitiveObject(
                  primitiveObjectInspector,
                  oip.getStructFieldData(input, field)
              )
          );
          break;
        case LIST:  // array case - only 1-depth array supported yet
          ListObjectInspector listObjectInspector = (ListObjectInspector) objectInspector;
          map.put(
              field.getFieldName(),
              getListObject(listObjectInspector, oip.getStructFieldData(input, field))
          );
          break;
        case MAP:
          MapObjectInspector mapObjectInspector = (MapObjectInspector) objectInspector;
          getMapObject(field.getFieldName(), mapObjectInspector, oip.getStructFieldData(input, field), map);
          break;
        default:
          break;
      }
    }

    TimestampSpec timestampSpec = parseSpec.getTimestampSpec();
    DateTime dateTime = timestampSpec.extractTimestamp(map);

    final List<String> dimensions;
    if (!this.dimensions.isEmpty()) {
      dimensions = this.dimensions;
    } else {
      dimensions = Lists.newArrayList(
          Sets.difference(map.keySet(), parseSpec.getDimensionsSpec().getDimensionExclusions())
      );
    }
    return ImmutableList.of(new MapBasedInputRow(dateTime, dimensions, map));
  }

  private List getListObject(ListObjectInspector listObjectInspector, Object listObject)
  {
    if (listObjectInspector.getListLength(listObject) < 0) {
      return null;
    }
    List<?> objectList = listObjectInspector.getList(listObject);
    List<?> list = null;
    ObjectInspector child = listObjectInspector.getListElementObjectInspector();
    switch (child.getCategory()) {
      case PRIMITIVE:
        final PrimitiveObjectInspector primitiveObjectInspector = (PrimitiveObjectInspector) child;
        list = objectList.stream()
                         .map(input -> coercePrimitiveObject(primitiveObjectInspector, input))
                         .collect(Collectors.toList());
        break;
      default:
        break;
    }

    return list;
  }

  private void getMapObject(String parentName, MapObjectInspector mapObjectInspector, Object mapObject, Map<String, Object> parsedMap)
  {
    if (mapObjectInspector.getMapSize(mapObject) < 0) {
      return;
    }
    String mapChildFieldNameFormat = StringUtils.replace(
        StringUtils.format(mapParentFieldNameFormat, parentName),
        MAP_CHILD_TAG,
        "%s"
    );

    Map objectMap = mapObjectInspector.getMap(mapObject);
    PrimitiveObjectInspector key = (PrimitiveObjectInspector) mapObjectInspector.getMapKeyObjectInspector();
    PrimitiveObjectInspector value = (PrimitiveObjectInspector) mapObjectInspector.getMapValueObjectInspector();

    objectMap.forEach((k, v) -> {
      String resolvedFieldName = StringUtils.format(mapChildFieldNameFormat, key.getPrimitiveJavaObject(k).toString());
      parsedMap.put(resolvedFieldName, value.getPrimitiveJavaObject(v));
    });
  }

  @JsonProperty
  public String getMapFieldNameFormat()
  {
    return mapFieldNameFormat;
  }

  @Override
  @JsonProperty
  public ParseSpec getParseSpec()
  {
    return parseSpec;
  }

  @JsonProperty
  public String getTypeString()
  {
    return typeString;
  }

  @Override
  public InputRowParser withParseSpec(ParseSpec parseSpec)
  {
    return new OrcHadoopInputRowParser(parseSpec, typeString, null);
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final OrcHadoopInputRowParser that = (OrcHadoopInputRowParser) o;
    return Objects.equals(parseSpec, that.parseSpec) &&
           Objects.equals(typeString, that.typeString);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(parseSpec, typeString);
  }

  @Override
  public String toString()
  {
    return "OrcHadoopInputRowParser{" +
           "parseSpec=" + parseSpec +
           ", typeString='" + typeString + '\'' +
           '}';
  }

  @VisibleForTesting
  static String typeStringFromParseSpec(ParseSpec parseSpec)
  {
    StringBuilder builder = new StringBuilder("struct<");
    builder.append(parseSpec.getTimestampSpec().getTimestampColumn()).append(":string");
    // the typeString seems positionally dependent, so repeated timestamp column causes incorrect mapping
    if (parseSpec.getDimensionsSpec().getDimensionNames().size() > 0) {
      builder.append(",");
      builder.append(String.join(
          ":string,",
          parseSpec.getDimensionsSpec()
                   .getDimensionNames()
                   .stream()
                   .filter(s -> !s.equals(parseSpec.getTimestampSpec().getTimestampColumn()))
                   .collect(Collectors.toList())));
      builder.append(":string");
    }
    builder.append(">");

    return builder.toString();
  }

  private static Object coercePrimitiveObject(final PrimitiveObjectInspector inspector, final Object object)
  {
    if (object instanceof HiveDecimalWritable) {
      // inspector on HiveDecimal rounds off to integer for some reason.
      return ((HiveDecimalWritable) object).getHiveDecimal().doubleValue();
    } else if (object instanceof DateWritable) {
      return object.toString();
    } else {
      return inspector.getPrimitiveJavaObject(object);
    }
  }

  private static StructObjectInspector makeObjectInspector(final String typeString)
  {
    final OrcSerde serde = new OrcSerde();

    TypeInfo typeInfo = TypeInfoUtils.getTypeInfoFromTypeString(typeString);
    Preconditions.checkArgument(
        typeInfo instanceof StructTypeInfo,
        StringUtils.format("typeString should be struct type but not [%s]", typeString)
    );
    Properties table = getTablePropertiesFromStructTypeInfo((StructTypeInfo) typeInfo);
    serde.initialize(new Configuration(), table);
    try {
      return (StructObjectInspector) serde.getObjectInspector();
    }
    catch (SerDeException e) {
      throw new RuntimeException(e);
    }
  }

  private static Properties getTablePropertiesFromStructTypeInfo(StructTypeInfo structTypeInfo)
  {
    Properties table = new Properties();
    table.setProperty("columns", String.join(",", structTypeInfo.getAllStructFieldNames()));
    table.setProperty("columns.types", String.join(
        ",",
        Lists.transform(
            structTypeInfo.getAllStructFieldTypeInfos(),
            new Function<TypeInfo, String>()
            {
              @Nullable
              @Override
              public String apply(@Nullable TypeInfo typeInfo)
              {
                return typeInfo.getTypeName();
              }
            }
        )
    ));

    return table;
  }
}

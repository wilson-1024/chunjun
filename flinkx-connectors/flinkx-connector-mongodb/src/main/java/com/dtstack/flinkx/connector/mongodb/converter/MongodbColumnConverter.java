/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.connector.mongodb.converter;

import com.dtstack.flinkx.converter.AbstractRowConverter;
import com.dtstack.flinkx.element.AbstractBaseColumn;
import com.dtstack.flinkx.element.ColumnRowData;
import com.dtstack.flinkx.element.column.BigDecimalColumn;
import com.dtstack.flinkx.element.column.BooleanColumn;
import com.dtstack.flinkx.element.column.BytesColumn;
import com.dtstack.flinkx.element.column.StringColumn;
import com.dtstack.flinkx.element.column.TimestampColumn;
import com.dtstack.flinkx.util.DateUtil;

import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.RowType;

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.sql.Date;
import java.sql.Time;

/**
 * @author Ada Wong
 * @program flinkx
 * @create 2021/06/21
 */
public class MongodbColumnConverter
        extends AbstractRowConverter<Document, Document, Document, LogicalType> {

    private final MongoDeserializationConverter[] toInternalConverters;
    private final MongoSerializationConverter[] toExternalConverters;
    private final String[] fieldNames;

    public MongodbColumnConverter(RowType rowType, String[] fieldNames) {
        super(rowType);
        this.fieldNames = fieldNames;
        toInternalConverters = new MongoDeserializationConverter[rowType.getFieldCount()];
        toExternalConverters = new MongoSerializationConverter[rowType.getFieldCount()];
        for (int i = 0; i < rowType.getFieldCount(); i++) {
            toInternalConverters[i] =
                    wrapIntoNullableInternalConverter(
                            createMongoInternalConverter(rowType.getTypeAt(i)));
            toExternalConverters[i] =
                    wrapIntoNullableMongodbExternalConverter(
                            createMongodbExternalConverter(fieldTypes[i]), fieldTypes[i]);
        }
    }

    protected MongoDeserializationConverter wrapIntoNullableInternalConverter(
            MongoDeserializationConverter deserializationConverter) {
        return val -> {
            if (val == null) {
                return null;
            } else {
                return deserializationConverter.deserialize(val);
            }
        };
    }

    protected MongoSerializationConverter wrapIntoNullableMongodbExternalConverter(
            MongoSerializationConverter serializationConverter, LogicalType type) {
        return (val, index, key, document) -> {
            if (val == null
                    || val.isNullAt(index)
                    || LogicalTypeRoot.NULL.equals(type.getTypeRoot())) {
                document.append(key, null);
            } else {
                serializationConverter.serialize(val, index, key, document);
            }
        };
    }

    @Override
    public RowData toInternal(Document document) {
        ColumnRowData data = new ColumnRowData(toInternalConverters.length);
        for (int pos = 0; pos < toInternalConverters.length; pos++) {
            Object field = document.get(fieldNames[pos]);
            if (field instanceof ObjectId) {
                field = field.toString();
            }
            data.addField((AbstractBaseColumn) toInternalConverters[pos].deserialize(field));
        }
        return data;
    }

    @Override
    public Document toExternal(RowData rowData, Document document) {
        for (int pos = 0; pos < rowData.getArity(); pos++) {
            toExternalConverters[pos].serialize(rowData, pos, fieldNames[pos], document);
        }
        return document;
    }

    private MongoDeserializationConverter createMongoInternalConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case BOOLEAN:
                return val -> new BooleanColumn(Boolean.parseBoolean(val.toString()));
            case TINYINT:
                return val -> new BigDecimalColumn(((Integer) val).byteValue());
            case SMALLINT:
            case INTEGER:
                return val -> new BigDecimalColumn((Integer) val);
            case FLOAT:
                return val -> new BigDecimalColumn((Float) val);
            case DOUBLE:
                return val -> new BigDecimalColumn((Double) val);
            case BIGINT:
                return val -> new BigDecimalColumn((Long) val);
            case DECIMAL:
                return val -> new BigDecimalColumn(((Decimal128) val).bigDecimalValue());
            case CHAR:
            case VARCHAR:
                return val -> new StringColumn((String) val);
            case INTERVAL_YEAR_MONTH:
            case DATE:
            case TIME_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_TIME_ZONE:
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return val -> new TimestampColumn((java.util.Date) val);
            case BINARY:
            case VARBINARY:
                return val -> new BytesColumn(((Binary) val).getData());
            default:
                throw new UnsupportedOperationException("Unsupported type:" + type);
        }
    }

    private MongoSerializationConverter createMongodbExternalConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case BOOLEAN:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asBoolean());
            case TINYINT:
                return (val, index, key, document) -> document.append(key, val.getByte(index));
            case SMALLINT:
            case INTEGER:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asInt());
            case FLOAT:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asFloat());
            case DOUBLE:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asDouble());
            case BIGINT:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asLong());
            case DECIMAL:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asBigDecimal());
            case CHAR:
            case VARCHAR:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asString());
            case INTERVAL_YEAR_MONTH:
                return (val, index, key, document) ->
                        document.append(
                                key,
                                ((ColumnRowData) val)
                                        .getField(index)
                                        .asTimestamp()
                                        .toLocalDateTime()
                                        .toLocalDate()
                                        .getYear());
            case DATE:
                return (val, index, key, document) ->
                        document.append(
                                key,
                                Date.valueOf(
                                        ((ColumnRowData) val)
                                                .getField(index)
                                                .asTimestamp()
                                                .toLocalDateTime()
                                                .toLocalDate()));

            case TIME_WITHOUT_TIME_ZONE:
                return (val, index, key, document) ->
                        document.append(
                                key,
                                Time.valueOf(
                                        ((ColumnRowData) val)
                                                .getField(index)
                                                .asTimestamp()
                                                .toLocalDateTime()
                                                .toLocalTime()));

            case TIMESTAMP_WITH_TIME_ZONE:
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asTimestamp());
            case BINARY:
            case VARBINARY:
                return (val, index, key, document) ->
                        document.append(key, ((ColumnRowData) val).getField(index).asBytes());
            default:
                throw new UnsupportedOperationException("Unsupported type:" + type);
        }
    }
}

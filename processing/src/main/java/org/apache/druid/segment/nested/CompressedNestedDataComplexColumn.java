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

package org.apache.druid.segment.nested;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Doubles;
import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.RE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.io.smoosh.SmooshedFileMapper;
import org.apache.druid.query.extraction.ExtractionFn;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.BaseSingleValueDimensionSelector;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.DimensionSelector;
import org.apache.druid.segment.NilColumnValueSelector;
import org.apache.druid.segment.ObjectColumnSelector;
import org.apache.druid.segment.column.BaseColumn;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.DictionaryEncodedColumn;
import org.apache.druid.segment.data.ColumnarDoubles;
import org.apache.druid.segment.data.ColumnarInts;
import org.apache.druid.segment.data.ColumnarLongs;
import org.apache.druid.segment.data.CompressedColumnarDoublesSuppliers;
import org.apache.druid.segment.data.CompressedColumnarLongsSupplier;
import org.apache.druid.segment.data.CompressedVSizeColumnarIntsSupplier;
import org.apache.druid.segment.data.CompressedVariableSizedBlobColumn;
import org.apache.druid.segment.data.CompressedVariableSizedBlobColumnSupplier;
import org.apache.druid.segment.data.FixedIndexed;
import org.apache.druid.segment.data.FrontCodedIntArrayIndexed;
import org.apache.druid.segment.data.GenericIndexed;
import org.apache.druid.segment.data.Indexed;
import org.apache.druid.segment.data.ObjectStrategy;
import org.apache.druid.segment.data.ReadableOffset;
import org.apache.druid.segment.data.VSizeColumnarInts;
import org.apache.druid.segment.data.WritableSupplier;
import org.apache.druid.segment.serde.DictionaryEncodedColumnPartSerde;
import org.apache.druid.segment.serde.NoIndexesColumnIndexSupplier;
import org.apache.druid.segment.vector.NilVectorSelector;
import org.apache.druid.segment.vector.ReadableVectorInspector;
import org.apache.druid.segment.vector.ReadableVectorOffset;
import org.apache.druid.segment.vector.SingleValueDimensionVectorSelector;
import org.apache.druid.segment.vector.VectorObjectSelector;
import org.apache.druid.segment.vector.VectorValueSelector;
import org.apache.druid.utils.CloseableUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of {@link NestedDataComplexColumn} which uses a {@link CompressedVariableSizedBlobColumn} for the
 * 'raw' {@link StructuredData} values and provides selectors for nested 'literal' field columns.
 */
public abstract class CompressedNestedDataComplexColumn<TStringDictionary extends Indexed<ByteBuffer>>
    extends NestedDataComplexColumn
{
  private final NestedDataColumnMetadata metadata;
  private final Closer closer;
  private final CompressedVariableSizedBlobColumnSupplier compressedRawColumnSupplier;
  private CompressedVariableSizedBlobColumn compressedRawColumn;
  private final ImmutableBitmap nullValues;

  private final GenericIndexed<String> fields;
  private final NestedFieldTypeInfo fieldInfo;

  private final Supplier<TStringDictionary> stringDictionarySupplier;
  private final Supplier<FixedIndexed<Long>> longDictionarySupplier;
  private final Supplier<FixedIndexed<Double>> doubleDictionarySupplier;
  private final Supplier<FrontCodedIntArrayIndexed> arrayDictionarySupplier;

  private final SmooshedFileMapper fileMapper;

  private final String rootFieldPath;

  private final ConcurrentHashMap<Integer, ColumnHolder> columns = new ConcurrentHashMap<>();

  private static final ObjectStrategy<Object> STRATEGY = NestedDataComplexTypeSerde.INSTANCE.getObjectStrategy();

  public CompressedNestedDataComplexColumn(
      NestedDataColumnMetadata metadata,
      @SuppressWarnings("unused") ColumnConfig columnConfig,
      CompressedVariableSizedBlobColumnSupplier compressedRawColumnSupplier,
      ImmutableBitmap nullValues,
      GenericIndexed<String> fields,
      NestedFieldTypeInfo fieldInfo,
      Supplier<TStringDictionary> stringDictionary,
      Supplier<FixedIndexed<Long>> longDictionarySupplier,
      Supplier<FixedIndexed<Double>> doubleDictionarySupplier,
      Supplier<FrontCodedIntArrayIndexed> arrayDictionarySupplier,
      SmooshedFileMapper fileMapper,
      String rootFieldPath
  )
  {
    this.metadata = metadata;
    this.nullValues = nullValues;
    this.fields = fields;
    this.fieldInfo = fieldInfo;
    this.stringDictionarySupplier = stringDictionary;
    this.longDictionarySupplier = longDictionarySupplier;
    this.doubleDictionarySupplier = doubleDictionarySupplier;
    this.arrayDictionarySupplier = arrayDictionarySupplier;
    this.fileMapper = fileMapper;
    this.closer = Closer.create();
    this.compressedRawColumnSupplier = compressedRawColumnSupplier;
    this.rootFieldPath = rootFieldPath;
  }

  public abstract List<NestedPathPart> parsePath(String path);

  public abstract String getField(List<NestedPathPart> path);

  public abstract String getFieldFileName(String fileNameBase, String field, int fieldIndex);

  public GenericIndexed<String> getFields()
  {
    return fields;
  }

  @Override
  public List<List<NestedPathPart>> getNestedFields()
  {
    List<List<NestedPathPart>> fieldParts = new ArrayList<>(fields.size());
    for (int i = 0; i < fields.size(); i++) {
      fieldParts.add(parsePath(fields.get(i)));
    }
    return fieldParts;
  }

  public NestedFieldTypeInfo getFieldInfo()
  {
    return fieldInfo;
  }

  public TStringDictionary getStringDictionary()
  {
    return stringDictionarySupplier.get();
  }

  public FixedIndexed<Long> getLongDictionary()
  {
    return longDictionarySupplier.get();
  }

  public FixedIndexed<Double> getDoubleDictionary()
  {
    return doubleDictionarySupplier.get();
  }

  public FrontCodedIntArrayIndexed getArrayDictionary()
  {
    return arrayDictionarySupplier.get();
  }

  public Iterable<Object[]> getArraysIterable()
  {
    Iterable<Object[]> arrays = () -> {
      final TStringDictionary stringDictionary = stringDictionarySupplier.get();
      final FixedIndexed<Long> longDictionary = longDictionarySupplier.get();
      final FixedIndexed<Double> doubleDictionary = doubleDictionarySupplier.get();

      return new Iterator<Object[]>()
      {
        final Iterator<int[]> delegate = arrayDictionarySupplier.get().iterator();

        @Override
        public boolean hasNext()
        {
          return delegate.hasNext();
        }

        @Override
        public Object[] next()
        {
          final int[] next = delegate.next();
          final Object[] nextArray = new Object[next.length];
          for (int i = 0; i < nextArray.length; i++) {
            nextArray[i] = lookupId(next[i]);
          }
          return nextArray;
        }

        private Object lookupId(int globalId)
        {
          if (globalId == 0) {
            return null;
          }
          final int adjustLongId = stringDictionary.size();
          final int adjustDoubleId = stringDictionary.size() + longDictionary.size();
          if (globalId < adjustLongId) {
            return StringUtils.fromUtf8Nullable(stringDictionary.get(globalId));
          } else if (globalId < adjustDoubleId) {
            return longDictionary.get(globalId - adjustLongId);
          } else if (globalId < adjustDoubleId + doubleDictionary.size()) {
            return doubleDictionary.get(globalId - adjustDoubleId);
          }
          throw new IAE("Unknown globalId [%s]", globalId);
        }
      };
    };
    return arrays;
  }

  public ImmutableBitmap getNullValues()
  {
    return nullValues;
  }

  @Nullable
  @Override
  public Object getRowValue(int rowNum)
  {
    if (nullValues.get(rowNum)) {
      return null;
    }

    if (compressedRawColumn == null) {
      compressedRawColumn = closer.register(compressedRawColumnSupplier.get());
    }

    final ByteBuffer valueBuffer = compressedRawColumn.get(rowNum);
    return STRATEGY.fromByteBuffer(valueBuffer, valueBuffer.remaining());
  }

  @Override
  public ColumnValueSelector<?> makeColumnValueSelector(ReadableOffset offset)
  {
    if (fields.size() == 1 && rootFieldPath.equals(fields.get(0))) {
      return makeColumnValueSelector(
          ImmutableList.of(),
          offset
      );
    }
    if (compressedRawColumn == null) {
      compressedRawColumn = closer.register(compressedRawColumnSupplier.get());
    }

    return new ObjectColumnSelector()
    {
      @Nullable
      @Override
      public Object getObject()
      {
        if (nullValues.get(offset.getOffset())) {
          return null;
        }
        final ByteBuffer valueBuffer = compressedRawColumn.get(offset.getOffset());
        return STRATEGY.fromByteBuffer(valueBuffer, valueBuffer.remaining());
      }

      @Override
      public Class classOfObject()
      {
        return getClazz();
      }

      @Override
      public void inspectRuntimeShape(RuntimeShapeInspector inspector)
      {
        inspector.visit("column", CompressedNestedDataComplexColumn.this);
      }
    };
  }

  @Override
  public VectorObjectSelector makeVectorObjectSelector(ReadableVectorOffset offset)
  {
    if (fields.size() == 1 && rootFieldPath.equals(fields.get(0))) {
      return makeVectorObjectSelector(
          Collections.emptyList(),
          offset
      );
    }
    if (compressedRawColumn == null) {
      compressedRawColumn = closer.register(compressedRawColumnSupplier.get());
    }
    return new VectorObjectSelector()
    {
      final Object[] vector = new Object[offset.getMaxVectorSize()];

      private int id = ReadableVectorInspector.NULL_ID;

      @Override
      public Object[] getObjectVector()
      {
        if (id == offset.getId()) {
          return vector;
        }

        if (offset.isContiguous()) {
          final int startOffset = offset.getStartOffset();
          final int vectorSize = offset.getCurrentVectorSize();

          for (int i = 0; i < vectorSize; i++) {
            vector[i] = getForOffset(startOffset + i);
          }
        } else {
          final int[] offsets = offset.getOffsets();
          final int vectorSize = offset.getCurrentVectorSize();

          for (int i = 0; i < vectorSize; i++) {
            vector[i] = getForOffset(offsets[i]);

          }
        }

        id = offset.getId();
        return vector;
      }

      @Nullable
      private Object getForOffset(int offset)
      {
        if (nullValues.get(offset)) {
          // maybe someday can use bitmap batch operations for nulls?
          return null;
        }
        final ByteBuffer valueBuffer = compressedRawColumn.get(offset);
        return STRATEGY.fromByteBuffer(valueBuffer, valueBuffer.remaining());
      }

      @Override
      public int getCurrentVectorSize()
      {
        return offset.getCurrentVectorSize();
      }

      @Override
      public int getMaxVectorSize()
      {
        return offset.getMaxVectorSize();
      }
    };
  }

  @Override
  public VectorValueSelector makeVectorValueSelector(ReadableVectorOffset offset)
  {
    if (fields.size() == 1 && rootFieldPath.equals(fields.get(0))) {
      return makeVectorValueSelector(
          Collections.emptyList(),
          offset
      );
    }
    return super.makeVectorValueSelector(offset);
  }

  @Override
  public int getLength()
  {
    return -1;
  }

  @Override
  public void close()
  {
    CloseableUtils.closeAndWrapExceptions(closer);
  }

  @Override
  public DimensionSelector makeDimensionSelector(
      List<NestedPathPart> path,
      ReadableOffset readableOffset,
      ExtractionFn fn
  )
  {
    final String field = getField(path);
    Preconditions.checkNotNull(field, "Null field");
    final int fieldIndex = fields.indexOf(field);
    if (fieldIndex >= 0) {
      DictionaryEncodedColumn<?> col = (DictionaryEncodedColumn<?>) getColumnHolder(field, fieldIndex).getColumn();
      return col.makeDimensionSelector(readableOffset, fn);
    }
    if (!path.isEmpty() && path.get(path.size() - 1) instanceof NestedPathArrayElement) {
      final NestedPathPart lastPath = path.get(path.size() - 1);
      final String arrayField = getField(path.subList(0, path.size() - 1));
      final int arrayFieldIndex = fields.indexOf(arrayField);
      if (arrayFieldIndex >= 0) {
        final int elementNumber = ((NestedPathArrayElement) lastPath).getIndex();
        if (elementNumber < 0) {
          throw new IAE("Cannot make array element selector for path [%s], negative array index not supported for this selector", path);
        }
        DictionaryEncodedColumn<?> col = (DictionaryEncodedColumn<?>) getColumnHolder(arrayField, arrayFieldIndex).getColumn();
        ColumnValueSelector<?> arraySelector = col.makeColumnValueSelector(readableOffset);
        return new BaseSingleValueDimensionSelector()
        {
          @Nullable
          @Override
          protected String getValue()
          {
            Object o = arraySelector.getObject();
            if (o instanceof Object[]) {
              Object[] array = (Object[]) o;
              if (elementNumber < array.length) {
                Object element = array[elementNumber];
                if (element == null) {
                  return null;
                }
                return String.valueOf(element);
              }
            }
            return null;
          }

          @Override
          public void inspectRuntimeShape(RuntimeShapeInspector inspector)
          {
            arraySelector.inspectRuntimeShape(inspector);
          }
        };
      }
    }
    return DimensionSelector.constant(null);
  }

  @Override
  public ColumnValueSelector<?> makeColumnValueSelector(List<NestedPathPart> path, ReadableOffset readableOffset)
  {
    final String field = getField(path);
    Preconditions.checkNotNull(field, "Null field");

    final int fieldIndex = fields.indexOf(field);
    if (fieldIndex >= 0) {
      BaseColumn col = getColumnHolder(field, fieldIndex).getColumn();
      return col.makeColumnValueSelector(readableOffset);
    }
    if (!path.isEmpty() && path.get(path.size() - 1) instanceof NestedPathArrayElement) {
      final NestedPathPart lastPath = path.get(path.size() - 1);
      final String arrayField = getField(path.subList(0, path.size() - 1));
      final int arrayFieldIndex = fields.indexOf(arrayField);
      if (arrayFieldIndex >= 0) {
        final int elementNumber = ((NestedPathArrayElement) lastPath).getIndex();
        if (elementNumber < 0) {
          throw new IAE("Cannot make array element selector for path [%s], negative array index not supported for this selector", path);
        }
        DictionaryEncodedColumn<?> col = (DictionaryEncodedColumn<?>) getColumnHolder(arrayField, arrayFieldIndex).getColumn();
        ColumnValueSelector arraySelector = col.makeColumnValueSelector(readableOffset);
        return new ColumnValueSelector<Object>()
        {
          @Override
          public boolean isNull()
          {
            Object o = getObject();
            return !(o instanceof Number);
          }

          @Override
          public long getLong()
          {
            Object o = getObject();
            return o instanceof Number ? ((Number) o).longValue() : 0L;
          }

          @Override
          public float getFloat()
          {
            Object o = getObject();
            return o instanceof Number ? ((Number) o).floatValue() : 0f;
          }

          @Override
          public double getDouble()
          {
            Object o = getObject();
            return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
          }

          @Override
          public void inspectRuntimeShape(RuntimeShapeInspector inspector)
          {
            arraySelector.inspectRuntimeShape(inspector);
          }

          @Nullable
          @Override
          public Object getObject()
          {
            Object o = arraySelector.getObject();
            if (o instanceof Object[]) {
              Object[] array = (Object[]) o;
              if (elementNumber < array.length) {
                return array[elementNumber];
              }
            }
            return null;
          }

          @Override
          public Class<?> classOfObject()
          {
            return Object.class;
          }
        };
      }
    }
    return NilColumnValueSelector.instance();
  }

  @Override
  public SingleValueDimensionVectorSelector makeSingleValueDimensionVectorSelector(
      List<NestedPathPart> path,
      ReadableVectorOffset readableOffset
  )
  {
    final String field = getField(path);
    Preconditions.checkNotNull(field, "Null field");
    final int fieldIndex = fields.indexOf(field);
    if (fieldIndex >= 0) {
      DictionaryEncodedColumn<?> col = (DictionaryEncodedColumn<?>) getColumnHolder(field, fieldIndex).getColumn();
      return col.makeSingleValueDimensionVectorSelector(readableOffset);
    } else {
      return NilVectorSelector.create(readableOffset);
    }
  }

  @Override
  public VectorObjectSelector makeVectorObjectSelector(List<NestedPathPart> path, ReadableVectorOffset readableOffset)
  {
    final String field = getField(path);
    Preconditions.checkNotNull(field, "Null field");
    final int fieldIndex = fields.indexOf(field);
    if (fieldIndex >= 0) {
      BaseColumn col = getColumnHolder(field, fieldIndex).getColumn();
      return col.makeVectorObjectSelector(readableOffset);
    }
    if (!path.isEmpty() && path.get(path.size() - 1) instanceof NestedPathArrayElement) {
      final NestedPathPart lastPath = path.get(path.size() - 1);
      final String arrayField = getField(path.subList(0, path.size() - 1));
      final int arrayFieldIndex = fields.indexOf(arrayField);
      if (arrayFieldIndex >= 0) {
        final int elementNumber = ((NestedPathArrayElement) lastPath).getIndex();
        if (elementNumber < 0) {
          throw new IAE("Cannot make array element selector for path [%s], negative array index not supported for this selector", path);
        }
        DictionaryEncodedColumn<?> col = (DictionaryEncodedColumn<?>) getColumnHolder(arrayField, arrayFieldIndex).getColumn();
        VectorObjectSelector arraySelector = col.makeVectorObjectSelector(readableOffset);

        return new VectorObjectSelector()
        {
          private final Object[] elements = new Object[arraySelector.getMaxVectorSize()];
          private int id = ReadableVectorInspector.NULL_ID;

          @Override
          public Object[] getObjectVector()
          {
            if (readableOffset.getId() != id) {
              final Object[] delegate = arraySelector.getObjectVector();
              for (int i = 0; i < arraySelector.getCurrentVectorSize(); i++) {
                Object maybeArray = delegate[i];
                if (maybeArray instanceof Object[]) {
                  Object[] anArray = (Object[]) maybeArray;
                  if (elementNumber < anArray.length) {
                    elements[i] = anArray[elementNumber];
                  } else {
                    elements[i] = null;
                  }
                } else {
                  elements[i] = null;
                }
              }
              id = readableOffset.getId();
            }
            return elements;
          }

          @Override
          public int getMaxVectorSize()
          {
            return arraySelector.getMaxVectorSize();
          }

          @Override
          public int getCurrentVectorSize()
          {
            return arraySelector.getCurrentVectorSize();
          }
        };
      }
    }
    return NilVectorSelector.create(readableOffset);
  }

  @Override
  public VectorValueSelector makeVectorValueSelector(List<NestedPathPart> path, ReadableVectorOffset readableOffset)
  {
    final String field = getField(path);
    Preconditions.checkNotNull(field, "Null field");
    final int fieldIndex = fields.indexOf(field);
    if (fieldIndex >= 0) {
      BaseColumn col = getColumnHolder(field, fieldIndex).getColumn();
      return col.makeVectorValueSelector(readableOffset);
    }
    if (!path.isEmpty() && path.get(path.size() - 1) instanceof NestedPathArrayElement) {
      final NestedPathPart lastPath = path.get(path.size() - 1);
      final String arrayField = getField(path.subList(0, path.size() - 1));
      final int arrayFieldIndex = fields.indexOf(arrayField);
      if (arrayFieldIndex >= 0) {
        final int elementNumber = ((NestedPathArrayElement) lastPath).getIndex();
        if (elementNumber < 0) {
          throw new IAE("Cannot make array element selector for path [%s], negative array index not supported for this selector", path);
        }
        DictionaryEncodedColumn<?> col = (DictionaryEncodedColumn<?>) getColumnHolder(arrayField, arrayFieldIndex).getColumn();
        VectorObjectSelector arraySelector = col.makeVectorObjectSelector(readableOffset);

        return new VectorValueSelector()
        {
          private final long[] longs = new long[readableOffset.getMaxVectorSize()];
          private final double[] doubles = new double[readableOffset.getMaxVectorSize()];
          private final float[] floats = new float[readableOffset.getMaxVectorSize()];
          private final boolean[] nulls = new boolean[readableOffset.getMaxVectorSize()];
          private int id = ReadableVectorInspector.NULL_ID;

          private void computeNumbers()
          {
            if (readableOffset.getId() != id) {
              final Object[] maybeArrays = arraySelector.getObjectVector();
              for (int i = 0; i < arraySelector.getCurrentVectorSize(); i++) {
                Object maybeArray = maybeArrays[i];
                if (maybeArray instanceof Object[]) {
                  Object[] anArray = (Object[]) maybeArray;
                  if (elementNumber < anArray.length) {
                    if (anArray[elementNumber] instanceof Number) {
                      Number n = (Number) anArray[elementNumber];
                      longs[i] = n.longValue();
                      doubles[i] = n.doubleValue();
                      floats[i] = n.floatValue();
                      nulls[i] = false;
                    } else {
                      Double d = anArray[elementNumber] instanceof String
                                 ? Doubles.tryParse((String) anArray[elementNumber])
                                 : null;
                      if (d != null) {
                        longs[i] = d.longValue();
                        doubles[i] = d;
                        floats[i] = d.floatValue();
                        nulls[i] = false;
                      } else {
                        nullElement(i);
                      }
                    }
                  } else {
                    nullElement(i);
                  }
                } else {
                  // not an array?
                  nullElement(i);
                }
              }
              id = readableOffset.getId();
            }
          }

          private void nullElement(int i)
          {
            longs[i] = 0L;
            doubles[i] = 0L;
            floats[i] = 0L;
            nulls[i] = true;
          }

          @Override
          public long[] getLongVector()
          {
            if (readableOffset.getId() != id) {
              computeNumbers();
            }
            return longs;
          }

          @Override
          public float[] getFloatVector()
          {
            if (readableOffset.getId() != id) {
              computeNumbers();
            }
            return floats;
          }

          @Override
          public double[] getDoubleVector()
          {
            if (readableOffset.getId() != id) {
              computeNumbers();
            }
            return doubles;
          }

          @Nullable
          @Override
          public boolean[] getNullVector()
          {
            if (readableOffset.getId() != id) {
              computeNumbers();
            }
            return nulls;
          }

          @Override
          public int getMaxVectorSize()
          {
            return arraySelector.getMaxVectorSize();
          }

          @Override
          public int getCurrentVectorSize()
          {
            return arraySelector.getCurrentVectorSize();
          }
        };
      }
    }
    return NilVectorSelector.create(readableOffset);
  }


  @Nullable
  @Override
  public Set<ColumnType> getColumnTypes(List<NestedPathPart> path)
  {
    String field = getField(path);
    int index = fields.indexOf(field);
    if (index < 0) {
      return null;
    }
    return NestedFieldTypeInfo.convertToSet(fieldInfo.getTypes(index).getByteValue());
  }

  @Nullable
  @Override
  public ColumnHolder getColumnHolder(List<NestedPathPart> path)
  {
    final String field = getField(path);
    final int fieldIndex = fields.indexOf(field);
    return getColumnHolder(field, fieldIndex);
  }

  @Nullable
  @Override
  public ColumnIndexSupplier getColumnIndexSupplier(List<NestedPathPart> path)
  {
    final String field = getField(path);
    int fieldIndex = fields.indexOf(field);
    if (fieldIndex >= 0) {
      return getColumnHolder(field, fieldIndex).getIndexSupplier();
    }
    if (!path.isEmpty() && path.get(path.size() - 1) instanceof NestedPathArrayElement) {
      final String arrayField = getField(path.subList(0, path.size() - 1));
      final int arrayFieldIndex = fields.indexOf(arrayField);
      if (arrayFieldIndex >= 0) {
        return NoIndexesColumnIndexSupplier.getInstance();
      }
    }
    return null;
  }

  @Override
  public boolean isNumeric(List<NestedPathPart> path)
  {
    final String field = getField(path);
    final int fieldIndex = fields.indexOf(field);
    if (fieldIndex < 0) {
      return true;
    }
    return getColumnHolder(field, fieldIndex).getCapabilities().isNumeric();
  }

  private ColumnHolder getColumnHolder(String field, int fieldIndex)
  {
    return columns.computeIfAbsent(fieldIndex, (f) -> readNestedFieldColumn(field, fieldIndex));
  }

  @Nullable
  private ColumnHolder readNestedFieldColumn(String field, int fieldIndex)
  {
    try {
      if (fieldIndex < 0) {
        return null;
      }
      final NestedFieldTypeInfo.TypeSet types = fieldInfo.getTypes(fieldIndex);
      final String fieldFileName = getFieldFileName(metadata.getFileNameBase(), field, fieldIndex);
      final ByteBuffer dataBuffer = fileMapper.mapFile(fieldFileName);
      if (dataBuffer == null) {
        throw new ISE(
            "Can't find field [%s] with name [%s] in [%s] file.",
            field,
            fieldFileName,
            metadata.getFileNameBase()
        );
      }

      ColumnBuilder columnBuilder = new ColumnBuilder().setFileMapper(fileMapper);
      // heh, maybe this should be its own class, or DictionaryEncodedColumnPartSerde could be cooler
      DictionaryEncodedColumnPartSerde.VERSION version = DictionaryEncodedColumnPartSerde.VERSION.fromByte(
          dataBuffer.get()
      );
      // we should check this someday soon, but for now just read it to push the buffer position ahead
      int flags = dataBuffer.getInt();
      Preconditions.checkState(
          flags == DictionaryEncodedColumnPartSerde.NO_FLAGS,
          StringUtils.format(
              "Unrecognized bits set in space reserved for future flags for field column [%s]",
              field
          )
      );

      final Supplier<FixedIndexed<Integer>> localDictionarySupplier = FixedIndexed.read(
          dataBuffer,
          NestedDataColumnSerializer.INT_TYPE_STRATEGY,
          metadata.getByteOrder(),
          Integer.BYTES
      );
      ByteBuffer bb = dataBuffer.asReadOnlyBuffer().order(metadata.getByteOrder());
      int longsLength = bb.getInt();
      int doublesLength = bb.getInt();
      dataBuffer.position(dataBuffer.position() + Integer.BYTES + Integer.BYTES);
      int pos = dataBuffer.position();
      final Supplier<ColumnarLongs> longs = longsLength > 0 ? CompressedColumnarLongsSupplier.fromByteBuffer(
          dataBuffer,
          metadata.getByteOrder()
      ) : () -> null;
      dataBuffer.position(pos + longsLength);
      pos = dataBuffer.position();
      final Supplier<ColumnarDoubles> doubles = doublesLength > 0 ? CompressedColumnarDoublesSuppliers.fromByteBuffer(
          dataBuffer,
          metadata.getByteOrder()
      ) : () -> null;
      dataBuffer.position(pos + doublesLength);
      final WritableSupplier<ColumnarInts> ints;
      if (version == DictionaryEncodedColumnPartSerde.VERSION.COMPRESSED) {
        ints = CompressedVSizeColumnarIntsSupplier.fromByteBuffer(dataBuffer, metadata.getByteOrder());
      } else {
        ints = VSizeColumnarInts.readFromByteBuffer(dataBuffer);
      }
      ColumnType theType = types.getSingleType();
      columnBuilder.setType(theType == null ? ColumnType.STRING : theType);

      GenericIndexed<ImmutableBitmap> rBitmaps = GenericIndexed.read(
          dataBuffer,
          metadata.getBitmapSerdeFactory().getObjectStrategy(),
          columnBuilder.getFileMapper()
      );
      final Supplier<FixedIndexed<Integer>> arrayElementDictionarySupplier;
      final GenericIndexed<ImmutableBitmap> arrayElementBitmaps;
      if (dataBuffer.hasRemaining()) {
        arrayElementDictionarySupplier = FixedIndexed.read(
            dataBuffer,
            NestedDataColumnSerializer.INT_TYPE_STRATEGY,
            metadata.getByteOrder(),
            Integer.BYTES
        );
        arrayElementBitmaps = GenericIndexed.read(
            dataBuffer,
            metadata.getBitmapSerdeFactory().getObjectStrategy(),
            columnBuilder.getFileMapper()
        );
      } else {
        arrayElementDictionarySupplier = null;
        arrayElementBitmaps = null;
      }
      final boolean hasNull = localDictionarySupplier.get().get(0) == 0;
      Supplier<DictionaryEncodedColumn<?>> columnSupplier = () -> {
        FixedIndexed<Integer> localDict = localDictionarySupplier.get();
        return closer.register(new NestedFieldDictionaryEncodedColumn(
            types,
            longs.get(),
            doubles.get(),
            ints.get(),
            stringDictionarySupplier.get(),
            longDictionarySupplier.get(),
            doubleDictionarySupplier.get(),
            arrayDictionarySupplier != null ? arrayDictionarySupplier.get() : null,
            localDict,
            hasNull
            ? rBitmaps.get(0)
            : metadata.getBitmapSerdeFactory().getBitmapFactory().makeEmptyImmutableBitmap()
        ));
      };
      columnBuilder.setHasMultipleValues(false)
                   .setHasNulls(hasNull)
                   .setDictionaryEncodedColumnSupplier(columnSupplier);
      columnBuilder.setIndexSupplier(
          new NestedFieldColumnIndexSupplier(
              types,
              metadata.getBitmapSerdeFactory().getBitmapFactory(),
              rBitmaps,
              localDictionarySupplier,
              stringDictionarySupplier,
              longDictionarySupplier,
              doubleDictionarySupplier,
              arrayElementDictionarySupplier,
              arrayElementBitmaps
          ),
          true,
          false
      );
      return columnBuilder.build();
    }
    catch (IOException ex) {
      throw new RE(ex, "Failed to read data for [%s]", field);
    }
  }
}

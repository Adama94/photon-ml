/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.data

import scala.collection.JavaConverters._

import org.apache.avro.Schema
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericData
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.sql.types.DataTypes._
import org.apache.spark.sql.types.{DataType, MapType}
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.avro.AvroFieldNames
import com.linkedin.photon.ml.util._

class AvroDataReaderTest {
  import AvroDataReaderTest._

  @DataProvider
  def fieldSchemaProvider() = {
    Array(
      Array(avroSchema.getFields.get(0).schema, IntegerType),
      Array(avroSchema.getFields.get(1).schema, StringType),
      Array(avroSchema.getFields.get(2).schema, BooleanType),
      Array(avroSchema.getFields.get(3).schema, DoubleType),
      Array(avroSchema.getFields.get(4).schema, FloatType),
      Array(avroSchema.getFields.get(5).schema, LongType),
      Array(avroSchema.getFields.get(6).schema, MapType(StringType, StringType, false)),
      Array(avroSchema.getFields.get(7).schema, IntegerType),
      Array(avroSchema.getFields.get(8).schema, LongType),
      Array(avroSchema.getFields.get(9).schema, DoubleType))
  }

  @Test(dataProvider = "fieldSchemaProvider")
  def testAvroTypeToSql(avroSchema: Schema, sqlDataType: DataType): Unit = {
    val field = AvroDataReader.avroTypeToSql("testField", avroSchema).get
    assertEquals(field.dataType, sqlDataType)
  }

  @Test
  def testReadColumnValuesFromRecord(): Unit = {
    val fields = Seq(
      AvroDataReader.avroTypeToSql(IntField, avroSchema.getFields.get(0).schema),
      AvroDataReader.avroTypeToSql(StringField, avroSchema.getFields.get(1).schema),
      AvroDataReader.avroTypeToSql(BooleanField, avroSchema.getFields.get(2).schema),
      AvroDataReader.avroTypeToSql(DoubleField, avroSchema.getFields.get(3).schema),
      AvroDataReader.avroTypeToSql(FloatField, avroSchema.getFields.get(4).schema),
      AvroDataReader.avroTypeToSql(LongField, avroSchema.getFields.get(5).schema),
      AvroDataReader.avroTypeToSql(MapField, avroSchema.getFields.get(6).schema),
      AvroDataReader.avroTypeToSql(UnionField, avroSchema.getFields.get(7).schema),
      AvroDataReader.avroTypeToSql(UnionFieldIntLong, avroSchema.getFields.get(8).schema),
      AvroDataReader.avroTypeToSql(UnionFieldFloatDouble, avroSchema.getFields.get(9).schema)).flatten

    val vals = AvroDataReader.readColumnValuesFromRecord(record, fields)
    assertEquals(vals,
      Seq(IntValue, StringValue, BooleanValue, DoubleValue, FloatValue, LongValue, MapValue, UnionIntValue,
        UnionIntLongValue, UnionFloatDoubleValue))
  }

  @Test
  def testReadFeaturesFromRecord(): Unit = {
    val vals = Array(
      (FeatureKey1, FeatureVal1),
      (FeatureKey2, FeatureVal2))

    assertEquals(AvroDataReader.readFeaturesFromRecord(record, Set(FeaturesField)), vals)
  }

  @Test
  def testReadFeatureVectorFromRecord(): Unit = {
    val vector = new SparseVector(2, Array(0, 1), Array(FeatureVal1, FeatureVal2))
    val indexMap = DefaultIndexMap(Seq(FeatureKey1, FeatureKey2))

    assertEquals(AvroDataReader.readFeatureVectorFromRecord(record, Set(FeaturesField), indexMap), vector)
  }

  @Test(expectedExceptions = Array(classOf[IllegalArgumentException]))
  def testReadFeatureVectorFromRecordDuplicateFeatures(): Unit = {
    val record = new GenericData.Record(avroSchema)
    record.put(FeaturesField, List(feature1, feature2, feature1).asJava)

    val indexMap = DefaultIndexMap(Seq(FeatureKey1, FeatureKey2))

    AvroDataReader.readFeatureVectorFromRecord(record, Set(FeaturesField), indexMap)
  }
}

object AvroDataReaderTest {
  val IntField = "intField"
  val IntValue = 7
  val StringField = "stringField"
  val StringValue = "ipass"
  val BooleanField = "booleanField"
  val BooleanValue = true
  val DoubleField = "doubleField"
  val DoubleValue = 13.0D
  val FloatField = "floatField"
  val FloatValue = 23.5
  val LongField = "longField"
  val LongValue = 31L
  val MapField = "mapField"
  val MapValue = Map("a" -> 5)
  val UnionField = "unionField"
  val UnionIntValue = 55
  val UnionFieldIntLong = "unionFieldIntLong"
  val UnionIntLongValue = 17
  val UnionFieldFloatDouble = "unionFieldFloatDouble"
  val UnionFloatDoubleValue = 43.5
  val FeaturesField = "features"
  val FeatureName1 = "f1"
  val FeatureVal1 = 1.0
  val FeatureKey1 = Utils.getFeatureKey(FeatureName1, "")
  val FeatureName2 = "f2"
  val FeatureVal2 = 0.0
  val FeatureKey2 = Utils.getFeatureKey(FeatureName2, "")

  val nameAndTermSchema = SchemaBuilder
    .record("testNameAndTermSchema")
    .namespace("com.linkedin.photon.ml.avro.data")
    .fields()
    .name(AvroFieldNames.NAME).`type`().stringType().noDefault()
    .name(AvroFieldNames.TERM).`type`().stringType().noDefault()
    .name(AvroFieldNames.VALUE).`type`().doubleType().noDefault()
    .endRecord()

  val avroSchema = SchemaBuilder
    .record("testAvroSchema")
    .namespace("com.linkedin.photon.ml.avro.data")
    .fields()
    .name(IntField).`type`().intType().noDefault()
    .name(StringField).`type`().stringType().noDefault()
    .name(BooleanField).`type`().booleanType().noDefault()
    .name(DoubleField).`type`().doubleType().noDefault()
    .name(FloatField).`type`().floatType().noDefault()
    .name(LongField).`type`().longType().noDefault()
    .name(MapField).`type`().map().values().stringType().noDefault()
    .name(UnionField).`type`().unionOf().intType().and().nullType().endUnion().noDefault()
    .name(UnionFieldIntLong).`type`().unionOf().intType().and().longType().endUnion().noDefault()
    .name(UnionFieldFloatDouble).`type`().unionOf().floatType().and().doubleType().and().nullType().endUnion().noDefault()
    .name(FeaturesField).`type`().array().items(nameAndTermSchema).noDefault()
    .endRecord()

  val record = new GenericData.Record(avroSchema)
  record.put(IntField, IntValue)
  record.put(StringField, StringValue)
  record.put(BooleanField, BooleanValue)
  record.put(DoubleField, DoubleValue)
  record.put(FloatField, FloatValue)
  record.put(LongField, LongValue)
  record.put(MapField, MapValue.asJava)
  record.put(UnionField, UnionIntValue)
  record.put(UnionFieldIntLong, UnionIntLongValue)
  record.put(UnionFieldFloatDouble, UnionFloatDoubleValue)

  val feature1 = new GenericData.Record(nameAndTermSchema)
  feature1.put(AvroFieldNames.NAME, FeatureName1)
  feature1.put(AvroFieldNames.TERM, "")
  feature1.put(AvroFieldNames.VALUE, FeatureVal1)

  val feature2 = new GenericData.Record(nameAndTermSchema)
  feature2.put(AvroFieldNames.NAME, FeatureName2)
  feature2.put(AvroFieldNames.TERM, "")
  feature2.put(AvroFieldNames.VALUE, FeatureVal2)

  record.put(FeaturesField, List(feature1, feature2).asJava)
}

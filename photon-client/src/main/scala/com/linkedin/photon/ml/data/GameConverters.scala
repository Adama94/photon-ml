/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
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

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row}

import com.linkedin.photon.ml.InputColumnsNames
import com.linkedin.photon.ml.util.VectorUtils

/**
 * A collection of utility functions for converting to and from GAME datasets.
 */
object GameConverters {

  // Column name for the synthesized unique id column
  private val UNIQUE_ID_COLUMN_NAME = "___photon:uniqueId___"

  /**
   * Converts a DataFrame into an [[RDD]] of type [[GameDatum]].
   *
   * @note We "decode" the Map of column names into an Array[String] which we broadcast for performance.
   *       inputColumnsNames is populated with all the default column names, and then possibly patched with
   *       user-specified, custom column names.
   *
   * @param data The source DataFrame
   * @param featureShards A set of feature shard ids
   * @param idTypeSet A set of id types expected to be found in the row
   * @param isResponseRequired Whether the response variable is expected to be found in the row. For example, if GAME
   *   data set to be parsed is used for model training, then the response variable is expected to be found in row. If
   *   the GAME data set is used for scoring, then we don't expect to find response.
   * @param inputColumnsNames User-supplied input column names to read the input data
   * @return The [[RDD]] of type [[GameDatum]]
   */
  protected[ml] def getGameDataSetFromDataFrame(
      data: DataFrame,
      featureShards: Set[String],
      idTypeSet: Set[String],
      isResponseRequired: Boolean,
      inputColumnsNames: InputColumnsNames = InputColumnsNames()): RDD[(Long, GameDatum)] = {

    val recordsWithUniqueId = data.withColumn(UNIQUE_ID_COLUMN_NAME, monotonicallyIncreasingId)
    val inputColumnsNamesBroadcast = data.sqlContext.sparkContext.broadcast(inputColumnsNames)

    recordsWithUniqueId.rdd.map { row: Row =>
      (row.getAs[Long](UNIQUE_ID_COLUMN_NAME),
        getGameDatumFromRow(row, featureShards, idTypeSet, isResponseRequired, inputColumnsNamesBroadcast))
    }
  }

  /**
   * Given a DataFrame row, build the id type to value map: (id type -> id value).
   *
   * @param row The source DataFrame row
   * @param idTypeSet The id types to look for from the row, either at the top layer or within "metadataMap"
   * @return The id type to value map in the form of (id type -> id value)
   */
  protected[data] def getIdTypeToValueMapFromRow(
      row: Row,
      idTypeSet: Set[String],
      columns: InputColumnsNames = InputColumnsNames()): Map[String, String] = {

    val metaMap = if (row.schema.fieldNames.contains(columns(InputColumnsNames.META_DATA_MAP))) {
      Some(row.getAs[Map[String, String]](columns(InputColumnsNames.META_DATA_MAP)))
    } else {
      None
    }

    idTypeSet.map { idType =>
      val idFromRow = if (row.schema.fieldNames.contains(idType)) {
        Some(row.getAs[Any](idType).toString)
      } else {
        None
      }

      val id = idFromRow.orElse {
        metaMap.flatMap(_.get(idType))
      }.getOrElse(throw new IllegalArgumentException(s"Cannot find id in either record" +
        s"field: $idType or in metadataMap with key: #$idType"))

      // random effect group name -> random effect group id value
      // random effect types are assumed to be strings
      (idType, id)
    }.toMap
  }

  /**
   * Build a [[GameDatum]] from a DataFrame row.
   *
   * @param row The source DataFrame row, must contain spark.ml SparseVector instances
   * @param columnsBroadcast The names of the columns to look for in the input rows, in order
   * @param featureShards A set of feature shard ids
   * @param idTypeSet A set of id types expected to be found in the row
   * @param isResponseRequired Whether the response variable is expected to be found in the row. For example, if GAME
   *   data set to be parsed is used for model training, then the response variable is expected to be found in row. If
   *   the GAME data set is used for scoring, then we don't expect to find response.
   * @return The [[GameDatum]]
   */
  protected[data] def getGameDatumFromRow(
      row: Row,
      featureShards: Set[String],
      idTypeSet: Set[String],
      isResponseRequired: Boolean,
      columnsBroadcast: Broadcast[InputColumnsNames]): GameDatum = {

    val columns = columnsBroadcast.value

    val featureShardContainer = featureShards.map { shardId =>
      val features = row.getAs[SparseVector](shardId)
      (shardId, VectorUtils.mllibToBreeze(features))
    }.toMap

    val response = if (isResponseRequired) {
      row.getAs[Number](columns(InputColumnsNames.RESPONSE)).doubleValue
    } else {
      if (row.schema.fieldNames.contains(columns(InputColumnsNames.RESPONSE))) {
        row.getAs[Number](columns(InputColumnsNames.RESPONSE)).doubleValue
      } else {
        Double.NaN
      }
    }

    val offset = if (row.schema.fieldNames.contains(columns(InputColumnsNames.OFFSET))) {
      Option(row.getAs[Number](columns(InputColumnsNames.OFFSET))).map(_.doubleValue)
    } else {
      None
    }

    val weight = if (row.schema.fieldNames.contains(columns(InputColumnsNames.WEIGHT))) {
      Option(row.getAs[Number](columns(InputColumnsNames.WEIGHT))).map(_.doubleValue)
    } else {
      None
    }

    val idTypeToValueMap =
      //TODO find a better way to handle the field "uid", which is used in ScoringResult
      if (row.schema.fieldNames.contains(columns(InputColumnsNames.UID))
          && row.getAs[Any](columns(InputColumnsNames.UID)) != null) {
        getIdTypeToValueMapFromRow(row, idTypeSet, columns) +
            (InputColumnsNames.UID.toString -> row.getAs[Any](columns(InputColumnsNames.UID)).toString)
      } else {
        getIdTypeToValueMapFromRow(row, idTypeSet, columns)
      }

    new GameDatum(
      response,
      offset,
      weight,
      featureShardContainer,
      idTypeToValueMap)
  }
}

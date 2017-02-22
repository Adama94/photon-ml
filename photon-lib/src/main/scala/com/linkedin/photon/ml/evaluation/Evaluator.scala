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
package com.linkedin.photon.ml.evaluation

import org.apache.spark.rdd.RDD

/**
 * Interface for evaluation implementations at the [[RDD]] level.
 */
protected[ml] trait Evaluator {

  /**
   * A [[RDD]] of (id, (labels, offsets, weights)) pairs.
   */
  protected[ml] val labelAndOffsetAndWeights: RDD[(Long, (Double, Double, Double))]

  /**
   * The default score used to compute the metric.
   */
  protected[ml] var defaultScore: Double = 0.0

  /**
   * The type of the evaluator.
   */
  protected[ml] val evaluatorType: EvaluatorType

  /**
   * Evaluate the scores of the model.
   *
   * @param scores The scores to evaluate
   * @return Evaluation metric value
   */
  protected[ml] def evaluate(scores: RDD[(Long, Double)]): Double = {
    // Create a local copy of the defaultScore, so that the underlying object won't get shipped to the executor nodes
    val defaultScore = this.defaultScore
    val scoreAndLabelAndWeights = scores
      .rightOuterJoin(labelAndOffsetAndWeights)
      .mapValues { case (scoreOption, (label, offset, weight)) =>
        (scoreOption.getOrElse(defaultScore) + offset, label, weight)
      }
    evaluateWithScoresAndLabelsAndWeights(scoreAndLabelAndWeights)
  }

  /**
   * Evaluate scores with labels and weights.
   *
   * @param scoresAndLabelsAndWeights A [[RDD]] of pairs (uniqueId, (score, label, weight)).
   * @return Evaluation metric value
   */
  protected[ml] def evaluateWithScoresAndLabelsAndWeights(
    scoresAndLabelsAndWeights: RDD[(Long, (Double, Double, Double))]): Double

  /**
   * Determine the best between two scores returned by the evaluator. In some cases, the better score is higher
   * (e.g. AUC) and in others, the better score is lower (e.g. RMSE).
   *
   * @param score1 The first score to compare
   * @param score2 The second score to compare
   * @return True if the first score is better than the second
   */
  def betterThan(score1: Double, score2: Double): Boolean

  /**
   *
   * @return
   */
  protected[ml] def getEvaluatorName: String = evaluatorType.name
}

object Evaluator {
  type EvaluationResults = Seq[(Evaluator, Double)]
}

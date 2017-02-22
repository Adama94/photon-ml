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
package com.linkedin.photon.ml.optimization

import java.util.Random

import breeze.linalg.{DenseVector, SparseVector, norm}
import org.apache.spark.broadcast.Broadcast
import org.mockito.Mockito._
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function.TwiceDiffFunction
import com.linkedin.photon.ml.normalization.{NoNormalization, NormalizationContext}
import com.linkedin.photon.ml.test.SparkTestUtils
import com.linkedin.photon.ml.util.{FunctionValuesConverged, GradientConverged, Logging}

/**
 * Verify that core optimizers do reasonable things on small test problems.
 */
class OptimizerTest extends SparkTestUtils with Logging {
  import OptimizerTest._

  @DataProvider(parallel = true)
  def optimzersUsingInitialValue(): Array[Array[Object]] = {
    Array(
      Array(new LBFGS(
        tolerance = OptimizerTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerTest.MAX_ITERATIONS,
        normalizationContext = OptimizerTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerTest.ENABLE_TRACKING)),
      Array(new TRON(
        tolerance = OptimizerTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerTest.MAX_ITERATIONS,
        normalizationContext = OptimizerTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerTest.ENABLE_TRACKING)))
  }

  @DataProvider(parallel = true)
  def optimzersNotUsingInitialValue(): Array[Array[Object]] = {
    Array(
      Array(new LBFGS(
        tolerance = OptimizerTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerTest.MAX_ITERATIONS,
        normalizationContext = OptimizerTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerTest.ENABLE_TRACKING,
        isReusingPreviousInitialState = false)),
      Array(new TRON(
        tolerance = OptimizerTest.CONVERGENCE_TOLERANCE,
        maxNumIterations = OptimizerTest.MAX_ITERATIONS,
        normalizationContext = OptimizerTest.NORMALIZATION_MOCK,
        isTrackingState = OptimizerTest.ENABLE_TRACKING,
        isReusingPreviousInitialState = false)))
  }

  // TODO: Currently the test objective function used by this test ignores weights, so testing points with varying
  //       weights is pointless

  @Test(dataProvider = "optimzersUsingInitialValue")
  def checkEasyTestFunctionSparkNoInitialValue(optimizer: Optimizer[TwiceDiffFunction]): Unit =
    sparkTest("checkEasyTestFunctionSparkNoInitialValue") {
      val features = new SparseVector[Double](Array(), Array(), OptimizerTest.PROBLEM_DIMENSION)

      // Test unweighted sample
      val pt = new LabeledPoint(label = 1, features, offset = 0, weight = 1)
      val data = sc.parallelize(Seq(pt))
      optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1))(data)
      OptimizerTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)

      // Test weighted sample
      val pt2 = new LabeledPoint(label = 1, features, offset = 0, weight = 1.5)
      val data2 = sc.parallelize(Seq(pt2))
      optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1))(data2)
      OptimizerTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)
    }

  @Test(dataProvider = "optimzersNotUsingInitialValue")
  def checkEasyTestFunctionSparkInitialValue(optimizer: Optimizer[TwiceDiffFunction]): Unit =
    sparkTest("checkEasyTestFunctionSparkInitialValue") {
      val features = new SparseVector[Double](Array(), Array(), OptimizerTest.PROBLEM_DIMENSION)
      val r = new Random(OptimizerTest.RANDOM_SEED)

      // Test unweighted sample
      val pt = new LabeledPoint(label = 1, features, offset = 0, weight = 1)
      val data = sc.parallelize(Seq(pt))
      for (iter <- 0 to OptimizerTest.RANDOM_SAMPLES) {
        val initParam = DenseVector.fill[Double](OptimizerTest.PROBLEM_DIMENSION)(r.nextDouble())
        optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1), initParam)(data)

        assertTrue(optimizer.getStateTracker.isDefined)
        assertTrue(optimizer.isDone)
        OptimizerTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)
      }

    // Test weighted sample
    val pt2 = new LabeledPoint(label = 1, features, offset = 0, weight = 0.5)
    val data2 = sc.parallelize(Seq(pt2))
    for (iter <- 0 to OptimizerTest.RANDOM_SAMPLES) {
      val initParam = DenseVector.fill[Double](OptimizerTest.PROBLEM_DIMENSION)(r.nextDouble())
      optimizer.optimize(new IntegTestObjective(sc, treeAggregateDepth = 1), initParam)(data2)

      OptimizerTest.easyOptimizationStatesChecks(optimizer.getStateTracker.get)
    }
  }
}

object OptimizerTest extends Logging {

  private val PROBLEM_DIMENSION: Int = 10
  private val MAX_ITERATIONS: Int = 1000 * PROBLEM_DIMENSION
  private val CONVERGENCE_TOLERANCE: Double = 1e-12
  private val OBJECTIVE_TOLERANCE: Double = 1e-6
  private val GRADIENT_TOLERANCE: Double = 1e-6
  private val PARAMETER_TOLERANCE: Double = 1e-4
  private val RANDOM_SEED: Long = 314159265359L
  private val RANDOM_SAMPLES: Int = 100
  private val ENABLE_TRACKING: Boolean = true
  private val NORMALIZATION = NoNormalization()
  private val NORMALIZATION_MOCK = mock(classOf[Broadcast[NormalizationContext]])

  doReturn(NORMALIZATION).when(NORMALIZATION_MOCK).value

  /**
   *
   * @param history
   */
  def checkConvergence(history: OptimizationStatesTracker) {
    var lastValue: Double = Double.MaxValue

    history.getTrackedStates.foreach { state =>
      assertTrue(lastValue >= state.value, "Objective should be monotonically decreasing (current=[" + state.value +
        "], previous=[" + lastValue + "])")
      lastValue = state.value
    }
  }

  /**
   * Common checks:
   * <ul>
   * <li>Did we get the expected parameters?</li>
   * <li>Did we get the expected objective?</li>
   * <li>Did we see monotonic convergence?</li>
   * </ul>
   *
   * @param optimizerStatesTracker
   */
  private def easyOptimizationStatesChecks(optimizerStatesTracker: OptimizationStatesTracker): Unit = {

    logger.info(s"Optimizer state: $optimizerStatesTracker")

    // The optimizer should be converged
    assertTrue(optimizerStatesTracker.converged)
    assertFalse(optimizerStatesTracker.getTrackedTimeHistory.isEmpty)
    assertFalse(optimizerStatesTracker.getTrackedStates.isEmpty)
    assertEquals(optimizerStatesTracker.getTrackedStates.length, optimizerStatesTracker.getTrackedTimeHistory.length)

    val optimizedObj = optimizerStatesTracker.getTrackedStates.last.value
    val optimizedGradientNorm = norm(optimizerStatesTracker.getTrackedStates.last.gradient, 2)
    val optimizedParam = optimizerStatesTracker.getTrackedStates.last.coefficients

    if (optimizerStatesTracker.convergenceReason.forall(_ == FunctionValuesConverged)) {
      // Expected answer in terms of optimal objective
      assertEquals(
        optimizedObj,
        0,
        OBJECTIVE_TOLERANCE,
        s"Optimized objective should be very close to zero (eps=[$OBJECTIVE_TOLERANCE])")
    } else if (optimizerStatesTracker.convergenceReason.forall(_ == GradientConverged)) {
      // Expected answer in terms of optimal gradient
      assertEquals(
        optimizedGradientNorm,
        0,
        GRADIENT_TOLERANCE,
        s"Optimized gradient norm should be very close to zero (eps=[$GRADIENT_TOLERANCE])")
    }

    // Expected answer in terms of optimal parameters
    optimizedParam.foreachPair { (idx, x) =>
      assertEquals(
        x,
        IntegTestObjective.CENTROID,
        PARAMETER_TOLERANCE,
        s"Optimized parameter for index [$idx] should be close to TestObjective.CENTROID (eps=[$PARAMETER_TOLERANCE]")
    }

    // Monotonic convergence
    checkConvergence(optimizerStatesTracker)
  }
}

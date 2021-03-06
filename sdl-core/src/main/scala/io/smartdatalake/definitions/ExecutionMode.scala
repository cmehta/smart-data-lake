/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.definitions

import java.sql.Timestamp

import io.smartdatalake.config.ConfigurationException
import io.smartdatalake.config.SdlConfigObject.{ActionObjectId, DataObjectId}
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.{SmartDataLakeLogger, SparkExpressionUtil}
import io.smartdatalake.workflow.DAGHelper.NodeId
import io.smartdatalake.workflow.ExceptionSeverity.ExceptionSeverity
import io.smartdatalake.workflow.ExecutionPhase.ExecutionPhase
import io.smartdatalake.workflow._
import io.smartdatalake.workflow.action.ActionHelper.{getOptionalDataFrame, searchCommonInits}
import io.smartdatalake.workflow.action.NoDataToProcessWarning
import io.smartdatalake.workflow.dataobject.{CanCreateDataFrame, CanHandlePartitions, DataObject}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, max}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types._

/**
 * Execution mode defines how data is selected when running a data pipeline.
 * You need to select one of the subclasses by defining type, i.e.
 *
 * {{{
 * executionMode = {
 *   type = SparkIncrementalMode
 *   compareCol = "id"
 * }
 * }}}
 */
sealed trait ExecutionMode extends SmartDataLakeLogger {
  def prepare(actionId: ActionObjectId)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    // validate condition
    applyCondition.foreach( c => SparkExpressionUtil.evaluateBoolean(actionId, Some("applyCondition"), c, DefaultExecutionModeExpressionData.from(context), onlySyntaxCheck = true))
  }
  def apply(actionId: ActionObjectId, mainInput: DataObject, mainOutput: DataObject, subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Option[(Seq[PartitionValues], Option[String])] = None
  def mainInputOutputNeeded: Boolean = false
  def applyCondition: Option[String] = None
  final def evaluateApplyCondition(actionId: ActionObjectId, subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Option[Boolean] = {
    val data = DefaultExecutionModeExpressionData.from(context).copy(givenPartitionValues = subFeed.partitionValues.map(_.getMapString), isStartNode = subFeed.isDAGStart)
    applyCondition.map( c => SparkExpressionUtil.evaluateBoolean(actionId, Some("applyCondition"), c, data))
  }
}

private[smartdatalake] trait ExecutionModeWithMainInputOutput {
  def alternativeOutputId: Option[DataObjectId] = None
  def alternativeOutput(implicit context: ActionPipelineContext): Option[DataObject] = {
    alternativeOutputId.map(context.instanceRegistry.get[DataObject](_))
  }
}

/**
 * Partition difference execution mode lists partitions on mainInput & mainOutput DataObject and starts loading all missing partitions.
 * Partition columns to be used for comparision need to be a common 'init' of input and output partition columns.
 * This mode needs mainInput/Output DataObjects which CanHandlePartitions to list partitions.
 * Partition values are passed to following actions, if for partition columns which they have in common.
 *
 * @param partitionColNb            optional number of partition columns to use as a common 'init'.
 * @param alternativeOutputId       optional alternative outputId of DataObject later in the DAG. This replaces the mainOutputId.
 *                                  It can be used to ensure processing all partitions over multiple actions in case of errors.
 * @param nbOfPartitionValuesPerRun optional restriction of the number of partition values per run.
 * @param applyCondition            Condition to decide if execution mode should be applied or not. Define a spark sql expression working with attributes of [[DefaultExecutionModeExpressionData]] returning a boolean.
 *                                  Default is to apply the execution mode if given partition values (partition values from command line or passed from previous action) are not empty.
 * @param failCondition             Condition to fail application of execution mode if true. Define a spark sql expression working with attributes of [[PartitionDiffModeExpressionData]] returning a boolean.
 *                                  Default is that the application of the PartitionDiffMode does not fail the action. If there is no data to process, the following actions are skipped.
 */
case class PartitionDiffMode(partitionColNb: Option[Int] = None, override val alternativeOutputId: Option[DataObjectId] = None, nbOfPartitionValuesPerRun: Option[Int] = None, override val applyCondition: Option[String] = None, failCondition: Option[String] = None) extends ExecutionMode with ExecutionModeWithMainInputOutput {
  override def mainInputOutputNeeded: Boolean = alternativeOutputId.isEmpty
  override def prepare(actionId: ActionObjectId)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.prepare(actionId)
    // validate condition
    failCondition.foreach( c => SparkExpressionUtil.evaluateBoolean(actionId, Some("failCondition"), c, PartitionDiffModeExpressionData.from(context), onlySyntaxCheck = true))
    // check alternativeOutput exists
    alternativeOutput
  }
  override def apply(actionId: ActionObjectId, mainInput: DataObject, mainOutput: DataObject, subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Option[(Seq[PartitionValues], Option[String])] = {
    val doApply = evaluateApplyCondition(actionId, subFeed)
      .getOrElse(subFeed.partitionValues.isEmpty) // default is to apply PartitionDiffMode if no partition values are given
    if (doApply) {
      val input = mainInput
      val output = alternativeOutput.getOrElse(mainOutput)
      (input, output) match {
        case (partitionInput: CanHandlePartitions, partitionOutput: CanHandlePartitions) =>
          if (partitionInput.partitions.nonEmpty) {
            if (partitionOutput.partitions.nonEmpty) {
              // prepare common partition columns
              val commonInits = searchCommonInits(partitionInput.partitions, partitionOutput.partitions)
              require(commonInits.nonEmpty, s"$actionId has set executionMode = 'PartitionDiffMode' but no common init was found in partition columns for $input and $output")
              val commonPartitions = if (partitionColNb.isDefined) {
                commonInits.find(_.size == partitionColNb.get).getOrElse(throw ConfigurationException(s"$actionId has set executionMode = 'PartitionDiffMode' but no common init with ${partitionColNb.get} was found in partition columns of $input and $output from $commonInits!"))
              } else {
                commonInits.maxBy(_.size)
              }
              // calculate missing partition values
              val inputPartitionValues = partitionInput.listPartitions.map(_.filterKeys(commonPartitions))
              val outputPartitionValues = partitionOutput.listPartitions.map(_.filterKeys(commonPartitions))
              val partitionValuesToBeProcessed = inputPartitionValues.toSet.diff(outputPartitionValues.toSet).toSeq
              // sort and limit number of partitions processed
              val ordering = PartitionValues.getOrdering(commonPartitions)
              val selectedPartitionValues = nbOfPartitionValuesPerRun match {
                case Some(n) => partitionValuesToBeProcessed.sorted(ordering).take(n)
                case None => partitionValuesToBeProcessed.sorted(ordering)
              }
              // evaluate fail condition
              val data = PartitionDiffModeExpressionData.from(context).copy(inputPartitionValues = inputPartitionValues.map(_.getMapString), outputPartitionValues = outputPartitionValues.map(_.getMapString), selectedPartitionValues = selectedPartitionValues.map(_.getMapString))
              val doFail = failCondition.exists(c => SparkExpressionUtil.evaluateBoolean(actionId, Some("failCondition"), c, data)) // default is to not fail
              if (doFail) throw ExecutionModeFailedException(actionId.id, context.phase, s"($actionId) Execution mode failed because of failCondition '${failCondition.get}' for $data")
              // stop processing if no new data
              if (partitionValuesToBeProcessed.isEmpty) throw NoDataToProcessWarning(actionId.id, s"($actionId) No partitions to process found for ${input.id}")
              //return
              logger.info(s"($actionId) PartitionDiffMode selected partition values ${selectedPartitionValues.mkString(", ")} to process")
              Some((selectedPartitionValues, None))
            } else throw ConfigurationException(s"$actionId has set executionMode = PartitionDiffMode but $output has no partition columns defined!")
          } else throw ConfigurationException(s"$actionId has set executionMode = PartitionDiffMode but $input has no partition columns defined!")
        case (_: CanHandlePartitions, _) => throw ConfigurationException(s"$actionId has set executionMode = PartitionDiffMode but $output does not support partitions!")
        case (_, _) => throw ConfigurationException(s"$actionId has set executionMode = PartitionDiffMode but $input does not support partitions!")
      }
    } else None
  }
}
case class PartitionDiffModeExpressionData(feed: String, application: String, runId: Int, attemptId: Int, referenceTimestamp: Option[Timestamp]
                                           , runStartTime: Timestamp, attemptStartTime: Timestamp
                                           , inputPartitionValues: Seq[Map[String,String]], outputPartitionValues: Seq[Map[String,String]], selectedPartitionValues: Seq[Map[String,String]])
private[smartdatalake] object PartitionDiffModeExpressionData {
  def from(context: ActionPipelineContext): PartitionDiffModeExpressionData = {
    PartitionDiffModeExpressionData(context.feed, context.application, context.runId, context.attemptId, context.referenceTimestamp.map(Timestamp.valueOf)
      , Timestamp.valueOf(context.runStartTime), Timestamp.valueOf(context.attemptStartTime), Seq(), Seq(), Seq())
  }
}

/**
 * Spark streaming execution mode uses Spark Structured Streaming to incrementally execute data loads (trigger=Trigger.Once) and keep track of processed data.
 * This mode needs a DataObject implementing CanCreateStreamingDataFrame and works only with SparkSubFeeds.
 * @param checkpointLocation location for checkpoints of streaming query to keep state
 * @param inputOptions additional option to apply when reading streaming source. This overwrites options set by the DataObjects.
 * @param outputOptions additional option to apply when writing to streaming sink. This overwrites options set by the DataObjects.
 */
case class SparkStreamingOnceMode(checkpointLocation: String, inputOptions: Map[String,String] = Map(), outputOptions: Map[String,String] = Map(), outputMode: OutputMode = OutputMode.Append) extends ExecutionMode

/**
 * Compares max entry in "compare column" between mainOutput and mainInput and incrementally loads the delta.
 * This mode works only with SparkSubFeeds. The filter is not propagated to following actions.
 * @param compareCol a comparable column name existing in mainInput and mainOutput used to identify the delta. Column content should be bigger for newer records.
 * @param alternativeOutputId optional alternative outputId of DataObject later in the DAG. This replaces the mainOutputId.
 *                            It can be used to ensure processing all partitions over multiple actions in case of errors.
 */
case class SparkIncrementalMode(compareCol: String, override val alternativeOutputId: Option[DataObjectId] = None) extends ExecutionMode with ExecutionModeWithMainInputOutput {
  override def mainInputOutputNeeded: Boolean = alternativeOutputId.isEmpty
  override def prepare(actionId: ActionObjectId)(implicit session: SparkSession, context: ActionPipelineContext): Unit = {
    super.prepare(actionId)
    // check alternativeOutput exists
    alternativeOutput
  }
  override def apply(actionId: ActionObjectId, mainInput: DataObject, mainOutput: DataObject, subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Option[(Seq[PartitionValues], Option[String])] = {
    val doApply = evaluateApplyCondition(actionId, subFeed)
      .getOrElse(true) // default is to apply SparkIncrementalMode
    if (doApply) {
      import session.implicits._
      val input = mainInput
      val output = alternativeOutput.getOrElse(mainOutput)
      (input, output) match {
        case (sparkInput: CanCreateDataFrame, sparkOutput: CanCreateDataFrame) =>
          // if data object is new, it might not be able to create a DataFrame
          val dfInputOpt = getOptionalDataFrame(sparkInput)
          val dfOutputOpt = getOptionalDataFrame(sparkOutput)
          (dfInputOpt, dfOutputOpt) match {
            // if both DataFrames exist, compare and create filter
            case (Some(dfInput), Some(dfOutput)) =>
              val inputColType = dfInput.schema(compareCol).dataType
              require(SparkIncrementalMode.allowedDataTypes.contains(inputColType), s"($actionId) Type of compare column ${compareCol} must be one of ${SparkIncrementalMode.allowedDataTypes.mkString(", ")} in ${sparkInput.id}")
              val outputColType = dfOutput.schema(compareCol).dataType
              require(SparkIncrementalMode.allowedDataTypes.contains(outputColType), s"($actionId) Type of compare column ${compareCol} must be one of ${SparkIncrementalMode.allowedDataTypes.mkString(", ")} in ${sparkOutput.id}")
              require(inputColType == outputColType, s"($actionId) Type of compare column ${compareCol} is different between ${sparkInput.id} ($inputColType) and ${sparkOutput.id} ($outputColType)")
              // get latest values
              val inputLatestValue = dfInput.agg(max(col(compareCol)).cast(StringType)).as[String].head
              val outputLatestValue = dfOutput.agg(max(col(compareCol)).cast(StringType)).as[String].head
              // stop processing if no new data
              if (outputLatestValue == inputLatestValue) throw NoDataToProcessWarning(actionId.id, s"($actionId) No increment to process found for ${output.id} column ${compareCol} (lastestValue=$outputLatestValue)")
              logger.info(s"($actionId) SparkIncrementalMode selected increment for writing to ${output.id}: column ${compareCol} from $outputLatestValue to $inputLatestValue to process")
              // prepare filter
              val selectedData = s"${compareCol} > cast('$outputLatestValue' as ${inputColType.sql})"
              Some((Seq(), Some(selectedData)))
            // otherwise don't filter
            case _ =>
              logger.info(s"($actionId) SparkIncrementalMode selected all records for writing to ${output.id}, because input or output DataObject is still empty.")
              Some((Seq(), None))
          }
        case _ => throw ConfigurationException(s"$actionId has set executionMode = $SparkIncrementalMode but $input or $output does not support creating Spark DataFrames!")
      }
    } else None
  }
}
object SparkIncrementalMode {
  private[smartdatalake] val allowedDataTypes = Seq(StringType, LongType, IntegerType, ShortType, FloatType, DoubleType, TimestampType)
}

/**
 * An execution mode which just validates that partition values are given.
 * Note: For start nodes of the DAG partition values can be defined by command line, for subsequent nodes partition values are passed on from previous nodes.
 */
case class FailIfNoPartitionValuesMode() extends ExecutionMode {
  override def apply(actionId: ActionObjectId, mainInput: DataObject, mainOutput: DataObject, subFeed: SubFeed)(implicit session: SparkSession, context: ActionPipelineContext): Option[(Seq[PartitionValues], Option[String])] = {
    // check if partition values present
    if (subFeed.partitionValues.isEmpty) throw new IllegalStateException(s"($actionId) Partition values are empty for mainInput ${subFeed.dataObjectId.id}")
    // return
    None
  }
}


/**
 * Attributes definition for spark expressions used as ExecutionMode conditions.
 * @param givenPartitionValues Partition values specified with command line (start action) or passed from previous action
 * @param isStartNode True if the current action is a start node of the DAG.
 */
case class DefaultExecutionModeExpressionData( feed: String, application: String, runId: Int, attemptId: Int, referenceTimestamp: Option[Timestamp]
                                             , runStartTime: Timestamp, attemptStartTime: Timestamp
                                             , givenPartitionValues: Seq[Map[String,String]], isStartNode: Boolean)
private[smartdatalake] object DefaultExecutionModeExpressionData {
  def from(context: ActionPipelineContext): DefaultExecutionModeExpressionData = {
    DefaultExecutionModeExpressionData(context.feed, context.application, context.runId, context.attemptId, context.referenceTimestamp.map(Timestamp.valueOf)
      , Timestamp.valueOf(context.runStartTime), Timestamp.valueOf(context.attemptStartTime), Seq(), false)
  }
}

private[smartdatalake] case class ExecutionModeFailedException(id: NodeId, phase: ExecutionPhase, msg: String) extends DAGException(msg) {
  // don't fail in init phase, but skip action to continue with exec phase
  override def severity: ExceptionSeverity = if (phase==ExecutionPhase.Init) ExceptionSeverity.SKIPPED else ExceptionSeverity.FAILED
  override def getDAGRootExceptions: Seq[DAGException] = Seq(this)
}


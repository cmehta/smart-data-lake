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

package io.smartdatalake.workflow

import java.time.LocalDateTime
import java.time.{Duration => JavaDuration}

import io.smartdatalake.app.SmartDataLakeBuilderConfig
import io.smartdatalake.util.hdfs.HdfsUtil
import io.smartdatalake.util.misc.SmartDataLakeLogger
import io.smartdatalake.workflow.action.RuntimeEventState.RuntimeEventState
import io.smartdatalake.workflow.action.{RuntimeEventState, RuntimeInfo}
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path, PathFilter}

import scala.io.Codec

/**
 * ActionDAGRunState contains all configuration and state of an ActionDAGRun needed to start a recovery run in case of failure.
 */
private[smartdatalake] case class ActionDAGRunState(appConfig: SmartDataLakeBuilderConfig, runId: Int, attemptId: Int, actionsState: Map[String, RuntimeInfo], isFinal: Boolean) {
  def toJson: String = ActionDAGRunState.toJson(this)
  def isFailed: Boolean = actionsState.exists(_._2.state==RuntimeEventState.FAILED)
  def isSucceeded: Boolean = isFinal && !isFailed
}
private[smartdatalake]object ActionDAGRunState {
  // json4s is used because kxbmap configs supports converting case classes to config only from verion 5.0 which isn't yet stable
  // json4s is included with spark-core
  // Note that key-type of Maps must be String in json4s (e.g. actionsState...)
  import org.json4s._
  import org.json4s.jackson.JsonMethods._
  import org.json4s.jackson.Serialization
  implicit val formats =  DefaultFormats.withHints(ShortTypeHints(List(classOf[SparkSubFeed],classOf[FileSubFeed]))).withTypeHintFieldName("type") +
    RuntimeEventStateSerializer + DurationSerializer + LocalDateTimeSerializer

  def toJson(actionDAGRunState: ActionDAGRunState): String = pretty(parse(Serialization.write(actionDAGRunState)))

  def fromJson(stateJson: String): ActionDAGRunState = parse(stateJson).extract[ActionDAGRunState]

  // custom serialization for RuntimeEventState which is shorter than the default
  case object RuntimeEventStateSerializer extends CustomSerializer[RuntimeEventState](format => (
    { case JString(state) => RuntimeEventState.withName(state) },
    { case state: RuntimeEventState => JString(state.toString) }
  ))
  // custom serialization for Duration
  case object DurationSerializer extends CustomSerializer[JavaDuration](format => (
    { case JString(dur) => JavaDuration.parse(dur) },
    { case dur: JavaDuration => JString(dur.toString) }
  ))
  // custom serialization for LocalDateTime
  case object LocalDateTimeSerializer extends CustomSerializer[LocalDateTime](format => (
    { case JString(tstmp) => LocalDateTime.parse(tstmp) },
    { case tstmp: LocalDateTime => JString(tstmp.toString) }
  ))
}

private[smartdatalake] case class ActionDAGRunStateStore(statePath: String, appName: String) extends SmartDataLakeLogger {

  private val hadoopStatePath = HdfsUtil.addHadoopDefaultSchemaAuthority(new Path(statePath))
  private val currentStatePath = new Path(hadoopStatePath, "current")
  private val succeededStatePath = new Path(hadoopStatePath, "succeeded")
  implicit private val filesystem: FileSystem = HdfsUtil.getHadoopFs(hadoopStatePath)
  if (!filesystem.exists(hadoopStatePath)) filesystem.mkdirs(hadoopStatePath)
  filesystem.setWriteChecksum(false) // disable writing CRC files

  /**
   * Save state to file
   */
  def saveState(state: ActionDAGRunState): Unit = synchronized {
    val path = if (state.isSucceeded) succeededStatePath else currentStatePath
    // write state file
    val json = state.toJson
    val fileName = s"$appName${ActionDAGRunStateStore.fileNamePartSeparator}${state.runId}${ActionDAGRunStateStore.fileNamePartSeparator}${state.attemptId}.json"
    val file = new Path(path, fileName)
    val os = filesystem.create(file, true) // overwrite if exists
    os.write(json.getBytes("UTF-8"))
    os.close()
    logger.info(s"updated state into ${file.toUri}")
    // if succeeded:
    // - delete temporary state file from current directory
    // - move previous failed attempt files from current to succeeded directory
    if (state.isSucceeded) {
      filesystem.delete(new Path(currentStatePath, fileName), /*recursive*/ false)
      getFiles(Some(currentStatePath))
        .filter( stateFile => stateFile.runId==state.runId && stateFile.attemptId<state.attemptId)
        .foreach { stateFile =>
          logger.info(s"renamed ${stateFile.path}")
          FileUtil.copy(filesystem, stateFile.path, filesystem, succeededStatePath, /*deleteSource*/ true, filesystem.getConf)
        }
    }
  }

  /**
   * Get latest state
   * @param runId optional runId to search for latest state
   */
  def getLatestState(runId: Option[Int] = None): StateFile = {
    val latestStateFile = getFiles()
      .filter(x => runId.isEmpty || runId.contains(x.runId))
      .sortBy(_.getSortAttrs).lastOption
    require(latestStateFile.nonEmpty, s"No state file for application $appName and runId ${runId.getOrElse("latest")} found.")
    latestStateFile.get
  }

  /**
   * Get latest runId
   */
  def getLatestRunId: Option[Int] = {
    val latestStateFile = getFiles()
      .sortBy(_.getSortAttrs).lastOption
    latestStateFile.map(_.runId)
  }

  /**
   * Search state directory for state files of this app
   */
  private def getFiles(path: Option[Path] = None): Seq[StateFile] = {
    val filenameMatcher = s"([^_]+)\\${ActionDAGRunStateStore.fileNamePartSeparator}([0-9]+)\\${ActionDAGRunStateStore.fileNamePartSeparator}([0-9]+)\\.json".r
    val pathFilter = new PathFilter {
      override def accept(path: Path): Boolean = path.getName.startsWith(appName + ActionDAGRunStateStore.fileNamePartSeparator)
    }
    val searchPath = path.getOrElse( new Path(hadoopStatePath, "*"))
    filesystem.globStatus(new Path(searchPath, "*.json"), pathFilter )
      .filter( x => x.isFile)
      .flatMap( x => x.getPath.getName match {
        case filenameMatcher(appName, runId, attemptId) =>
          Some(StateFile(x.getPath, appName, runId.toInt, attemptId.toInt))
        case _ => None
      })
      .filter(_.appName == this.appName)
  }

  /**
   * recover previous run state
   */
  def recoverRunState(stateFile: Path): ActionDAGRunState = {
    require(filesystem.isFile(stateFile), s"Cannot recover previous run state. ${stateFile.toUri} doesn't exists or is not a file.")
    val is = filesystem.open(stateFile)
    val json = scala.io.Source.fromInputStream(is)(Codec.UTF8).mkString
    ActionDAGRunState.fromJson(json)
  }

  case class StateFile(path: Path, appName: String, runId: Int, attemptId: Int) {
    def getSortAttrs: (Int, Int) = (runId, attemptId)
  }
}

private[smartdatalake] object ActionDAGRunStateStore {
  val fileNamePartSeparator = "."
}
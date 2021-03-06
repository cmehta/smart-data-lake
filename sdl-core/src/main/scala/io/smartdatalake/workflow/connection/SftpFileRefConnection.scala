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
package io.smartdatalake.workflow.connection

import com.typesafe.config.Config
import io.smartdatalake.config.SdlConfigObject.ConnectionId
import io.smartdatalake.config.{FromConfigFactory, InstanceRegistry}
import io.smartdatalake.definitions.{AuthMode, BasicAuthMode, PublicKeyAuthMode}
import io.smartdatalake.util.filetransfer.SshUtil
import io.smartdatalake.util.misc.TryWithResourcePool
import net.schmizz.sshj.sftp.SFTPClient
import org.apache.commons.pool2.impl.{DefaultPooledObject, GenericObjectPool}
import org.apache.commons.pool2.{BasePooledObjectFactory, PooledObject}

/**
 * SFTP Connection information
 *
 * @param id unique id of this connection
 * @param host sftp host
 * @param port port of sftp service, default is 22
 * @param authMode authentication information: for now BasicAuthMode and PublicKeyAuthMode are supported.
 * @param ignoreHostKeyVerification do not validate host key if true, default is false
 * @param maxParallelConnections number of parallel sftp connections created by an instance of this connection
 * @param metadata
 */
case class SftpFileRefConnection( override val id: ConnectionId,
                                  host: String,
                                  port: Int = 22,
                                  authMode: AuthMode,
                                  ignoreHostKeyVerification: Boolean = false,
                                  maxParallelConnections: Int = 1,
                                  override val metadata: Option[ConnectionMetadata] = None
                                 ) extends Connection {

  // Allow only supported authentication modes
  private val supportedAuths = Seq(classOf[BasicAuthMode], classOf[PublicKeyAuthMode])
  require(supportedAuths.contains(authMode.getClass), s"${authMode.getClass.getSimpleName} not supported by ${this.getClass.getSimpleName}. Supported auth modes are ${supportedAuths.map(_.getSimpleName).mkString(", ")}.")

  def execWithSFtpClient[A]( func: SFTPClient => A ): A = {
    TryWithResourcePool.exec(pool){
      sftp => func(sftp)
    }
  }

  def test(): Unit = {
    TryWithResourcePool.exec(pool){ sftp => Unit } // no operation
  }

  // setup connection pool
  val pool = new GenericObjectPool[SFTPClient](new SftpClientPoolFactory)
  pool.setMaxTotal(maxParallelConnections)
  pool.setMaxIdle(1) // keep max one idle sftp connection
  pool.setMinEvictableIdleTimeMillis(1000) // timeout to close sftp connection if not in use
  private class SftpClientPoolFactory extends BasePooledObjectFactory[SFTPClient] {
    override def create(): SFTPClient = {
      authMode match {
        case m: BasicAuthMode => SshUtil.connectWithUserPw(host, port, m.user, m.password, ignoreHostKeyVerification).newSFTPClient()
        case m: PublicKeyAuthMode => SshUtil.connectWithPublicKey(host, port, m.user, ignoreHostKeyVerification).newSFTPClient()
        case _ => throw new IllegalArgumentException(s"${authMode.getClass.getSimpleName} not supported.")
      }
    }
    override def wrap(sftp: SFTPClient): PooledObject[SFTPClient] = new DefaultPooledObject(sftp)
    override def destroyObject(p: PooledObject[SFTPClient]): Unit =
      p.getObject.close()
  }

  /**
   * @inheritdoc
   */
  override def factory: FromConfigFactory[Connection] = SftpFileRefConnection
}

object SftpFileRefConnection extends FromConfigFactory[Connection] {

  /**
   * @inheritdoc
   */
  override def fromConfig(config: Config, instanceRegistry: InstanceRegistry): SftpFileRefConnection = {
    import configs.syntax.ConfigOps
    import io.smartdatalake.config._

    implicit val instanceRegistryImpl: InstanceRegistry = instanceRegistry
    config.extract[SftpFileRefConnection].value
  }
}





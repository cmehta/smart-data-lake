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
import io.smartdatalake.util.misc.AclDef

/**
 * Connection information for hive tables
 *
 * @param id unique id of this connection
 * @param db hive db
 * @param pathPrefix schema, authority and base path for tables directory on hadoop
 * @param acl permissions for files created with this connection
 * @param metadata
 */
case class HiveTableConnection( override val id: ConnectionId,
                                db: String,
                                pathPrefix: String,
                                acl: Option[AclDef] = None,
                                override val metadata: Option[ConnectionMetadata] = None
                               ) extends Connection {

  /**
   * @inheritdoc
   */
  override def factory: FromConfigFactory[Connection] = HiveTableConnection
}

object HiveTableConnection extends FromConfigFactory[Connection] {

  /**
   * @inheritdoc
   */
  override def fromConfig(config: Config, instanceRegistry: InstanceRegistry): HiveTableConnection = {
    import configs.syntax.ConfigOps
    config.extract[HiveTableConnection].value
  }
}

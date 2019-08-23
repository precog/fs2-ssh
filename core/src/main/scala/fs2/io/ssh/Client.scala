/*
 * Copyright 2014–2019 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2
package io.ssh

import cats.effect.{Blocker, ContextShift, Resource, Sync}
import cats.implicits._

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session

import scala.{Array, Int, None, Some}

import java.lang.{String, SuppressWarnings}
import java.net.InetSocketAddress

object Client {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def exec[F[_]: Sync: ContextShift](
      host: InetSocketAddress,
      username: String,
      auth: Auth,
      command: String,
      blocker: Blocker,
      chunkSize: Int = 4096)
      : Resource[F, Process[F]] =
    connect[F](host, username, auth, blocker) flatMap { session =>
      val r = Resource.make(blocker.blockOn(Sync[F].delay(session.exec(command)))) { cmd =>
        blocker.blockOn(Sync[F].delay(cmd.close()))
      }

      r.map(new Process[F](_, blocker, chunkSize))
    }

  // TODO error handling
  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private[this] def connect[F[_]: ContextShift](
      host: InetSocketAddress,
      username: String,
      auth: Auth,
      blocker: Blocker)(
      implicit F: Sync[F])
      : Resource[F, Session] =
    for {
      sshclientM <- Resource liftF {
        for {
          client <- F.delay(new SSHClient())
          _ <- blocker.blockOn(F.delay(client.loadKnownHosts()))
          connectM = blocker.blockOn(F.delay(client.connect(host.getAddress, host.getPort)))
        } yield Resource.make(connectM)(_ => blocker.blockOn(F.delay(client.disconnect()))).as(client)
      }

      sshclient <- sshclientM

      _ <- Resource liftF {
        blocker blockOn {
          Sync[F] delay {
            auth match {
              case Auth.Password(text) =>
                sshclient.authPassword(username, text)

              case Auth.Key(privateKey, Some(password)) =>
                val kp = sshclient.loadKeys(privateKey.toString, password)
                sshclient.authPublickey(username, kp)

              case Auth.Key(privateKey, None) =>
                val kp = sshclient.loadKeys(privateKey.toString)
                sshclient.authPublickey(username, kp)
            }
          }
        }
      }

      session <- Resource.make(blocker.blockOn(Sync[F].delay(sshclient.startSession()))) { session =>
        blocker.blockOn(Sync[F].delay(session.close()))
      }
    } yield session
}

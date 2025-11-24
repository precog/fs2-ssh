/*
 * Copyright 2020 Precog Data
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

import cats.effect.{Resource, Async}
import cats.implicits._
import cats.mtl.Raise
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.SshException
import org.apache.sshd.common.channel.StreamingChannel
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.session.SessionHeartbeatController.HeartbeatType
import org.apache.sshd.common.util.net.SshdSocketAddress
import scala.{Array, Int, None, Product, Serializable, Some, Unit}
import scala.util.{Left, Right}
import java.lang.{String, SuppressWarnings}
import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.TimeUnit

final class Client[F[_]] private (client: SshClient)(implicit F: Async[F]) {
  import CompatConverters.All._

  import Client.Error
  import MinaFuture._

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def exec(cc: ConnectionConfig, command: String, chunkSize: Int = 4096)(
      implicit FR: Raise[F, Error]
  ): Resource[F, Process[F]] = {

    for {
      session <- this.session(cc)

      channel <- Resource.make(for {
        channel <- F.delay(session.createExecChannel(command))
        _ <- F.delay(channel.setStreaming(StreamingChannel.Streaming.Async))
        opened <- fromFuture(F.delay(channel.open()))
        // TODO handle failure opening
      } yield channel)(channel =>
        fromFuture(F.delay(channel.close(false))).void
      )
    } yield new Process[F](channel, chunkSize)
  }

  def portForward(
      cc: ConnectionConfig,
      local: InetSocketAddress,
      remote: InetSocketAddress
  )(implicit FR: Raise[F, Error]): Resource[F, Unit] = {
    this
      .session(cc)
      .flatMap { clientSession =>
        val l = SshdSocketAddress.toSshdSocketAddress(local)
        val r = SshdSocketAddress.toSshdSocketAddress(remote)
        Resource.make(F.delay(clientSession.startLocalPortForwarding(l, r)))(
          address => F.delay(clientSession.stopLocalPortForwarding(address))
        )
      }
      .void
  }

  @SuppressWarnings(
    Array("org.wartremover.warts.Null", "org.wartremover.warts.ToString")
  )
  def session(
      cc: ConnectionConfig
  )(implicit FR: Raise[F, Error]): Resource[F, ClientSession] = {

    for {
      session <-
        Resource.make(
          fromFuture(F.delay(client.connect(cc.username, cc.host)))
        )(session => fromFuture(F.delay(session.close(false))).void)

      _ <- cc.auth match {
        case Auth.Password(text) =>
          Resource.eval(F.delay(session.addPasswordIdentity(text)))

        case Auth.KeyFile(path0, maybePass) =>
          Resource eval {
            for {
              path <- F.blocking(path0.toAbsolutePath())
              provider <- F.delay(new FileKeyPairProvider(path))

              _ <- maybePass match {
                case Some(password) =>
                  F delay {
                    provider setPasswordFinder { (session, key, index) =>
                      if (key.getName() === path.toString)
                        password
                      else
                        null
                    }
                  }

                case None =>
                  F.delay(
                    provider.setPasswordFinder(FilePasswordProvider.EMPTY)
                  )
              }

              pairs <- F.blocking(provider.loadKeys(session))
              _ <- pairs.asScala.toList traverse_ { kp =>
                F.delay(session.addPublicKeyIdentity(kp))
              }
            } yield ()
          }

        case Auth.KeyBytes(bytes, maybePass) =>
          val provider = ByteArrayKeyPairProvider(bytes, maybePass)

          Resource eval {
            for {
              pairs <- F.delay(provider.loadKeys(session))
              _ <- pairs.asScala.toList traverse_ { kp =>
                F.delay(session.addPublicKeyIdentity(kp))
              }
            } yield ()
          }
      }

      success <- Resource.eval(fromFuture(F.delay(session.auth()))).attempt

      _ <- Resource eval {
        success match {
          case Left(ex: SshException) =>
            FR.raise(Error.SshErr(ex))

          case Left(e) =>
            F.raiseError(e)

          case Right(a) =>
            F.pure(a)
        }
      }

      // TODO handle auth failure (minor problems...)
    } yield session
  }
}

object Client {
  val HeartbeatInterval = 20L

  def apply[F[_]: Async]: Resource[F, Client[F]] = {
    val makeF = Async[F] delay {
      val client = SshClient.setUpDefaultClient()
      client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY)

      // send periodic SSH_MSG_IGNOREs to prevent the connection from dying
      // without activity
      client.setSessionHeartbeat(
        HeartbeatType.IGNORE,
        TimeUnit.SECONDS,
        HeartbeatInterval
      )

      client.start()
      client
    }

    Resource.make(makeF)(c => Async[F].delay(c.stop())).map(new Client[F](_))
  }

  // convenience function that really should live elsewhere
  def resolve[F[_]: Async](hostname: String, port: Int): F[InetSocketAddress] =
    Async[F].blocking(
      new InetSocketAddress(InetAddress.getByName(hostname), port)
    )

  sealed trait Error extends Product with Serializable

  object Error {
    case class SshErr(ex: SshException) extends Error
  }
}

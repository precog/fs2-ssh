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

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.keyprovider.FileKeyPairProvider

import scala.{Array, Int, None, Some}
import scala.collection.JavaConverters._

import java.lang.{String, SuppressWarnings}
import java.net.{InetAddress, InetSocketAddress}

final class Client[F[_]: Concurrent: ContextShift] private (client: SshClient) {
  import MinaFuture._

  private val F = Concurrent[F]

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.DefaultArguments",
      "org.wartremover.warts.Null",
      "org.wartremover.warts.ToString"))
  def exec(
      cc: ConnectionConfig,
      command: String,
      blocker: Blocker,
      chunkSize: Int = 4096)
      : Resource[F, Process[F]] = {

    for {
      session <-
        Resource.make(
          fromFuture(F.delay(client.connect(cc.username, cc.host))))(
          session => fromFuture(F.delay(session.close(false))).void)

      _ <- cc.auth match {
        case Auth.Password(text) =>
          Resource.liftF(F.delay(session.addPasswordIdentity(text)))

        case Auth.Key(path0, maybePass) =>
          Resource liftF {
            for {
              path <- blocker.blockOn(F.delay(path0.toAbsolutePath()))
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
                  F.delay(provider.setPasswordFinder(FilePasswordProvider.EMPTY))
              }

              pairs <- blocker.blockOn(F.delay(provider.loadKeys(session)))
              _ <- pairs.asScala.toList traverse_ { kp =>
                F.delay(session.addPublicKeyIdentity(kp))
              }
            } yield ()
          }
      }

      success <- Resource.liftF(fromFuture(F.delay(session.auth())))

      // TODO handle auth failure (minor problems...)

      channel <- Resource.make(
        for {
          channel <- F.delay(session.createExecChannel(command))
          _ <- F.delay(channel.setStreaming(ClientChannel.Streaming.Async))
          opened <- fromFuture(F.delay(channel.open()))
          // TODO handle failure opening
        } yield channel)(
        channel => fromFuture(F.delay(channel.close(false))).void)
    } yield new Process[F](channel, chunkSize)
  }
}

object Client {

  def apply[F[_]: Concurrent: ContextShift]: Resource[F, Client[F]] = {
    val makeF = Sync[F] delay {
      val client = SshClient.setUpDefaultClient()
      client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY)

      client.start()
      client
    }

    Resource.make(makeF)(c => Sync[F].delay(c.stop())).map(new Client[F](_))
  }

  // convenience function that really should live elsewhere
  def resolve[F[_]: Sync: ContextShift](
      hostname: String,
      port: Int,
      blocker: Blocker)
      : F[InetSocketAddress] =
    blocker.blockOn(Sync[F].delay(new InetSocketAddress(InetAddress.getByName(hostname), port)))
}

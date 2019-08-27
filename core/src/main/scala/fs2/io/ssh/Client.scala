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
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.io.{IoInputStream, IoOutputStream}
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer

import scala.{Array, Byte, Int, None, Some, Unit}
import scala.collection.JavaConverters._

import java.lang.{String, SuppressWarnings}
import java.net.{InetAddress, InetSocketAddress}

final class Client[F[_]: Concurrent: ContextShift] private (client: SshClient) {
  import MinaFuture.fromFuture

  private val F = Concurrent[F]

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
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

        case Auth.Key(path, maybePass) =>
          Resource liftF {
            for {
              provider <- F.delay(new FileKeyPairProvider(path))

              _ <- maybePass match {
                case Some(password) =>
                  F.delay(provider.setPasswordFinder(FilePasswordProvider.of(password)))

                case None =>
                  F.unit
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
        F.delay(session.createExecChannel(command)))(
        channel => fromFuture(F.delay(channel.close(false))).void)

      stdout = Stream.force(F.delay(ioisToStream(channel.getAsyncOut(), chunkSize)))
      stderr = Stream.force(F.delay(ioisToStream(channel.getAsyncErr(), chunkSize)))
      stdin = ioosToSink(F.delay(channel.getAsyncIn()))
    } yield new Process[F](channel, stdout, stderr, stdin)
  }

  // TODO I'm pretty sure this stream is ephemeral and we might miss things
  private[this] def ioisToStream(iois: IoInputStream, chunkSize: Int): Stream[F, Byte] = {
    val readF = fromFuture(F.delay(iois.read(new ByteArrayBuffer(chunkSize))))
    Stream.eval(readF).repeat.takeWhile(!_.isEmpty).flatMap(Stream.chunk(_))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  private[this] def ioosToSink(ioisF: F[IoOutputStream]): Pipe[F, Byte, Unit] = { in =>
    Stream.eval(ioisF) flatMap { iois =>
      val written = in.chunks evalMap { chunk =>
        val bytes = chunk.toBytes
        val buffer = new ByteArrayBuffer(bytes.values, bytes.offset, bytes.length)
        buffer.wpos(bytes.length)
        fromFuture(F.delay(iois.writePacket(buffer)))
      }

      written.takeWhile(_ == true).void
    }
  }
}

object Client {

  def apply[F[_]: Concurrent: ContextShift]: Resource[F, Client[F]] = {
    val makeF = Sync[F] delay {
      val client = new SshClient   // TODO is this good?
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

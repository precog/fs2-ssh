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
package io
package ssh

import cats.effect.{Concurrent, ContextShift, Sync}
import cats.implicits._

import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.common.io.{IoInputStream, IoOutputStream}
import org.apache.sshd.common.util.buffer.ByteArrayBuffer

import scala.{Array, Byte, Int, Unit}

import java.io.IOException
import java.lang.{SuppressWarnings, Throwable}

final class Process[F[_]: Concurrent: ContextShift] private[ssh] (
    channel: ChannelExec,
    chunkSize: Int) {

  import MinaFuture.fromFuture

  private val F = Concurrent[F]

  val stdout: Stream[F, Byte] =
    Stream.force(F.delay(ioisToStream(channel.getAsyncOut(), chunkSize)))

  val stderr: Stream[F, Byte] =
    Stream.force(F.delay(ioisToStream(channel.getAsyncErr(), chunkSize)))

  // TODO configurable EOF semantics (currently defaults to send on complete)
  val stdin: Pipe[F, Byte, Unit] =
    ioosToSink(F.delay(channel.getAsyncIn()))

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  val join: F[Int] = {
    val statusF = Sync[F] delay {
      val status = channel.getExitStatus()
      if (status != null)
        status.intValue
      else
        0
    }

    MinaFuture.awaitClose[F](channel) >> statusF
  }

  // TODO I'm pretty sure this stream is ephemeral and we might miss things
  private[this] def ioisToStream(iois: IoInputStream, chunkSize: Int): Stream[F, Byte] = {
    val readF = fromFuture(F.delay(iois.read(new ByteArrayBuffer(chunkSize))))

    Stream.eval(readF)
      .repeat
      .handleErrorWith {
        case t: IOException =>
          Stream.eval(F.delay(iois.isClosed() || iois.isClosing())) flatMap { closing =>
            if (closing)
              Stream.empty
            else
              Stream.raiseError[F](t)
          }

        case t: Throwable =>
          Stream.raiseError[F](t)
      }
      .flatMap(Stream.chunk(_))
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  private[this] def ioosToSink(ioosF: F[IoOutputStream]): Pipe[F, Byte, Unit] = { in =>
    Stream.eval(ioosF) flatMap { ioos =>
      val written = in.chunks evalMap { chunk =>
        val bytes = chunk.toBytes
        val buffer = new ByteArrayBuffer(bytes.values, bytes.offset, bytes.length)
        buffer.wpos(bytes.length)
        fromFuture(F.delay(ioos.writePacket(buffer)))
      }

      written.takeWhile(_ == true).void
    }
  }
}

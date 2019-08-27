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
import cats.effect.syntax.bracket._

import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.common.future.{CloseFuture, SshFutureListener}

import scala.{Array, Byte, Int, Unit}
import scala.util.Right

import java.lang.SuppressWarnings

final class Process[F[_]: Concurrent: ContextShift] private[ssh] (
    channel: ChannelExec,
    val stdout: Stream[F, Byte],
    val stderr: Stream[F, Byte],
    val stdin: Pipe[F, Byte, Unit]) {

  private[this] val F = Concurrent[F]

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  val join: F[Int] =
    Concurrent cancelableF[F, Int] { cb =>
      Sync[F] delay {
        val listener: SshFutureListener[CloseFuture] = { _ =>
          val status = channel.getExitStatus()
          val statusI = if (status != null)
            status.intValue
          else
            0

          cb(Right(statusI))
        }

        channel.addCloseFutureListener(listener)

        Sync[F].delay(channel.removeCloseFutureListener(listener))
      }
    } guarantee ContextShift[F].shift
}

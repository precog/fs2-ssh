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

import scala.{Array, Byte, Int, Unit}

import java.lang.SuppressWarnings

final class Process[F[_]: Concurrent: ContextShift] private[ssh] (
    channel: ChannelExec,
    val stdout: Stream[F, Byte],
    val stderr: Stream[F, Byte],
    val stdin: Pipe[F, Byte, Unit]) {

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
}

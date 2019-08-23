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

import cats.effect.{Blocker, ContextShift, Sync}

import net.schmizz.sshj.connection.channel.direct.Session.Command

import scala.{Array, Byte, Int, Unit}

import java.lang.SuppressWarnings

@SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
final class Process[F[_]: Sync: ContextShift] private[ssh] (
    command: Command,
    blocker: Blocker,
    chunkSize: Int) {

  import blocker.blockOn
  private[this] val F = Sync[F]

  val stdout: Stream[F, Byte] =
    readInputStream(
      blockOn(F.delay(command.getInputStream)),
      chunkSize,
      blocker.blockingContext,
      closeAfterUse = false)

  val stderr: Stream[F, Byte] =
    readInputStream(
      blockOn(F.delay(command.getErrorStream)),
      chunkSize,
      blocker.blockingContext,
      closeAfterUse = false)

  val stdin: Pipe[F, Byte, Unit] =
    writeOutputStream(
      blockOn(F.delay(command.getOutputStream)),
      blocker.blockingContext,
      closeAfterUse = false)

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  val join: F[Int] =
    blockOn {
      F delay {
        command.join()

        val status = command.getExitStatus()
        if (status eq null)
          0   // ???
        else
          status.intValue
      }
    }
}

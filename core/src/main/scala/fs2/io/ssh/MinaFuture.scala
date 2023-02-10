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

import cats.effect.kernel.Async
import cats.effect.Sync
import cats.implicits._
import org.apache.sshd.client.future.OpenFuture
import org.apache.sshd.common.Closeable
import org.apache.sshd.common.future.{CloseFuture, SshFuture, SshFutureListener}
import org.apache.sshd.common.io.{IoReadFuture, IoWriteFuture}
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.future.{AuthFuture, ConnectFuture}
import scala.{Array, Boolean, Byte, Unit}
import scala.util.{Left, Right}
import java.lang.{SuppressWarnings, Throwable}

// this is an unsafe abstraction which exists solely because it should already exist
private[ssh] trait MinaFuture[S <: SshFuture[S]] {
  type A
  def cancel(s: S): Unit
  def exception(s: S): Throwable
  def result(s: S): A
}

private[ssh] object MinaFuture {

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  def fromFuture[
      F[_]: Async,
      S <: SshFuture[S]](
      fcf: F[S])(
      implicit S: MinaFuture[S])
      : F[S.A] = {

    Async[F].async[S.A] { cb =>
      fcf flatMap { fut =>
        Sync[F] delay {
          val _ = fut addListener { fut =>
            val e = S.exception(fut)
            if (e != null)
              cb(Left(e))
            else
              cb(Right(S.result(fut)))
          }

          Async[F].delay(S.cancel(fut)).some
        }
      }
    }
  }

  def awaitClose[F[_]: Async](c: Closeable): F[Unit] = {
    Async[F].async[Unit] { cb =>
      Async[F] delay {
        val listener: SshFutureListener[CloseFuture] =
          _ => cb(Right(()))

        c.addCloseFutureListener(listener)
        Async[F].delay(c.removeCloseFutureListener(listener))
      }.some
    }
  }

  implicit object SaneConnectFuture extends MinaFuture[ConnectFuture] {
    type A = ClientSession
    def cancel(s: ConnectFuture) = s.cancel()
    def exception(s: ConnectFuture) = s.getException()
    def result(s: ConnectFuture) = s.getSession()
  }

  implicit object SaneAuthFuture extends MinaFuture[AuthFuture] {
    type A = Boolean
    def cancel(s: AuthFuture) = s.cancel()
    def exception(s: AuthFuture) = s.getException()
    def result(s: AuthFuture) = s.isSuccess()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit object SaneOpenFuture extends MinaFuture[OpenFuture] {
    type A = Boolean
    def cancel(s: OpenFuture) = s.cancel()
    def exception(s: OpenFuture) = s.getException()
    def result(s: OpenFuture) = s.isOpened()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  implicit object SaneCloseFuture extends MinaFuture[CloseFuture] {
    type A = Boolean
    def cancel(s: CloseFuture) = ()
    def exception(s: CloseFuture) = null
    def result(s: CloseFuture) = s.isClosed()
  }

  implicit object SaneIoReadFuture extends MinaFuture[IoReadFuture] {
    type A = Chunk[Byte]

    def cancel(s: IoReadFuture) = ()
    def exception(s: IoReadFuture) = s.getException()

    def result(s: IoReadFuture) = {
      val buffer = s.getBuffer()
      // IoReadFuture#getRead tells us how many bytes were actually read, so we know how much of the array it is safe to consume
      val length = s.getRead()
      Chunk.array(buffer.array(), 0, length)
    }
  }

  implicit object SaneIoWriteFuture extends MinaFuture[IoWriteFuture] {
    type A = Boolean
    def cancel(s: IoWriteFuture) = ()
    def exception(s: IoWriteFuture) = s.getException()
    def result(s: IoWriteFuture) = s.isWritten()
  }
}

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
package io
package ssh

import cats.{Applicative, Functor}
import cats.data.EitherT
import cats.effect.kernel.Async
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import cats.implicits._
import cats.mtl.Raise
import org.specs2.execute.Result
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import scala.{Byte, None, Some, StringContext, Unit, math}
import scala.collection.immutable.Seq
import java.lang.{RuntimeException, String, System}
import java.net.InetSocketAddress
import java.nio.file.Paths

// these are really more like integration tests
// to run them locally, make sure you have things from
// the "fs2-ssh test server" entry in 1Password
// they will only run on Travis if you push your branch to upstream
class ClientSpec extends Specification with SshDockerService {

  val Timeout = 30.seconds
  implicit val ioRuntime: IORuntime = IORuntime.global

  val testHost = "localhost"
  val testPort = 2222
  val testUser = "fs2-ssh"
  val testPassword = "password"
  val keyPassword = "password"

  "ssh client" should {
    final case class WrappedError(e: Client.Error) extends RuntimeException(e.toString)

    // useful for tests where we're implicitly asserting no errors
    implicit val throwingFR: Raise[IO, Client.Error] =
      new Raise[IO, Client.Error] {
        val functor = Functor[IO]

        override def raise[E2 <: Client.Error, A](e: E2): IO[A] =
          IO.raiseError[A](WrappedError(e))
      }

    "authenticate with a password" in setup { (client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          testUser,
          Auth.Password(testPassword)),
        "whoami").void
    }

    "report authentication failure" in setupF[EitherT[IO, Client.Error, *]](
      { (client, isa) =>
        client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password("bippy")),
          "whoami").void
      },
      _.value.unsafeRunTimed(Timeout) must beSome(beLeft(Client.Error.Authentication: Client.Error)))

    "authenticate with an unprotected key" in setup { (client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          testUser,
          Auth.KeyFile(Paths.get("core", "src", "test", "resources", "nopassword"), None)),
        "whoami").void
    }

    "authenticate with an unprotected key in memory" in setup { (client, isa) =>
      for {
        keyChunks <-
          file.readAll[IO](
            Paths.get("core", "src", "test", "resources", "nopassword"),
            4096)
          .chunks
          .compile
          .resource
          .to(Seq)

        key = Chunk.concat(keyChunks).toArray[Byte]

        _ <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.KeyBytes(key, None)),
          "whoami")
      } yield ()
    }

    "authenticate with a protected key" in setup { (client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          testUser,
          Auth.KeyFile(
            Paths.get("core", "src", "test", "resources", "password"),
            Some(keyPassword))),
        "whoami").void
    }

    "authenticate with a protected key in memory" in setup { (client, isa) =>
      for {
        keyChunks <-
          file.readAll[IO](
            Paths.get("core", "src", "test", "resources", "password"),
            4096)
          .chunks
          .compile
          .resource
          .to(Seq)

        key = Chunk.concat(keyChunks).toArray[Byte]

        _ <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.KeyBytes(key, Some(keyPassword))),
          "whoami")
      } yield ()
    }

    "read from stdout" in setup { (client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "whoami")

        results <- p.stdout
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.eval(IO(results.trim mustEqual testUser))
      } yield ()
    }

    "read from stderr" in setup { (client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "whoami >&2")

        results <- p.stderr
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.eval(IO(results.trim mustEqual testUser))
      } yield ()
    }

    "write to stdin" in setup { (client, isa) =>
      val data = "Hello, It's me, I've been wondering if after all these years you'd like to meet"

      for {
        num <- Resource.eval(IO(math.abs(math.random() * 100000)))

        // we need to nest the resource here because we want to explicitly close the connection
        r = for {
          p1 <- client.exec(
            ConnectionConfig(
              isa,
              testUser,
              Auth.Password(testPassword)),
            s"cat > /tmp/testing-${num}")

          _ <- Stream.chunk(Chunk.array(data.getBytes))
              .through(p1.stdin)
              .compile
              .resource
              .drain
        } yield ()

        // you know, a close function on Resource would be really great right about now...
        _ <- Resource.eval(r.use(_ => IO.unit))

        p2 <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          s"cat /tmp/testing-${num}")

        results <- p2.stdout
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.eval(IO(results.trim mustEqual data))
      } yield ()
    }

    "join on remote command, awaiting result" in setup { (client, isa) =>
      for {
        now <- Resource.eval(IO(System.currentTimeMillis))

        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "sleep 5")

        status <- Resource.eval(p.join)
        now2 <- Resource.eval(IO(System.currentTimeMillis))

        _ <- Resource eval {
          IO {
            status mustEqual 0
            (now2 - now) must beGreaterThan(5000L)
          }
        }
      } yield ()
    }

    "join on remote command, reporting non-zero status" in setup { (client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "exit 1")

        status <- Resource.eval(p.join)
        _ <- Resource.eval(IO(status mustEqual 1))
      } yield ()
    }
  }

  def setup(f: (Client[IO], InetSocketAddress) => Resource[IO, Unit]): Result =
    setupF[IO](f, _.unsafeRunTimed(Timeout) must beSome)

  def setupF[F[_]: Async](
      f: (Client[F], InetSocketAddress) => Resource[F, Unit],
      finish: F[Unit] => Result)
      : Result = {

    val r = for {
      client <- Client[F]
      isa <- Resource.eval(Client.resolve[F](testHost, testPort))
      _ <- f(client, isa)
    } yield ()

    finish(r.use(_ => Applicative[F].unit))
  }
}

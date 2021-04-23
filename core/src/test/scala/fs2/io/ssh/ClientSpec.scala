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

import cats.Applicative
import cats.data.EitherT
import cats.effect.{Blocker, Concurrent, ContextShift, IO, Resource}
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.mtl.instances.all._

import org.specs2.execute.Result
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import scala.{math, Byte, None, Some, StringContext, Unit}
import scala.collection.immutable.Seq

import java.lang.{RuntimeException, String, System}
import java.net.InetSocketAddress
import java.nio.file.Paths

// these are really more like integration tests
// to run them locally, make sure you have things from
// the "fs2-ssh test server" entry in 1Password
// they will only run on Travis if you push your branch to upstream
class ClientSpec extends Specification with SshDockerService {
  implicit val cs = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.global)

  val Timeout = 30.seconds

  val testHost = "localhost"
  val testPort = 2222
  val testUser = "fs2-ssh"
  val testPassword = "password"
  val keyPassword = "password"

  "ssh client" should {
    final case class WrappedError(e: Client.Error) extends RuntimeException(e.toString)

    // useful for tests where we're implicitly asserting no errors
    implicit val throwingFR: FunctorRaise[IO, Client.Error] =
      new FunctorRaise[IO, Client.Error] {
        val functor = IO.ioEffect
        def raise[A](e: Client.Error) =
          IO.raiseError(WrappedError(e))
      }

    "authenticate with a password" in setup { (blocker, client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          testUser,
          Auth.Password(testPassword)),
        "whoami",
        blocker).void
    }

    "report authentication failure" in setupF[EitherT[IO, Client.Error, ?]](
      { (blocker, client, isa) =>
        client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password("bippy")),
          "whoami",
          blocker).void
      },
      _.value.unsafeRunTimed(Timeout) must beSome(beLeft(Client.Error.Authentication: Client.Error)))

    "authenticate with an unprotected key" in setup { (blocker, client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          testUser,
          Auth.KeyFile(Paths.get("core", "src", "test", "resources", "nopassword"), None)),
        "whoami",
        blocker).void
    }

    "authenticate with an unprotected key in memory" in setup { (blocker, client, isa) =>
      for {
        keyChunks <-
          file.readAll[IO](
            Paths.get("core", "src", "test", "resources", "nopassword"),
            blocker,
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
          "whoami",
          blocker)
      } yield ()
    }

    "authenticate with a protected key" in setup { (blocker, client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          testUser,
          Auth.KeyFile(
            Paths.get("core", "src", "test", "resources", "password"),
            Some(keyPassword))),
        "whoami",
        blocker).void
    }

    "authenticate with a protected key in memory" in setup { (blocker, client, isa) =>
      for {
        keyChunks <-
          file.readAll[IO](
            Paths.get("core", "src", "test", "resources", "password"),
            blocker,
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
          "whoami",
          blocker)
      } yield ()
    }

    "read from stdout" in setup { (blocker, client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "whoami",
          blocker)

        results <- p.stdout
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.liftF(IO(results.trim mustEqual testUser))
      } yield ()
    }

    "read from stderr" in setup { (blocker, client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "whoami >&2",
          blocker)

        results <- p.stderr
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.liftF(IO(results.trim mustEqual testUser))
      } yield ()
    }

    "write to stdin" in setup { (blocker, client, isa) =>
      val data = "Hello, It's me, I've been wondering if after all these years you'd like to meet"

      for {
        num <- Resource.liftF(IO(math.abs(math.random() * 100000)))

        // we need to nest the resource here because we want to explicitly close the connection
        r = for {
          p1 <- client.exec(
            ConnectionConfig(
              isa,
              testUser,
              Auth.Password(testPassword)),
            s"cat > /tmp/testing-${num}",
            blocker)

          _ <- Stream.chunk(Chunk.bytes(data.getBytes))
              .through(p1.stdin)
              .compile
              .resource
              .drain
        } yield ()

        // you know, a close function on Resource would be really great right about now...
        _ <- Resource.liftF(r.use(_ => IO.unit))

        p2 <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          s"cat /tmp/testing-${num}",
          blocker)

        results <- p2.stdout
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.liftF(IO(results.trim mustEqual data))
      } yield ()
    }

    "join on remote command, awaiting result" in setup { (blocker, client, isa) =>
      for {
        now <- Resource.liftF(IO(System.currentTimeMillis))

        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "sleep 5",
          blocker)

        status <- Resource.liftF(p.join)
        now2 <- Resource.liftF(IO(System.currentTimeMillis))

        _ <- Resource liftF {
          IO {
            status mustEqual 0
            (now2 - now) must beGreaterThan(5000L)
          }
        }
      } yield ()
    }

    "join on remote command, reporting non-zero status" in setup { (blocker, client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.Password(testPassword)),
          "exit 1",
          blocker)

        status <- Resource.liftF(p.join)
        _ <- Resource.liftF(IO(status mustEqual 1))
      } yield ()
    }

    "disconnect immediately when command is long-running" in {
      val cc = ConnectionConfig(_, testUser, Auth.Password(testPassword))

      val createTmp: (Blocker, Client[IO], InetSocketAddress) => Resource[IO, String] = (blocker, client, isa) =>
        for {
          num <- Resource.liftF(IO(math.abs(math.random * 100000)))

          tmpFile = s"/tmp/testing-${num}"

          _ <- client.exec(
            cc(isa),
            s"touch $tmpFile",
            blocker)
        } yield tmpFile

      val longRunningRead: String => (Blocker, Client[IO], InetSocketAddress) => Resource[IO, String] =
        tmpFile => (blocker, client, isa) =>
          for {
            p <- client.exec(
              cc(isa),
              s"tail -f -n0 $tmpFile",
              blocker,
              closeImmediately = true)

            results <- p.stdout
              .chunks
              .through(text.utf8DecodeC.andThen(text.lines))
              .collectFirst { case s@"1" => s }
              .take(1)
              .compile
              .resource
              .lastOrError
          } yield results

      val writeLine: (String, String) => (Blocker, Client[IO], InetSocketAddress) => Resource[IO, Unit] =
        (tmpFile, line) => (blocker, client, isa) =>
          for {
            p <- client.exec(
              cc(isa),
              s"cat >> $tmpFile",
              blocker)

            _ <- Stream.chunk(Chunk.bytes(s"$line\n".getBytes))
              .through(p.stdin)
              .compile
              .resource
              .drain
          } yield ()

      val createWriteRead = for {
        tmpFile <- setupIO(createTmp)

        sleep5 = IO.sleep(5.seconds)
        writeSlowly = for {
          _ <- sleep5
          _ <- setupIO(writeLine(tmpFile, "0"))
          _ <- sleep5
          _ <- setupIO(writeLine(tmpFile, "1"))
          _ <- sleep5
        } yield ()

        writeRead <- IO.race(
          writeSlowly,
          setupIO(longRunningRead(tmpFile)).timeout(Timeout)
        )
      } yield writeRead

      createWriteRead.unsafeRunTimed(Timeout) must beSome(beRight("1"))
    }
  }

  def setup(f: (Blocker, Client[IO], InetSocketAddress) => Resource[IO, Unit]): Result =
    setupF[IO](f, _.unsafeRunTimed(Timeout) must beSome)

  def setupF[F[_]: Concurrent: ContextShift](
      f: (Blocker, Client[F], InetSocketAddress) => Resource[F, Unit],
      finish: F[Unit] => Result)
      : Result = {

    val r = setupResourceF(f)
    finish(r.use(_ => Applicative[F].unit))
  }

  def setupIO[A](f: (Blocker, Client[IO], InetSocketAddress) => Resource[IO, A]): IO[A] =
    setupResourceF(f).use(IO.pure)

  def setupResourceF[F[_]: Concurrent: ContextShift, A](
      f: (Blocker, Client[F], InetSocketAddress) => Resource[F, A])
      : Resource[F, A] = {

    for {
      blocker <- Blocker[F]
      client <- Client[F]
      isa <- Resource.liftF(Client.resolve[F](testHost, testPort, blocker))
      res <- f(blocker, client, isa)
    } yield res
  }
}

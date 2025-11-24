/*
 * Copyright 2022 Precog Data Inc.
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
import cats.effect.{IO, Resource}
import cats.implicits._
import cats.mtl.Raise
import fs2.io.file.{Files, Flags}
import org.http4s.ember.client.EmberClientBuilder

import scala.concurrent.duration._
import scala.{Byte, None, Some, StringContext, Unit, math}
import scala.collection.immutable.Seq
import java.lang.{RuntimeException, String, System}
import java.net.InetSocketAddress
import java.nio.file.Paths
import munit.CatsEffectSuite
import scala.annotation.nowarn

class ClientSpec extends CatsEffectSuite with SshDockerService {

  override val munitIOTimeout = Duration(90, "s")

  val testHost = "localhost"
  val testUser = "fs2-ssh"
  val testPassword = "password"
  val keyPassword = "password"

  val fixture = ResourceFunFixture(containerResource)

  def setup[F[_]: Async](testPort: Int)(
      f: (Client[F], InetSocketAddress) => Resource[F, Unit]
  ) = setupF[F](testPort)(f)(identity)

  def setupF[F[_]: Async](testPort: Int)(
      f: (Client[F], InetSocketAddress) => Resource[F, Unit]
  )(
      finish: F[Unit] => F[Unit]
  ): F[Unit] = {

    val r = for {
      client <- Client[F]
      isa <- Resource.eval(Client.resolve[F](testHost, testPort))
      _ <- f(client, isa)
    } yield ()

    finish(r.use(_ => Applicative[F].unit))
  }

  @nowarn
  final case class WrappedError(e: Client.Error)
      extends RuntimeException(e.toString)

  // useful for tests where we're implicitly asserting no errors
  implicit val throwingFR: Raise[IO, Client.Error] =
    new Raise[IO, Client.Error] {
      val functor = Functor[IO]

      override def raise[E2 <: Client.Error, A](e: E2): IO[A] =
        IO.raiseError[A](WrappedError(e))
    }

  fixture.test("authenticate with a password") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      client
        .exec(
          ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
          "whoami"
        )
        .void
    }

  }

  fixture.test("report authentication failure") { c =>
    setupF[EitherT[IO, Client.Error, *]](c.mappedPort(22)) { (client, isa) =>
      client
        .exec(
          ConnectionConfig(isa, testUser, Auth.Password("bippy")),
          "whoami"
        )
        .void
    } { eit =>
      EitherT.liftF(
        eit.value.map(a =>
          assert(
            a.swap
              .map({ case Client.Error.SshErr(_) => true })
              .getOrElse(false),
            s"Got $a"
          )
        )
      )
    }

  }

  fixture.test("authenticate with an unprotected key") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      client
        .exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.KeyFile(
              Paths.get("core", "src", "test", "resources", "nopassword"),
              None
            )
          ),
          "whoami"
        )
        .void
    }
  }

  fixture.test("authenticate with an unprotected key in memory") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      for {
        keyChunks <-
          Files[IO]
            .readAll(
              fs2.io.file.Path.fromNioPath(
                Paths.get("core", "src", "test", "resources", "nopassword")
              ),
              4096,
              Flags.Read
            )
            .chunks
            .compile
            .resource
            .to(Seq)

        key = Chunk.concat(keyChunks).toArray[Byte]

        _ <- client.exec(
          ConnectionConfig(isa, testUser, Auth.KeyBytes(key, None)),
          "whoami"
        )
      } yield ()
    }
  }

  fixture.test("authenticate with a protected key") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      client
        .exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.KeyFile(
              Paths.get("core", "src", "test", "resources", "password"),
              Some(keyPassword)
            )
          ),
          "whoami"
        )
        .void
    }
  }

  fixture.test("authenticate with a protected key in memory") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      for {
        keyChunks <-
          Files[IO]
            .readAll(
              fs2.io.file.Path.fromNioPath(
                Paths.get("core", "src", "test", "resources", "password")
              ),
              4096,
              Flags.Read
            )
            .chunks
            .compile
            .resource
            .to(Seq)

        key = Chunk.concat(keyChunks).toArray[Byte]

        _ <- client.exec(
          ConnectionConfig(
            isa,
            testUser,
            Auth.KeyBytes(key, Some(keyPassword))
          ),
          "whoami"
        )
      } yield ()
    }
  }

  fixture.test("read from stdout") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
          "whoami"
        )

        results <- p.stdout.chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.eval(IO(assertEquals(results.trim, testUser)))
      } yield ()
    }
  }

  fixture.test("read from stderr") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
          "whoami >&2"
        )

        results <- p.stderr.chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.pure(assertEquals(results.trim, testUser))
      } yield ()
    }
  }

  fixture.test("write to stdin") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      val data =
        "Hello, It's me, I've been wondering if after all these years you'd like to meet"

      for {
        num <- Resource.eval(IO(math.abs(math.random() * 100000)))

        // we need to nest the resource here because we want to explicitly close the connection
        r = for {
          p1 <- client.exec(
            ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
            s"cat > /tmp/testing-${num}"
          )

          _ <- Stream
            .chunk(Chunk.array(data.getBytes))
            .through(p1.stdin)
            .compile
            .resource
            .drain
        } yield ()

        // you know, a close function on Resource would be really great right about now...
        _ <- Resource.eval(r.use(_ => IO.unit))

        p2 <- client.exec(
          ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
          s"cat /tmp/testing-${num}"
        )

        results <- p2.stdout.chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.pure(assertEquals(results.trim, data))
      } yield ()
    }
  }

  fixture.test("join on remote command, awaiting result") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      for {
        now <- Resource.eval(IO(System.currentTimeMillis))

        p <- client.exec(
          ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
          "sleep 5"
        )

        status <- Resource.eval(p.join)
        now2 <- Resource.eval(IO(System.currentTimeMillis))

        _ <- Resource eval {
          IO {
            assertEquals(status, 0)
            assert((now2 - now) > (5000L))
          }
        }
      } yield ()
    }
  }

  fixture.test("join on remote command, reporting non-zero status") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
          "exit 1"
        )

        status <- Resource.eval(p.join)
        _ <- Resource.pure(assertEquals(status, 1))
      } yield ()
    }
  }

  fixture.test("forward the port") { c =>
    setup[IO](c.mappedPort(22)) { (client, isa) =>
      client.portForward(
        ConnectionConfig(isa, testUser, Auth.Password(testPassword)),
        InetSocketAddress.createUnresolved("127.0.0.1", 3000),
        InetSocketAddress.createUnresolved(isa.getHostString, 3000)
      ) >> EmberClientBuilder.default[IO].build.evalMap { client =>
        client
          .get("http://127.0.0.1:3000")(r => IO.pure(r.status.isSuccess))
          .map { result =>
            assert(result)
          }
          .void
      }
    }
  }
}

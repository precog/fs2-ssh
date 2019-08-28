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

import cats.effect.{Blocker, IO, Resource}
import cats.implicits._

import org.specs2.execute.Result
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import scala.{math, sys, Byte, None, Some, StringContext, Unit}

import java.lang.{String, System}
import java.net.InetSocketAddress
import java.nio.file.Paths

// these are really more like integration tests
// to run them locally, make sure you have things from
// the "fs2-ssh test server" entry in 1Password
// they will only run on Travis if you push your branch to upstream
class ClientSpec extends Specification {
  implicit val cs = IO.contextShift(ExecutionContext.global)

  val Timeout = 30.seconds

  val TestHost = "ec2-54-205-215-42.compute-1.amazonaws.com"
  val TestUser = "fs2-ssh"
  val TestPassword = sys.env("FS2_SSH_TEST_PASSWORD")
  val KeyPassword = "password"

  "ssh client" should {
    "authenticate with a password" in setup { (blocker, client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          TestUser,
          Auth.Password(TestPassword)),
        "whoami",
        blocker).void
    }

    "authenticate with an unprotected key" in setup { (blocker, client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          TestUser,
          Auth.Key(Paths.get("keys", "nopassword"), None)),
        "whoami",
        blocker).void
    }

    "authenticate with a protected key" in setup { (blocker, client, isa) =>
      client.exec(
        ConnectionConfig(
          isa,
          TestUser,
          Auth.Key(Paths.get("keys", "password"), Some(KeyPassword))),
        "whoami",
        blocker).void
    }

    "read from stdout" in setup { (blocker, client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            TestUser,
            Auth.Password(TestPassword)),
          "whoami",
          blocker)

        results <- p.stdout
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.liftF(IO(results.trim mustEqual "fs2-ssh"))
      } yield ()
    }

    "read from stderr" in setup { (blocker, client, isa) =>
      for {
        p <- client.exec(
          ConnectionConfig(
            isa,
            TestUser,
            Auth.Password(TestPassword)),
          "whoami >&2",
          blocker)

        results <- p.stderr
          .chunks
          .map(bytes => new String(bytes.toArray[Byte]))
          .foldMonoid
          .compile
          .resource
          .lastOrError

        _ <- Resource.liftF(IO(results.trim mustEqual "fs2-ssh"))
      } yield ()
    }

    "write to stdin" in setup { (blocker, client, isa) =>
      val data = "Hello, It's me, I've been wondering if after all these years you'd like to meet"

      for {
        num <- Resource.liftF(IO(math.abs(math.random * 100000)))

        // we need to nest the resource here because we want to explicitly close the connection
        r = for {
          p1 <- client.exec(
            ConnectionConfig(
              isa,
              TestUser,
              Auth.Password(TestPassword)),
            s"cat > /tmp/testing-${num}",
            blocker)

          _ <- Stream(data.getBytes: _*)
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
            TestUser,
            Auth.Password(TestPassword)),
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
            TestUser,
            Auth.Password(TestPassword)),
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
            TestUser,
            Auth.Password(TestPassword)),
          "exit 1",
          blocker)

        status <- Resource.liftF(p.join)
        _ <- Resource.liftF(IO(status mustEqual 1))
      } yield ()
    }
  }

  def setup(f: (Blocker, Client[IO], InetSocketAddress) => Resource[IO, Unit]): Result = {
    val r = for {
      blocker <- Blocker[IO]
      client <- Client[IO]
      isa <- Resource.liftF(Client.resolve[IO](TestHost, 22, blocker))
      _ <- f(blocker, client, isa)
    } yield ()

    r.use(_ => IO.unit).unsafeRunTimed(Timeout) must beSome
  }
}

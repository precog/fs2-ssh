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

package fs2.io.ssh

import cats.implicits._

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.common.session.SessionContext

import scala.{Array, Byte, Option, Some, None}

import java.io.ByteArrayInputStream
import java.lang.{Iterable, String, SuppressWarnings}
import java.security.KeyPair

private[ssh] final class ByteArrayKeyPairProvider private (
    bytes: Array[Byte],
    maybePass: Option[String])
    extends AbstractKeyPairProvider {

  private[this] val BytesMagicKey = "<bytes>"

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def loadKeys(session: SessionContext): Iterable[KeyPair] = {
    val bis = new ByteArrayInputStream(bytes)

    SecurityUtils.loadKeyPairIdentities(
      session,
      NamedResource.ofName(BytesMagicKey),
      bis,
      maybePass match {
        case Some(password) =>
          { (session, key, _) =>
            if (key.getName() === BytesMagicKey)
              password
            else
              null
          }

        case None =>
          FilePasswordProvider.EMPTY
      })
  }
}

object ByteArrayKeyPairProvider {

  def apply(bytes: Array[Byte], maybePass: Option[String]): ByteArrayKeyPairProvider =
    new ByteArrayKeyPairProvider(bytes, maybePass)
}

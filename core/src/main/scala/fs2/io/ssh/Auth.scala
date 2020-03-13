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

import scala.{Array, Byte, Option, None, Product, Serializable}

import java.lang.{String, SuppressWarnings}
import java.nio.file.Path

sealed trait Auth extends Product with Serializable

object Auth {

  final case class Password(text: String) extends Auth

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class KeyFile(privateKey: Path, password: Option[String] = None) extends Auth

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.DefaultArguments",
      "org.wartremover.warts.ArrayEquals"))   // I hate wartremover...
  final case class KeyBytes(privateKey: Array[Byte], password: Option[String] = None) extends Auth
}

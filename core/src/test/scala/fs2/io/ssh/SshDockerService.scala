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

package fs2.io.ssh

import java.io.File

import com.spotify.docker.client.DefaultDockerClient
import com.whisk.docker.DockerContainer
import com.whisk.docker.impl.spotify.DockerKitSpotify
import com.whisk.docker.specs2.DockerTestKit

import scala.{List, Some}
import scala.Predef.ArrowAssoc

trait SshDockerService extends DockerTestKit with DockerKitSpotify {
  private val dockerClient =  DefaultDockerClient.fromEnv().build()

  def buildsshService(): DockerContainer ={
    dockerClient.build(new File("core/src/test/resources/docker").toPath, "fs2-ssh")
    DockerContainer("fs2-ssh")
      .withPorts(22 -> Some(2222))
  }

  abstract override def dockerContainers: List[DockerContainer] =
    buildsshService() :: super.dockerContainers
}
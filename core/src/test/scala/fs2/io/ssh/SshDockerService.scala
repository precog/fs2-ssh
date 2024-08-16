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

package fs2.io.ssh

import cats.effect.Resource
import cats.effect.IO
import org.testcontainers.images.builder.ImageFromDockerfile
import com.dimafeng.testcontainers.GenericContainer
import java.nio.file.Path

trait SshDockerService {

  val containerResource = Resource
    .make {
      for {

        c <- IO.delay {

          val img: ImageFromDockerfile =
            new ImageFromDockerfile().withFileFromPath(
              ".",
              Path.of("core/src/test/resources/docker")
            )

          val cont =
            GenericContainer.apply(img.getDockerImageName(), List(22, 3000))

          img.get()

          cont.start()

          cont
        }
      } yield c
    }(c => IO.delay(c.stop()))

}

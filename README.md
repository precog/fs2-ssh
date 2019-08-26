# fs2-ssh [![Build Status](https://travis-ci.com/slamdata/fs2-ssh.svg?branch=master)](https://travis-ci.com/slamdata/fs2-ssh) [![Bintray](https://img.shields.io/bintray/v/slamdata-inc/maven-public/fs2-ssh.svg)](https://bintray.com/slamdata-inc/maven-public/fs2-ssh) [![Discord](https://img.shields.io/discord/373302030460125185.svg?logo=discord)](https://discord.gg/QNjwCg6)

A lightweight wrapper around [SSHJ](https://github.com/hierynomus/sshj). The primary purpose of this library is to provide a resource-safe, functional, thread-pool-safe API for SSH access within the [Cats Effect](https://github.com/typelevel/cats-effect) ecosystem.

## Usage

```sbt
libraryDependencies += "com.slamdata" %% "fs2-ssh" % <version>
```

fs2-ssh is currently published for Scala 2.12. It depends on Cats Effect 1.4.0, fs2 1.0.5, and SSHJ 0.27.0.

## Functionality

Everything is encapsulated within the `fs2.io.ssh.Client` object. Currently, the only available functionality (ignoring the `resolve` helper function) is `exec`, which executes a command on the remote server and exits. Password and public/private key authentication are both supported, as are password-protected private keys, via the `Auth` ADT. The command is given as a `String`, and in my experience most remote servers seem to parse it with shell-style functionality (e.g. wildcards and piping seem to be supported most of the time).

`exec` returns a `Process`, which provides access to `stdin`/`stderr`/`stdout`, represented as `Stream`s, and a single effect, `join`, which blocks until the command completes and produces the exit status code. It's theoretically possible to add more functionality here, such as signal handling, but that hasn't yet been required.

The `known_hosts` file is respected, but not written.

All operations are performed on the blocking pool encapsulated by the specified `Blocker` instance. I'm not aware of any SSH client libraries written using async IO on the JVM, but if one existed, we would be using it.

### Future

Some future expected functionality:

- Shell access
- Tunneling
- Jump hosting
- Zlib compression

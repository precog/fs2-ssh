# fs2-ssh [![Build Status](https://travis-ci.com/slamdata/fs2-ssh.svg?branch=master)](https://travis-ci.com/slamdata/fs2-ssh) [![Bintray](https://img.shields.io/bintray/v/slamdata-inc/maven-public/fs2-ssh.svg)](https://bintray.com/slamdata-inc/maven-public/fs2-ssh) [![Discord](https://img.shields.io/discord/373302030460125185.svg?logo=discord)](https://discord.gg/QNjwCg6)

A lightweight wrapper around [Apache SSHD](https://mina.apache.org/sshd-project/). The primary purpose of this library is to provide a resource-safe, functional, thread-pool-safe API for SSH within the [Cats Effect](https://github.com/typelevel/cats-effect) ecosystem.

At present, only limited client functionality is offered, but future functionality is likely to include further client functionality (such as shell access, tunneling, and signals) as well as a server API.

## Usage

```sbt
libraryDependencies += "com.slamdata" %% "fs2-ssh" % <version>
```

fs2-ssh is currently published for Scala 2.12. It depends on Cats Effect 1.4.0, fs2 1.0.5, SSHD 2.3.0, and Netty 4.1.39.

## Functionality

Everything is encapsulated within the `fs2.io.ssh.Client` object. Currently, the only available functionality (ignoring the `resolve` helper function) is `exec`, which executes a command on the remote server and exits. Password and public/private key authentication are both supported, as are password-protected private keys, via the `Auth` ADT. The command is given as a `String`, and in my experience most remote servers seem to parse it with shell-style functionality (e.g. wildcards and piping seem to be supported most of the time).

`exec` returns a `Process`, which provides access to `stdin`/`stderr`/`stdout`, represented as `Stream`s, and a single effect, `join`, which blocks until the command completes and produces the exit status code. It's theoretically possible to add more functionality here, such as signal handling, but that hasn't yet been required.

It's worth noting that there is (currently) no way to send `EOF` to the destination. This is technically possible internally (for example, perhaps when the stream being piped to `stdin` completes), but that would create a situation where resource safety is compromised. Instead, it's better to rely on terminating the resource which manages the `Process`. This corresponds to sending `Ctrl-C` to the destination.

The `known_hosts` file is respected, but not written. It's worth noting that SSHD does some weird juju with `known_hosts` and appears to read it in unmanaged threads, which can't be helped without completely ignoring the file.

It's also worth noting that SSHD's Netty usage seems to be somewhat naive, and it does a lot of work on the event dispatcher pool (most notably, decrypting and parsing the SSH protocol itself). This is deeply unfortunate, but there's not much we can do about it other than switching to MINA for as an upstream NIO framework, and even then I suspect that they still wouldn't be context shifting correctly. As a result, throughput is somewhat lower under load then you might expect, given the other internals.

## Contributing

Apache License v2. Don't format code bizarrely. Don't force push to PRs. You know the drill. The weird and unfortunate thing is that external contributors will not be able to run the "unit" tests (which are really integration tests) without private credentials. The reason for this is the tests actually shell into a server hosted in EC2 (generously sponsored by [SlamData](https://github.com/slamdata)) in order to test that the client functionality is compatible with OpenSSH. This is all well and good, but the credentials cannot be made public for reasons that likely involve bitcoin mining and botnets.

Travis *does* run the integration tests, but only on branches which are pushed to the upstream fork. This means that third-party PRs will never build successfully in CI. Sorry. 😔

## Future

Some future expected functionality:

- Shell access
- Tunneling
- Jump hosting
- Server things

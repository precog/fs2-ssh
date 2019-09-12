FROM debian:buster
MAINTAINER koisell.dev@gmail.com

RUN apt-get update && apt-get install -y openssh-server
RUN mkdir /var/run/sshd
ADD sshd_config /etc/ssh/sshd_config

RUN useradd -ms /bin/bash fs2-ssh
RUN echo 'fs2-ssh:password' | chpasswd
RUN mkdir -p /home/fs2-ssh/.ssh
RUN touch /home/fs2-ssh/.ssh/authorized_keys
ADD nopassword.pub password.pub /home/fs2-ssh/
RUN cat /home/fs2-ssh/nopassword.pub /home/fs2-ssh/password.pub >> /home/fs2-ssh/.ssh/authorized_keys

EXPOSE 22
CMD ["/usr/sbin/sshd", "-D"]

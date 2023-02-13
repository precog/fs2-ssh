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

RUN apt-get install -y npm
RUN mkdir -p /home/fs2-ssh/nodejs_app
RUN cd /home/fs2-ssh/nodejs_app && npm init -y
RUN cd /home/fs2-ssh/nodejs_app && npm install -y express
RUN cd /home/fs2-ssh/nodejs_app && npm install -y pm2 -g
ADD index.js /home/fs2-ssh/nodejs_app
ADD script.sh /home/fs2-ssh/
RUN chmod +x /home/fs2-ssh/script.sh
EXPOSE 22
EXPOSE 3000

CMD ["/home/fs2-ssh/script.sh"]

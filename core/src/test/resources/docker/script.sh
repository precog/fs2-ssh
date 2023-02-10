#!/bin/bash

set -m

pm2 start /home/fs2-ssh/nodejs_app/index.js
/usr/sbin/sshd -D
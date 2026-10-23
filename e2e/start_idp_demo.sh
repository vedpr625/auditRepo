#!/usr/bin/env bash
cd "$(dirname "$0")"; DEMO=1 setsid nohup python3 mock_idp.py 300 > idp.out 2>&1 &
sleep 2

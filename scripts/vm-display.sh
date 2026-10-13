#!/usr/bin/env bash
# Runs inside the OpenClaw VM. Gives the VM a virtual screen so the agent's browser is visible
# (and recordable) from the host: Xvfb :99 -> x11vnc on 127.0.0.1:5900 -> noVNC on 127.0.0.1:6080.
# Lima forwards the guest's localhost ports to the host's localhost, so open http://localhost:6080/vnc.html
# on the Mac. Nothing listens beyond loopback, so VNC has no password.
#
# From the host:
#   limactl copy scripts/vm-display.sh openclaw:vm-display.sh
#   limactl shell openclaw -- bash -l vm-display.sh
set -euo pipefail

W=${W:-1600}; H=${H:-1000}

sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb x11vnc novnc websockify openbox >/dev/null

units="$HOME/.config/systemd/user"
mkdir -p "$units" "$HOME/.config/environment.d"

cat > "$units/poc-xvfb.service" <<EOF
[Unit]
Description=Virtual display :99 for the agent's browser
[Service]
ExecStart=/usr/bin/Xvfb :99 -screen 0 ${W}x${H}x24 -nolisten tcp
Restart=on-failure
[Install]
WantedBy=default.target
EOF

cat > "$units/poc-openbox.service" <<'EOF'
[Unit]
Description=Window manager on :99 (maximizes the agent's browser)
After=poc-xvfb.service
Requires=poc-xvfb.service
[Service]
Environment=DISPLAY=:99
ExecStart=/usr/bin/openbox
Restart=on-failure
[Install]
WantedBy=default.target
EOF

cat > "$units/poc-x11vnc.service" <<'EOF'
[Unit]
Description=VNC view of :99, loopback only
After=poc-xvfb.service
Requires=poc-xvfb.service
[Service]
ExecStart=/usr/bin/x11vnc -display :99 -localhost -rfbport 5900 -forever -shared -nopw -quiet
Restart=on-failure
[Install]
WantedBy=default.target
EOF

cat > "$units/poc-novnc.service" <<'EOF'
[Unit]
Description=noVNC in the browser at 127.0.0.1:6080
After=poc-x11vnc.service
Requires=poc-x11vnc.service
[Service]
ExecStart=/usr/bin/websockify --web /usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900
Restart=on-failure
[Install]
WantedBy=default.target
EOF

# The gateway service launches the agent's browser; give it the virtual display.
echo "DISPLAY=:99" > "$HOME/.config/environment.d/60-agent-display.conf"
systemctl --user set-environment DISPLAY=:99

# Keep user services running without an open shell.
sudo loginctl enable-linger "$USER"

systemctl --user daemon-reload
systemctl --user enable --now poc-xvfb.service poc-openbox.service poc-x11vnc.service poc-novnc.service
sleep 2
systemctl --user --no-pager --plain list-units 'poc-*' | sed -n '1,6p'

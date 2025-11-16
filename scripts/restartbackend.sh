# Reload systemd
sudo systemctl daemon-reload

# Start services
sudo systemctl stop secman-backend.service

sudo systemctl start secman-backend.service

sudo systemctl status secman-backend.service
# Reload systemd
sudo systemctl daemon-reload

# Start services
sudo systemctl stop secman-frontend.service

sudo systemctl start secman-frontend.service

sudo systemctl status secman-frontend.service
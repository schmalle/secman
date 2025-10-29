# Secman Production Deployment - Quick Start

## 🚨 Fix "allowedHosts" Error - Immediate Solution

Replace `YOURDOMAIN.com` with your actual domain:

```bash
cd /path/to/secman/src/frontend
export ALLOWED_HOSTS=YOURDOMAIN.com
npm run build
node dist/server/entry.mjs
```

## 📋 Permanent Solution (Recommended)

### Step 1: Create `.env` file

```bash
cd src/frontend
cat > .env << 'EOF'
ALLOWED_HOSTS=your-actual-domain.com
API_TARGET=http://localhost:8080
HMR_CLIENT_PORT=443
PUBLIC_API_URL=https://your-actual-domain.com
EOF
```

### Step 2: Build and Deploy

```bash
# Build
npm run build

# Deploy with PM2 (recommended)
npm install -g pm2
pm2 start dist/server/entry.mjs --name secman-frontend
pm2 save
pm2 startup

# OR deploy with systemd (see PRODUCTION-DEPLOYMENT.md)
```

### Step 3: Configure Nginx (if using reverse proxy)

```nginx
server {
    listen 443 ssl http2;
    server_name your-actual-domain.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://localhost:4321;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## 🔍 Troubleshooting

### Error persists after setting ALLOWED_HOSTS?

Check environment is loaded:
```bash
echo $ALLOWED_HOSTS
```

If empty, reload:
```bash
source .env
# or
export ALLOWED_HOSTS=your-domain.com
```

### Multiple domains?

Use comma separation:
```bash
ALLOWED_HOSTS=domain1.com,domain2.com,subdomain.domain1.com
```

### Wildcard subdomains?

Use dot prefix:
```bash
ALLOWED_HOSTS=.example.com
# Allows: app.example.com, api.example.com, etc.
```

## 📚 Full Documentation

See `src/frontend/PRODUCTION-DEPLOYMENT.md` for:
- Complete deployment guide
- PM2 configuration
- Systemd service setup
- Security checklist
- Monitoring setup

## 🎯 Quick Commands

```bash
# Check status
pm2 status secman-frontend

# View logs
pm2 logs secman-frontend

# Restart after config change
pm2 restart secman-frontend --update-env

# Stop service
pm2 stop secman-frontend
```

## ⚠️ Important Notes

1. **Always set specific domains** in production (not wildcards)
2. **Use HTTPS** with valid SSL certificates
3. **Set NODE_ENV=production** in your environment
4. **Secure your backend API** - don't expose it directly to internet
5. **Monitor logs** regularly for errors

## 📞 Need Help?

- Check logs: `pm2 logs` or `journalctl -u secman-frontend -f`
- Validate config: `cat src/frontend/astro.config.mjs | grep allowedHosts`
- Test locally: `ALLOWED_HOSTS=localhost npm run dev`

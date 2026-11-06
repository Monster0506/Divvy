# Deploying from the server console

Use a Linux host with systemd, Docker Compose v2, Git and `flock` (the `util-linux` package). The full stack targets 4 vCPU / 8 GB. Run these commands in the server's console; no SSH keys, deploy keys or GitHub tokens are needed for this public repository.

## First setup

Clone over public HTTPS into `~/divvy`:

```bash
git clone --branch main https://github.com/Monster0506/Divvy.git "$HOME/divvy"
cd "$HOME/divvy"
cp .env.example .env
chmod 600 .env
editor .env
```

Set the database credentials, `API_KEY`, and any ML settings in `.env`. Keep those secrets on the server and back up a copy somewhere safe. Start the stack with:

```bash
sudo docker compose up -d --build
sudo docker compose ps
```

Use your existing server address in the app under Account > Server. Network access and HTTPS are managed by your hosting environment; the updater does not configure them.

## Automatic updates

Once the stack is running and the updater has been merged to main:

```bash
sudo bash "$HOME/divvy/deploy/install-auto-update.sh"
```

This installs a systemd service and a timer that checks main about once a minute. The installer resolves the checkout's absolute path and runs the updater as its owner, with Docker group access when available. It runs an initial update immediately; allow time for the image build. Later updates run only when the server's build inputs change. The command is exactly `docker compose up -d --build`.

See [Automatic server updates in the README](../README.md#automatic-server-updates) for archive-to-checkout conversion, logs, manual runs and disabling the timer. The old upload-from-a-laptop deployment is not needed for automatic updates.

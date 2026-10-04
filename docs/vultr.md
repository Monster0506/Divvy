# Deploying to a Vultr server

Plan: Cloud Compute, 2 vCPU / 4 GB, Ubuntu 24.04, with your SSH key added.

1. Add the public key (`~/.ssh/divvy_vultr.pub`) in Vultr when you create the server.
2. Put your Qwen key in the environment, then deploy with the server's IP:

       export QWEN_API_KEY=...
       make deploy HOST=203.0.113.5

   The first run takes a few minutes (Docker install, image build, certificate). It prints the URL and API key to
   enter in the app under Account > Server. Later runs only need `make deploy`.

3. HTTPS uses a free `<ip>.sslip.io` name by default. For your own domain, add an A record for it pointing at the
   server and run `make deploy DOMAIN=api.example.com`.

Other targets: `make status`, `make logs`, `make ssh`, `make restart`, `make backup` (saves a database dump to
`deploy/backups/`). Secrets live in `deploy/prod.env` (gitignored); keep a copy somewhere safe. Only the last commit is
deployed, so commit before you run it.

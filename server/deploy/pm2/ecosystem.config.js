// pm2 process for the Support Visit server (same setup as the other pm2 apps on the host).
//   pm2 start ecosystem.config.js && pm2 save
// Settings live in .env next to this file (see ../.env.example); start.sh loads them.
module.exports = {
  apps: [
    {
      name: "supportvisit",
      script: "./start.sh",
      interpreter: "bash",
      cwd: __dirname,
      autorestart: true,
      max_restarts: 50,
      restart_delay: 3000,
      kill_timeout: 10000,
    },
  ],
};

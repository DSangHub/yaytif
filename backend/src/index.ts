import 'dotenv/config';
import { buildApp } from './app.js';
import { parseEnv } from './config/env.js';
import { disconnectPrisma } from './lib/prisma.js';

export { buildApp, type BuildAppOptions } from './app.js';

async function start(): Promise<void> {
  const env = parseEnv();
  const app = await buildApp({ env });

  let shuttingDown = false;
  const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    app.log.info({ signal }, 'Shutting down Yaytif backend server');
    try {
      await app.close();
      process.exit(0);
    } catch (error) {
      app.log.error(error, 'Error during graceful shutdown');
      process.exit(1);
    }
  };

  process.on('SIGINT', () => {
    void shutdown('SIGINT');
  });
  process.on('SIGTERM', () => {
    void shutdown('SIGTERM');
  });

  try {
    await app.listen({ host: env.HOST, port: env.PORT });
  } catch (error) {
    app.log.error(error, 'Failed to start Yaytif backend server');
    await disconnectPrisma();
    process.exit(1);
  }
}

const isDirectExecution =
  process.argv[1] !== undefined &&
  (process.argv[1].endsWith('/index.ts') || process.argv[1].endsWith('/index.js'));

if (isDirectExecution) {
  void start();
}

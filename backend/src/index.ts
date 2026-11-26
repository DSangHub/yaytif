import 'dotenv/config';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import Fastify, { type FastifyInstance } from 'fastify';
import { parseEnv, type Env } from './config/env.js';
import { checkDatabaseConnection, disconnectPrisma } from './lib/prisma.js';

export interface ServerOptions {
  env?: Env;
}

export async function buildServer(options: ServerOptions = {}): Promise<FastifyInstance> {
  const env = options.env ?? parseEnv();

  const app = Fastify({
    logger: {
      level: env.LOG_LEVEL,
    },
    trustProxy: true,
  });

  await app.register(helmet, {
    contentSecurityPolicy: false,
  });

  const allowedOrigins = env.CORS_ORIGIN.split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);

  await app.register(cors, {
    origin: allowedOrigins.length === 1 && allowedOrigins[0] === '*' ? true : allowedOrigins,
    credentials: true,
  });

  app.get('/health', async () => {
    return {
      status: 'ok',
      service: 'yaytif-backend',
      timestamp: new Date().toISOString(),
      uptimeSeconds: Math.floor(process.uptime()),
    };
  });

  app.get('/health/ready', async (_request, reply) => {
    const databaseConnected = await checkDatabaseConnection();
    if (!databaseConnected) {
      return reply.code(503).send({
        status: 'degraded',
        service: 'yaytif-backend',
        database: 'disconnected',
        timestamp: new Date().toISOString(),
      });
    }

    return reply.code(200).send({
      status: 'ready',
      service: 'yaytif-backend',
      database: 'connected',
      timestamp: new Date().toISOString(),
    });
  });

  app.addHook('onClose', async () => {
    await disconnectPrisma();
  });

  return app;
}

async function start(): Promise<void> {
  const env = parseEnv();
  const app = await buildServer({ env });

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

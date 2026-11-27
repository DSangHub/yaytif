import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import type { PrismaClient } from '@prisma/client';
import Fastify, { type FastifyInstance } from 'fastify';
import { parseEnv, type Env } from './config/env.js';
import { registerErrorHandler } from './lib/errors.js';
import { checkDatabaseConnection, disconnectPrisma, prisma as defaultPrisma } from './lib/prisma.js';
import { registerAlertRoutes } from './routes/alerts.js';
import type { RouteContext } from './routes/context.js';
import { registerPayoutRoutes } from './routes/payouts.js';
import { registerPostbackRoutes } from './routes/postbacks.js';
import { registerSessionRoutes } from './routes/sessions.js';
import { registerUserRoutes } from './routes/users.js';
import { registerWalletRoutes } from './routes/wallet.js';

export interface BuildAppOptions {
  env?: Env;
  prisma?: PrismaClient;
  now?: () => Date;
}

export async function buildApp(options: BuildAppOptions = {}): Promise<FastifyInstance> {
  const env = options.env ?? parseEnv();
  const prismaClient = options.prisma ?? defaultPrisma;
  const ownsPrismaLifecycle = options.prisma === undefined;
  const now = options.now ?? (() => new Date());

  const app = Fastify({
    logger: {
      level: env.LOG_LEVEL,
    },
    trustProxy: true,
  });

  app.setErrorHandler(registerErrorHandler);

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
      timestamp: now().toISOString(),
      uptimeSeconds: Math.floor(process.uptime()),
    };
  });

  app.get('/health/ready', async (_request, reply) => {
    const databaseConnected = await checkDatabaseConnection(prismaClient);
    if (!databaseConnected) {
      return reply.code(503).send({
        status: 'degraded',
        service: 'yaytif-backend',
        database: 'disconnected',
        timestamp: now().toISOString(),
      });
    }

    return reply.code(200).send({
      status: 'ready',
      service: 'yaytif-backend',
      database: 'connected',
      timestamp: now().toISOString(),
    });
  });

  const routeContext: RouteContext = {
    prisma: prismaClient,
    env,
    now,
  };

  await app.register(
    async (api) => {
      registerUserRoutes(api, routeContext);
      registerAlertRoutes(api, routeContext);
      registerSessionRoutes(api, routeContext);
      registerPostbackRoutes(api, routeContext);
      registerWalletRoutes(api, routeContext);
      registerPayoutRoutes(api, routeContext);
    },
    { prefix: '/api/v1' },
  );

  if (ownsPrismaLifecycle) {
    app.addHook('onClose', async () => {
      await disconnectPrisma();
    });
  }

  return app;
}

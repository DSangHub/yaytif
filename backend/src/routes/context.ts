import type { PrismaClient } from '@prisma/client';
import type { Env } from '../config/env.js';

export interface RouteContext {
  prisma: PrismaClient;
  env: Env;
  now: () => Date;
}

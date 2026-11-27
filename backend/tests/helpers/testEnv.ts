import type { Env } from '../../src/config/env.js';

export function createTestEnv(overrides: Partial<Env> = {}): Env {
  return {
    NODE_ENV: 'test',
    HOST: '127.0.0.1',
    PORT: 3001,
    LOG_LEVEL: 'silent',
    CORS_ORIGIN: 'http://localhost:3000',
    DATABASE_URL: 'postgresql://yaytif:yaytif@localhost:5432/yaytif_test',
    DAILY_ATTENTION_EARNINGS_CAP: '10.000000',
    POSTBACK_SECRET: undefined,
    ...overrides,
  };
}

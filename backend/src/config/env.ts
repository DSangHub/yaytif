import { Prisma } from '@prisma/client';
import { z } from 'zod';

const positiveDecimalEnvSchema = z
  .string()
  .trim()
  .regex(/^\d+(\.\d{1,6})?$/, 'Must be a positive decimal string with up to 6 decimal places')
  .refine((value) => new Prisma.Decimal(value).gt(0), {
    message: 'Must be greater than 0',
  });

const optionalSecretSchema = z.preprocess(
  (value) => (typeof value === 'string' && value.trim() === '' ? undefined : value),
  z.string().min(1).optional(),
);

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().min(1).default('0.0.0.0'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3001),
  LOG_LEVEL: z
    .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
    .default('info'),
  CORS_ORIGIN: z.string().min(1).default('http://localhost:3000'),
  DATABASE_URL: z.string().min(1, 'DATABASE_URL is required'),
  DAILY_ATTENTION_EARNINGS_CAP: positiveDecimalEnvSchema.default('10.000000'),
  POSTBACK_SECRET: optionalSecretSchema,
});

export type Env = z.infer<typeof envSchema>;

export function parseEnv(rawEnv: NodeJS.ProcessEnv = process.env): Env {
  const result = envSchema.safeParse(rawEnv);
  if (!result.success) {
    const formattedErrors = result.error.issues
      .map((issue) => `${issue.path.join('.') || 'env'}: ${issue.message}`)
      .join('; ');
    throw new Error(`Invalid environment configuration: ${formattedErrors}`);
  }
  return result.data;
}

import { Prisma } from '@prisma/client';
import { z } from 'zod';

export const uuidSchema = z.string().uuid();

export const countryCodeSchema = z
  .string()
  .trim()
  .length(2, 'countryCode must be a 2-letter ISO code')
  .transform((value) => value.toUpperCase())
  .refine((value) => /^[A-Z]{2}$/.test(value), {
    message: 'countryCode must contain only ASCII letters',
  });

export const localeSchema = z
  .string()
  .trim()
  .min(2)
  .max(16)
  .regex(/^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$/, 'Invalid BCP-47 locale format');

export const currencySchema = z
  .string()
  .trim()
  .length(3, 'currency must be a 3-letter ISO 4217 code')
  .transform((value) => value.toUpperCase())
  .refine((value) => /^[A-Z]{3}$/.test(value), {
    message: 'currency must contain only ASCII letters',
  });

export const interestsSchema = z
  .array(
    z
      .string()
      .trim()
      .min(1, 'Interest tag cannot be empty')
      .max(64, 'Interest tag must be at most 64 characters'),
  )
  .max(50, 'At most 50 interests are allowed')
  .transform((items) => [...new Set(items)]);

export const positiveDecimalStringSchema = z
  .string()
  .trim()
  .regex(/^\d+(\.\d{1,6})?$/, 'Must be a positive decimal string with up to 6 fractional digits')
  .refine((value) => new Prisma.Decimal(value).gt(0), {
    message: 'Amount must be greater than 0',
  });

export const emptyQuerySchema = z.object({}).strict();

export const userIdParamsSchema = z
  .object({
    userId: uuidSchema,
  })
  .strict();

export function parseInput<TSchema extends z.ZodTypeAny>(
  schema: TSchema,
  input: unknown,
): z.output<TSchema> {
  return schema.parse(input);
}
